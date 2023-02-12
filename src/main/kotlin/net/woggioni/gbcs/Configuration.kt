package net.woggioni.gbcs

import java.nio.file.Path
import java.nio.file.Paths
import org.w3c.dom.Document
import net.woggioni.gbcs.Xml.asIterable
import org.w3c.dom.Element

data class HostAndPort(val host: String, val port : Integer) {
    override fun toString() = "$host:$port"
}

data class TlsConfiguration(val keyStore: KeyStore?, val trustStore: TrustStore?, val verifyClients : Boolean)
data class KeyStore(
    val file : Path,
    val password : String?,
    val keyAlias: String,
    val keyPassword : String?
)

data class TrustStore(
    val file : Path,
    val password : String?,
)

data class Configuration(
    val cacheFolder : Path,
    val host : String,
    val port : Int,
    val users : Map<String, Set<Role>>,
    val tlsConfiguration: TlsConfiguration?,
    val serverPath : String
) {
    companion object {
        fun parse(document : Element) : Configuration {

            var cacheFolder = Paths.get(System.getProperty("user.home")).resolve(".gbcs")
            var host : String = "127.0.0.1"
            var port : Int = 11080
            var users = emptyMap<String, Set<Role>>()
            var tlsConfiguration : TlsConfiguration? = null
            var serverPath = "/"

            for(child in document.asIterable()) {
                when(child.nodeName) {
                    "bind" -> {
                        host = child.getAttribute("host")
                        port = Integer.parseInt(child.getAttribute("port"))
                    }
                    "cache" -> {
                        cacheFolder = Paths.get(child.textContent)
                    }
                    "tls" -> {
                        val verifyClients = child.getAttribute("verify-clients")
                            .takeIf(String::isNotEmpty)
                            ?.let(String::toBoolean) ?: false
                        var keyStore : KeyStore? = null
                        var trustStore : TrustStore? = null
                        for(granChild in child.asIterable()) {
                            when(granChild.nodeName) {
                                "keystore" -> {
                                    val trustStoreFile = Paths.get(granChild.getAttribute("file"))
                                    val trustStorePassword = granChild.getAttribute("password")
                                        .takeIf(String::isNotEmpty)
                                    val keyAlias = granChild.getAttribute("server-key-alias")
                                    val keyPasswordPassword = granChild.getAttribute("server-key-password")
                                        .takeIf(String::isNotEmpty)
                                    keyStore = KeyStore(
                                        trustStoreFile,
                                        trustStorePassword,
                                        keyAlias,
                                        keyPasswordPassword
                                    )
                                }
                                "truststore" -> {
                                    val trustStoreFile = Paths.get(granChild.getAttribute("file"))
                                    val trustStorePassword = granChild.getAttribute("password")
                                        .takeIf(String::isNotEmpty)
                                    trustStore = TrustStore(
                                        trustStoreFile,
                                        trustStorePassword
                                    )
                                }
                            }
                        }
                        tlsConfiguration = TlsConfiguration(keyStore, trustStore, verifyClients)
                    }
                }

            }

            return Configuration(cacheFolder, host, port, users, tlsConfiguration, serverPath)
        }
    }
}
