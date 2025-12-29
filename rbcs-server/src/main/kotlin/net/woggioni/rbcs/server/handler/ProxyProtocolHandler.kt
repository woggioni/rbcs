package net.woggioni.rbcs.server.handler

import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.haproxy.HAProxyMessage
import java.net.InetAddress
import java.net.InetSocketAddress
import net.woggioni.rbcs.common.Cidr
import net.woggioni.rbcs.common.createLogger
import net.woggioni.rbcs.common.trace
import net.woggioni.rbcs.server.RemoteBuildCacheServer


class ProxyProtocolHandler(private val trustedProxyIPs : List<Cidr>) : SimpleChannelInboundHandler<HAProxyMessage>() {

    companion object {
        private val log = createLogger<ProxyProtocolHandler>()
    }

    override fun channelRead0(
        ctx: ChannelHandlerContext,
        msg: HAProxyMessage
    ) {
        val sourceAddress = ctx.channel().remoteAddress()
        if (sourceAddress is InetSocketAddress &&
            trustedProxyIPs.isEmpty() ||
            trustedProxyIPs.any { it.contains((sourceAddress as InetSocketAddress).address) }) {
            val proxiedClientAddress = InetSocketAddress(
                InetAddress.ofLiteral(msg.sourceAddress()),
                msg.sourcePort()
            )
            if(log.isTraceEnabled) {
                log.trace {
                    "Received proxied request from $sourceAddress forwarded for $proxiedClientAddress"
                }
            }
            ctx.channel().attr(RemoteBuildCacheServer.clientIp).set(proxiedClientAddress)
        }
    }
}