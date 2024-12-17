package net.woggioni.gbcs

import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.CertPathValidator
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import org.junit.jupiter.api.Test

class CertificateValidationTest {

    @Test
    fun test() {
        val keystore = KeyStore.getInstance("PKCS12")
        val keystorePath = Path.of("/home/woggioni/ssl/woggioni@f6aa5663ef26.pfx")
        Files.newInputStream(keystorePath).use {
            keystore.load(it, System.getenv("KEYPASS").toCharArray())
        }
        val pkix = CertPathValidator.getInstance("PKIX")
        val trustStore = KeyStore.getInstance("PKCS12")
        val trustStorePath = Path.of("/home/woggioni/ssl/truststore.pfx")

        Files.newInputStream(trustStorePath).use {
            trustStore.load(it, "123456".toCharArray())
        }

        val certificateFactory = CertificateFactory.getInstance("X.509")
        val cert = keystore.getCertificateChain("woggioni@f6aa5663ef26").toList()
            .let(certificateFactory::generateCertPath)
        val params = PKIXParameters(trustStore)
        params.isRevocationEnabled = false
        pkix.validate(cert, params)
    }
}