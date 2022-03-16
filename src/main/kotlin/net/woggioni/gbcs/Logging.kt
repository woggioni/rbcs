package net.woggioni.gbcs

import io.netty.channel.ChannelHandlerContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.LogManager




inline fun <reified T> T.contextLogger() = LoggerFactory.getLogger(T::class.java)

inline fun Logger.traceParam(messageBuilder : () -> Pair<String, Array<Any>>) {
    if(isTraceEnabled) {
        val (format, params) = messageBuilder()
        trace(format, params)
    }
}

inline fun Logger.debugParam(messageBuilder : () -> Pair<String, Array<Any>>) {
    if(isDebugEnabled) {
        val (format, params) = messageBuilder()
        info(format, params)
    }
}

inline fun Logger.infoParam(messageBuilder : () -> Pair<String, Array<Any>>) {
    if(isInfoEnabled) {
        val (format, params) = messageBuilder()
        info(format, params)
    }
}

inline fun Logger.warnParam(messageBuilder : () -> Pair<String, Array<Any>>) {
    if(isWarnEnabled) {
        val (format, params) = messageBuilder()
        warn(format, params)
    }
}

inline fun Logger.errorParam(messageBuilder : () -> Pair<String, Array<Any>>) {
    if(isErrorEnabled) {
        val (format, params) = messageBuilder()
        error(format, params)
    }
}


inline fun log(log : Logger,
               filter : Logger.() -> Boolean,
               loggerMethod : Logger.(String) -> Unit, messageBuilder : () -> String) {
    if(log.filter()) {
        log.loggerMethod(messageBuilder())
    }
}

inline fun Logger.trace(messageBuilder : () -> String) {
    if(isTraceEnabled) {
        trace(messageBuilder())
    }
}

inline fun Logger.debug(messageBuilder : () -> String) {
    if(isDebugEnabled) {
        debug(messageBuilder())
    }
}

inline fun Logger.info(messageBuilder : () -> String) {
    if(isInfoEnabled) {
        info(messageBuilder())
    }
}

inline fun Logger.warn(messageBuilder : () -> String) {
    if(isWarnEnabled) {
        warn(messageBuilder())
    }
}

inline fun Logger.error(messageBuilder : () -> String) {
    if(isErrorEnabled) {
        error(messageBuilder())
    }
}

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


class LoggingConfig {

    init {
        val logManager = LogManager.getLogManager()
        System.getProperty("log.config.source")?.let withSource@ { source ->
            val urls = LoggingConfig::class.java.classLoader.getResources(source)
            while(urls.hasMoreElements()) {
                val url = urls.nextElement()
                url.openStream().use { inputStream ->
                    logManager.readConfiguration(inputStream)
                    return@withSource
                }
            }
            Path.of(source).takeIf(Files::exists)
                ?.let(Files::newInputStream)
                ?.use(logManager::readConfiguration)
        }
    }
}