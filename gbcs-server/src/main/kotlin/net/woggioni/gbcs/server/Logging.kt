package net.woggioni.gbcs.server

import io.netty.channel.ChannelHandlerContext
import org.slf4j.Logger
import java.net.InetSocketAddress

inline fun Logger.trace(ctx : ChannelHandlerContext, messageBuilder : () -> String) {
    log(this, ctx, { isTraceEnabled }, { trace(it) } , messageBuilder)
}
inline fun Logger.debug(ctx : ChannelHandlerContext, messageBuilder : () -> String) {
    log(this, ctx, { isDebugEnabled }, { debug(it) } , messageBuilder)
}
inline fun Logger.info(ctx : ChannelHandlerContext, messageBuilder : () -> String) {
    log(this, ctx, { isInfoEnabled }, { info(it) } , messageBuilder)
}
inline fun Logger.warn(ctx : ChannelHandlerContext, messageBuilder : () -> String) {
    log(this, ctx, { isWarnEnabled }, { warn(it) } , messageBuilder)
}
inline fun Logger.error(ctx : ChannelHandlerContext, messageBuilder : () -> String) {
    log(this, ctx, { isErrorEnabled }, { error(it) } , messageBuilder)
}

inline fun log(log : Logger, ctx : ChannelHandlerContext,
                        filter : Logger.() -> Boolean,
                        loggerMethod : Logger.(String) -> Unit, messageBuilder : () -> String) {
    if(log.filter()) {
        val clientAddress = (ctx.channel().remoteAddress() as InetSocketAddress).address.hostAddress
        log.loggerMethod(clientAddress + " - " + messageBuilder())
    }
}