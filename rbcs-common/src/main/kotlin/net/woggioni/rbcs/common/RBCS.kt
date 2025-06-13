package net.woggioni.rbcs.common

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertPathValidator
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.PKIXRevocationChecker
import java.security.cert.X509Certificate
import java.util.EnumSet
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import net.woggioni.jwo.JWO
import net.woggioni.jwo.Tuple2

object RBCS {
    fun String.toUrl() : URL = URL.of(URI(this), null)

    const val RBCS_NAMESPACE_URI: String = "urn:net.woggioni.rbcs.server"
    const val RBCS_PREFIX: String = "rbcs"
    const val XML_SCHEMA_NAMESPACE_URI = "http://www.w3.org/2001/XMLSchema-instance"

    fun ByteArray.toInt(index : Int = 0) : Long {
        if(index + 4 > size) throw IllegalArgumentException("Not enough bytes to decode a 32 bits integer")
        var value : Long = 0
        for (b in index until index + 4) {
            value = (value shl 8) + (get(b).toInt() and 0xFF)
        }
        return value
    }

    fun ByteArray.toLong(index : Int = 0) : Long {
        if(index + 8 > size) throw IllegalArgumentException("Not enough bytes to decode a 64 bits long integer")
        var value : Long = 0
        for (b in index until index + 8) {
            value = (value shl 8) + (get(b).toInt() and 0xFF)
        }
        return value
    }

    fun digest(
        data: ByteArray,
        md: MessageDigest
    ): ByteArray {
        md.update(data)
        return md.digest()
    }

    fun digestString(
        data: ByteArray,
        md: MessageDigest
    ): String {
        return JWO.bytesToHex(digest(data, md))
    }

    fun processCacheKey(key: String, digestAlgorithm: String?) = digestAlgorithm
        ?.let(MessageDigest::getInstance)
        ?.let { md ->
            digest(key.toByteArray(), md)
        } ?: key.toByteArray(Charsets.UTF_8)

    fun Long.toIntOrNull(): Int? {
        return if (this >= Int.MIN_VALUE && this <= Int.MAX_VALUE) {
            toInt()
        } else {
            null
        }
    }

    fun getFreePort(): Int {
        var count = 0
        while (count < 50) {
            try {
                ServerSocket(0, 50, InetAddress.getLocalHost()).use { serverSocket ->
                    val candidate = serverSocket.localPort
                    if (candidate > 0) {
                        return candidate
                    } else {
                        throw RuntimeException("Got invalid port number: $candidate")
                    }
                }
            } catch (ignored: IOException) {
                ++count
            }
        }
        throw RuntimeException("Error trying to find an open port")
    }

    fun loadKeystore(file: Path, password: String?): KeyStore {
        val ext = JWO.splitExtension(file)
            .map(Tuple2<String, String>::get_2)
            .orElseThrow {
                IllegalArgumentException(
                    "Keystore file '${file}' must have .jks, .p12, .pfx extension"
                )
            }
        val keystore = when (ext.substring(1).lowercase()) {
            "jks" -> KeyStore.getInstance("JKS")
            "p12", "pfx" -> KeyStore.getInstance("PKCS12")
            else -> throw IllegalArgumentException(
                "Keystore file '${file}' must have .jks, .p12, .pfx extension"
            )
        }
        Files.newInputStream(file).use {
            keystore.load(it, password?.let(String::toCharArray))
        }
        return keystore
    }

    fun getTrustManager(trustStore: KeyStore?, certificateRevocationEnabled: Boolean): X509TrustManager {
        return if (trustStore != null) {
            val certificateFactory = CertificateFactory.getInstance("X.509")
            val validator = CertPathValidator.getInstance("PKIX").apply {
                val rc = revocationChecker as PKIXRevocationChecker
                rc.options = EnumSet.of(
                    PKIXRevocationChecker.Option.NO_FALLBACK
                )
            }
            val params = PKIXParameters(trustStore).apply {
                isRevocationEnabled = certificateRevocationEnabled
            }
            object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {
                    val clientCertificateChain = certificateFactory.generateCertPath(chain.toList())
                    try {
                        validator.validate(clientCertificateChain, params)
                    } catch (ex: CertPathValidatorException) {
                        throw CertificateException(ex)
                    }
                }

                override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
                    throw NotImplementedError()
                }

                private val acceptedIssuers = trustStore.aliases().asSequence()
                    .filter(trustStore::isCertificateEntry)
                    .map(trustStore::getCertificate)
                    .map { it as X509Certificate }
                    .toList()
                    .toTypedArray()

                override fun getAcceptedIssuers() = acceptedIssuers
            }
        } else {
            val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            trustManagerFactory.trustManagers.asSequence().filter { it is X509TrustManager }
                .single() as X509TrustManager
        }
    }
}