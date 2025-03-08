package net.woggioni.rbcs.client

import net.woggioni.rbcs.client.impl.Parser
import net.woggioni.rbcs.common.Xml
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.time.Duration

data class Configuration(
    val profiles: Map<String, Profile>
) {
    sealed class Authentication {
        data class TlsClientAuthenticationCredentials(
            val key: PrivateKey,
            val certificateChain: Array<X509Certificate>
        ) : Authentication()

        data class BasicAuthenticationCredentials(val username: String, val password: String) : Authentication()
    }

    class TrustStore (
        var file: Path?,
        var password: String?,
        var checkCertificateStatus: Boolean = false,
        var verifyServerCertificate: Boolean = true,
    )

    class RetryPolicy(
        val maxAttempts: Int,
        val initialDelayMillis: Long,
        val exp: Double
    )

    class Connection(
        val readIdleTimeout: Duration,
        val writeIdleTimeout: Duration,
        val idleTimeout: Duration,
        val requestPipelining : Boolean,
    )

    data class Profile(
        val serverURI: URI,
        val connection: Connection,
        val authentication: Authentication?,
        val connectionTimeout: Duration?,
        val maxConnections: Int,
        val compressionEnabled: Boolean,
        val retryPolicy: RetryPolicy?,
        val tlsTruststore : TrustStore?
    )

    companion object {
        fun parse(path: Path): Configuration {
            return Files.newInputStream(path).use {
                Xml.parseXml(path.toUri().toURL(), it)
            }.let(Parser::parse)
        }
    }
}