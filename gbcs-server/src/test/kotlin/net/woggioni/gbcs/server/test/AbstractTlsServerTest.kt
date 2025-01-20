package net.woggioni.gbcs.server.test

import net.woggioni.gbcs.api.Configuration
import net.woggioni.gbcs.api.Role
import net.woggioni.gbcs.common.Xml
import net.woggioni.gbcs.server.cache.FileSystemCacheConfiguration
import net.woggioni.gbcs.server.configuration.Serializer
import net.woggioni.gbcs.server.test.utils.CertificateUtils
import net.woggioni.gbcs.server.test.utils.CertificateUtils.X509Credentials
import net.woggioni.gbcs.server.test.utils.NetworkUtils
import org.bouncycastle.asn1.x500.X500Name
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.KeyStore.PasswordProtection
import java.time.Duration
import java.util.Base64
import java.util.zip.Deflater
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import kotlin.random.Random


abstract class AbstractTlsServerTest : AbstractServerTest() {

    companion object {
        private const val CA_CERTIFICATE_ENTRY = "gbcs-ca"
        private const val CLIENT_CERTIFICATE_ENTRY = "gbcs-client"
        private const val SERVER_CERTIFICATE_ENTRY = "gbcs-server"
        private const val PASSWORD = "password"
    }

    private lateinit var cacheDir: Path
    private lateinit var serverKeyStoreFile: Path
    private lateinit var clientKeyStoreFile: Path
    private lateinit var trustStoreFile: Path
    private lateinit var serverKeyStore: KeyStore
    private lateinit var clientKeyStore: KeyStore
    private lateinit var trustStore: KeyStore
    protected lateinit var ca: X509Credentials

    protected val readersGroup = Configuration.Group("readers", setOf(Role.Reader))
    protected val writersGroup = Configuration.Group("writers", setOf(Role.Writer))
    protected val random = Random(101325)
    protected val keyValuePair = newEntry(random)
    private val serverPath : String? = null

    protected abstract val users : List<Configuration.User>

    protected fun createKeyStoreAndTrustStore() {
        ca = CertificateUtils.createCertificateAuthority(CA_CERTIFICATE_ENTRY, 30)
        val serverCert = CertificateUtils.createServerCertificate(ca, X500Name("CN=$SERVER_CERTIFICATE_ENTRY"), 30)
        val clientCert = CertificateUtils.createClientCertificate(ca, X500Name("CN=$CLIENT_CERTIFICATE_ENTRY"), 30)

        serverKeyStore = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setEntry(CA_CERTIFICATE_ENTRY, KeyStore.TrustedCertificateEntry(ca.certificate), PasswordProtection(null))
            setEntry(
                SERVER_CERTIFICATE_ENTRY,
                KeyStore.PrivateKeyEntry(
                    serverCert.keyPair().private,
                    arrayOf(serverCert.certificate(), ca.certificate)
                ),
                PasswordProtection(PASSWORD.toCharArray())
            )
        }
        Files.newOutputStream(this.serverKeyStoreFile).use {
            serverKeyStore.store(it, null)
        }

        clientKeyStore = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setEntry(CA_CERTIFICATE_ENTRY, KeyStore.TrustedCertificateEntry(ca.certificate), PasswordProtection(null))
            setEntry(
                CLIENT_CERTIFICATE_ENTRY,
                KeyStore.PrivateKeyEntry(
                    clientCert.keyPair().private,
                    arrayOf(clientCert.certificate(), ca.certificate)
                ),
                PasswordProtection(PASSWORD.toCharArray())
            )
        }
        Files.newOutputStream(this.clientKeyStoreFile).use {
            clientKeyStore.store(it, null)
        }

        trustStore = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setEntry(CA_CERTIFICATE_ENTRY, KeyStore.TrustedCertificateEntry(ca.certificate), PasswordProtection(null))
        }
        Files.newOutputStream(this.trustStoreFile).use {
            trustStore.store(it, null)
        }
    }

    protected fun getClientKeyStore(ca: X509Credentials, subject: X500Name) = KeyStore.getInstance("PKCS12").apply {
        val clientCert = CertificateUtils.createClientCertificate(ca, subject, 30)

        load(null, null)
        setEntry(CA_CERTIFICATE_ENTRY, KeyStore.TrustedCertificateEntry(ca.certificate), PasswordProtection(null))
        setEntry(
            CLIENT_CERTIFICATE_ENTRY,
            KeyStore.PrivateKeyEntry(clientCert.keyPair().private, arrayOf(clientCert.certificate(), ca.certificate)),
            PasswordProtection(PASSWORD.toCharArray())
        )
    }

    protected fun getHttpClient(clientKeyStore: KeyStore?): HttpClient {
        val kmf = clientKeyStore?.let {
            KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                init(it, PASSWORD.toCharArray())
            }
        }


        // Set up trust manager factory with the truststore
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(trustStore)

        // Create SSL context with the key and trust managers
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(kmf?.keyManagers ?: emptyArray(), tmf.trustManagers, null)
        }
        return HttpClient.newBuilder().sslContext(sslContext).build()
    }

    override fun setUp() {
        this.clientKeyStoreFile = testDir.resolve("client-keystore.p12")
        this.serverKeyStoreFile = testDir.resolve("server-keystore.p12")
        this.trustStoreFile = testDir.resolve("truststore.p12")
        this.cacheDir = testDir.resolve("cache")
        createKeyStoreAndTrustStore()
        cfg = Configuration(
            "127.0.0.1",
            NetworkUtils.getFreePort(),
            serverPath,
            users.asSequence().map { it.name to it }.toMap(),
            sequenceOf(writersGroup, readersGroup).map { it.name to it }.toMap(),
            FileSystemCacheConfiguration(this.cacheDir,
                maxAge = Duration.ofSeconds(3600 * 24),
                compressionEnabled = true,
                compressionLevel = Deflater.DEFAULT_COMPRESSION,
                digestAlgorithm = "MD5"
            ),
            Configuration.ClientCertificateAuthentication(
                Configuration.TlsCertificateExtractor("CN", "(.*)"),
                null
            ),
            Configuration.Tls(
                Configuration.KeyStore(this.serverKeyStoreFile, null, SERVER_CERTIFICATE_ENTRY, PASSWORD),
                Configuration.TrustStore(this.trustStoreFile, null, false),
                true
            ),
            false,
            0x10000,
            100
        )
        Xml.write(Serializer.serialize(cfg), System.out)
    }

    override fun tearDown() {
    }

    protected fun newRequestBuilder(key: String) = HttpRequest.newBuilder()
        .uri(URI.create("https://${cfg.host}:${cfg.port}/${serverPath ?: ""}/$key"))

    private fun buildAuthorizationHeader(user: Configuration.User, password: String): String {
        val b64 = Base64.getEncoder().encode("${user.name}:${password}".toByteArray(Charsets.UTF_8)).let {
            String(it, StandardCharsets.UTF_8)
        }
        return "Basic $b64"
    }

    protected fun newEntry(random: Random): Pair<String, ByteArray> {
        val key = ByteArray(0x10).let {
            random.nextBytes(it)
            Base64.getUrlEncoder().encodeToString(it)
        }
        val value = ByteArray(0x1000).also {
            random.nextBytes(it)
        }
        return key to value
    }
}