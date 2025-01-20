package net.woggioni.gbcs.server.auth

import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.handler.ssl.SslHandler
import io.netty.handler.ssl.SslHandshakeCompletionEvent
import java.security.KeyStore
import java.security.cert.CertPathValidator
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.PKIXRevocationChecker
import java.security.cert.X509Certificate
import java.util.EnumSet
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager


class ClientCertificateValidator private constructor(
    private val sslHandler: SslHandler,
    private val x509TrustManager: X509TrustManager
) : ChannelInboundHandlerAdapter() {
    override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
        if (evt is SslHandshakeCompletionEvent) {
            if (evt.isSuccess) {
                val session: SSLSession = sslHandler.engine().session
                val clientCertificateChain = session.peerCertificates as Array<X509Certificate>
                val authType: String = clientCertificateChain[0].publicKey.algorithm
                x509TrustManager.checkClientTrusted(clientCertificateChain, authType)
            } else {
                // Handle the failure, for example by closing the channel.
            }
        }
        super.userEventTriggered(ctx, evt)
    }

    companion object {
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

        fun of(
            sslHandler: SslHandler,
            trustStore: KeyStore?,
            certificateRevocationEnabled: Boolean
        ): ClientCertificateValidator {
            return ClientCertificateValidator(sslHandler, getTrustManager(trustStore, certificateRevocationEnabled))
        }
    }
}