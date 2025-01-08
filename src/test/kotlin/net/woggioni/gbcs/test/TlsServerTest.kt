package net.woggioni.gbcs.test

import io.netty.handler.codec.http.HttpResponseStatus
import net.woggioni.gbcs.api.Configuration
import net.woggioni.gbcs.api.Role
import net.woggioni.gbcs.base.Xml
import net.woggioni.gbcs.cache.FileSystemCacheConfiguration
import net.woggioni.gbcs.configuration.Serializer
import net.woggioni.gbcs.utils.CertificateUtils
import net.woggioni.gbcs.utils.CertificateUtils.X509Credentials
import org.bouncycastle.asn1.x500.X500Name
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
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


class TlsServerTest : AbstractServerTest() {

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
    private lateinit var ca: X509Credentials

    private val readersGroup = Configuration.Group("readers", setOf(Role.Reader))
    private val writersGroup = Configuration.Group("writers", setOf(Role.Writer))
    private val random = Random(101325)
    private val keyValuePair = newEntry(random)
    private val serverPath : String? = null

    private val users = listOf(
        Configuration.User("user1", null, setOf(readersGroup)),
        Configuration.User("user2", null, setOf(writersGroup)),
        Configuration.User("user3", null, setOf(readersGroup, writersGroup))
    )

    fun createKeyStoreAndTrustStore() {
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

    fun getClientKeyStore(ca: X509Credentials, subject: X500Name) = KeyStore.getInstance("PKCS12").apply {
        val clientCert = CertificateUtils.createClientCertificate(ca, subject, 30)

        load(null, null)
        setEntry(CA_CERTIFICATE_ENTRY, KeyStore.TrustedCertificateEntry(ca.certificate), PasswordProtection(null))
        setEntry(
            CLIENT_CERTIFICATE_ENTRY,
            KeyStore.PrivateKeyEntry(clientCert.keyPair().private, arrayOf(clientCert.certificate(), ca.certificate)),
            PasswordProtection(PASSWORD.toCharArray())
        )
    }

    fun getHttpClient(clientKeyStore: KeyStore?): HttpClient {
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
            ServerSocket(0).localPort + 1,
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
        )
        Xml.write(Serializer.serialize(cfg), System.out)
    }

    override fun tearDown() {
    }

    fun newRequestBuilder(key: String) = HttpRequest.newBuilder()
        .uri(URI.create("https://${cfg.host}:${cfg.port}/${serverPath ?: ""}/$key"))

    fun buildAuthorizationHeader(user: Configuration.User, password: String): String {
        val b64 = Base64.getEncoder().encode("${user.name}:${password}".toByteArray(Charsets.UTF_8)).let {
            String(it, StandardCharsets.UTF_8)
        }
        return "Basic $b64"
    }

    fun newEntry(random: Random): Pair<String, ByteArray> {
        val key = ByteArray(0x10).let {
            random.nextBytes(it)
            Base64.getUrlEncoder().encodeToString(it)
        }
        val value = ByteArray(0x1000).also {
            random.nextBytes(it)
        }
        return key to value
    }

    @Test
    @Order(1)
    fun putWithNoClientCertificate() {
        val client: HttpClient = getHttpClient(null)
        val (key, value) = keyValuePair

        val requestBuilder = newRequestBuilder(key)
            .header("Content-Type", "application/octet-stream")
            .PUT(HttpRequest.BodyPublishers.ofByteArray(value))

        val response: HttpResponse<String> = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
        Assertions.assertEquals(HttpResponseStatus.UNAUTHORIZED.code(), response.statusCode())
    }

    @Test
    @Order(2)
    fun putAsAReaderUser() {
        val (key, value) = keyValuePair
        val user = cfg.users.values.find {
            Role.Reader in it.roles && Role.Writer !in it.roles
        } ?: throw RuntimeException("Reader user not found")
        val client: HttpClient = getHttpClient(getClientKeyStore(ca, X500Name("CN=${user.name}")))
        val requestBuilder = newRequestBuilder(key)
            .header("Content-Type", "application/octet-stream")
            .PUT(HttpRequest.BodyPublishers.ofByteArray(value))

        val response: HttpResponse<String> = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
        Assertions.assertEquals(HttpResponseStatus.FORBIDDEN.code(), response.statusCode())
    }

    @Test
    @Order(3)
    fun getAsAWriterUser() {

        val (key, _) = keyValuePair
        val user = cfg.users.values.find {
            Role.Writer in it.roles
        } ?: throw RuntimeException("Reader user not found")
        val client: HttpClient = getHttpClient(getClientKeyStore(ca, X500Name("CN=${user.name}")))

        val requestBuilder = newRequestBuilder(key)
            .header("Authorization", buildAuthorizationHeader(user, PASSWORD))
            .GET()

        val response: HttpResponse<String> = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
        Assertions.assertEquals(HttpResponseStatus.FORBIDDEN.code(), response.statusCode())
    }

    @Test
    @Order(4)
    fun putAsAWriterUser() {

        val (key, value) = keyValuePair
        val user = cfg.users.values.find {
            Role.Writer in it.roles
        } ?: throw RuntimeException("Reader user not found")
        val client: HttpClient = getHttpClient(getClientKeyStore(ca, X500Name("CN=${user.name}")))

        val requestBuilder = newRequestBuilder(key)
            .header("Content-Type", "application/octet-stream")
            .header("Authorization", buildAuthorizationHeader(user, PASSWORD))
            .PUT(HttpRequest.BodyPublishers.ofByteArray(value))

        val response: HttpResponse<String> = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
        Assertions.assertEquals(HttpResponseStatus.CREATED.code(), response.statusCode())
    }

    @Test
    @Order(5)
    fun getAsAReaderUser() {
        val (key, value) = keyValuePair
        val user = cfg.users.values.find {
            Role.Reader in it.roles
        } ?: throw RuntimeException("Reader user not found")
        val client: HttpClient = getHttpClient(getClientKeyStore(ca, X500Name("CN=${user.name}")))

        val requestBuilder = newRequestBuilder(key)
            .header("Authorization", buildAuthorizationHeader(user, PASSWORD))
            .GET()

        val response: HttpResponse<ByteArray> =
            client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofByteArray())
        Assertions.assertEquals(HttpResponseStatus.OK.code(), response.statusCode())
        Assertions.assertArrayEquals(value, response.body())
    }

    @Test
    @Order(6)
    fun getMissingKeyAsAReaderUser() {
        val (key, _) = newEntry(random)
        val user = cfg.users.values.find {
            Role.Reader in it.roles
        } ?: throw RuntimeException("Reader user not found")
        val client: HttpClient = getHttpClient(getClientKeyStore(ca, X500Name("CN=${user.name}")))

        val requestBuilder = newRequestBuilder(key)
            .header("Authorization", buildAuthorizationHeader(user, PASSWORD))
            .GET()

        val response: HttpResponse<ByteArray> =
            client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofByteArray())
        Assertions.assertEquals(HttpResponseStatus.NOT_FOUND.code(), response.statusCode())
    }
}