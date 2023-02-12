package net.woggioni.gbcs

import java.security.KeyStore
import java.security.cert.CertPathValidator
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.PKIXRevocationChecker
import java.security.cert.X509Certificate
import java.util.EnumSet
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.handler.ssl.SslHandler
import io.netty.handler.ssl.SslHandshakeCompletionEvent
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager


class ClientCertificateValidator private constructor(private val sslHandler : SslHandler, private val x509TrustManager: X509TrustManager) : ChannelInboundHandlerAdapter() {
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

        fun of(sslHandler : SslHandler, trustStore : KeyStore?) : ClientCertificateValidator {
            val certificateFactory = CertificateFactory.getInstance("X.509")

            val validator = CertPathValidator.getInstance("PKIX").apply {
                val rc = revocationChecker as PKIXRevocationChecker
                rc.options = EnumSet.of(
                    PKIXRevocationChecker.Option.NO_FALLBACK,
                    PKIXRevocationChecker.Option.SOFT_FAIL,
                    PKIXRevocationChecker.Option.PREFER_CRLS)
            }

            val manager = if(trustStore != null) {
                val params = PKIXParameters(trustStore)
                object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {
                        val clientCertificateChain = certificateFactory.generateCertPath(chain.toList())
                        validator.validate(clientCertificateChain, params)
                    }

                    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
                        throw NotImplementedError()
                    }

                    override fun getAcceptedIssuers(): Array<X509Certificate> {
                        throw NotImplementedError()
                    }
                }
            } else {
                val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                trustManagerFactory.trustManagers.asSequence().filter { it is X509TrustManager }.single() as X509TrustManager
            }
            return ClientCertificateValidator(sslHandler, manager)
        }
    }
}