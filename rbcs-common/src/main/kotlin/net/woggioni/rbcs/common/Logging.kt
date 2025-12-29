package net.woggioni.rbcs.common

import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.LogManager
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.slf4j.event.Level
import org.slf4j.spi.LoggingEventBuilder

inline fun <reified T> T.contextLogger() = LoggerFactory.getLogger(T::class.java)
inline fun <reified T> createLogger() = LoggerFactory.getLogger(T::class.java)

inline fun Logger.traceParam(messageBuilder: () -> Pair<String, Array<Any>>) {
    if (isTraceEnabled) {
        val (format, params) = messageBuilder()
        trace(format, params)
    }
}

inline fun Logger.debugParam(messageBuilder: () -> Pair<String, Array<Any>>) {
    if (isDebugEnabled) {
        val (format, params) = messageBuilder()
        info(format, params)
    }
}

inline fun Logger.infoParam(messageBuilder: () -> Pair<String, Array<Any>>) {
    if (isInfoEnabled) {
        val (format, params) = messageBuilder()
        info(format, params)
    }
}

inline fun Logger.warnParam(messageBuilder: () -> Pair<String, Array<Any>>) {
    if (isWarnEnabled) {
        val (format, params) = messageBuilder()
        warn(format, params)
    }
}

inline fun Logger.errorParam(messageBuilder: () -> Pair<String, Array<Any>>) {
    if (isErrorEnabled) {
        val (format, params) = messageBuilder()
        error(format, params)
    }
}


inline fun log(
    log: Logger,
    filter: Logger.() -> Boolean,
    loggerMethod: Logger.(String) -> Unit, messageBuilder: () -> String
) {
    if (log.filter()) {
        log.loggerMethod(messageBuilder())
    }
}

fun withMDC(params: Array<Pair<String, String>>, cb: () -> Unit) {
    object : AutoCloseable {
        override fun close() {
            for ((key, _) in params) MDC.remove(key)
        }
    }.use {
        for ((key, value) in params) MDC.put(key, value)
        cb()
    }
}

inline fun Logger.log(level: Level, channel: Channel, crossinline messageBuilder: (LoggingEventBuilder) -> Unit ) {
    if (isEnabledForLevel(level)) {
        val params = arrayOf<Pair<String, String>>(
            "channel-id-short" to channel.id().asShortText(),
            "channel-id-long" to channel.id().asLongText(),
            "remote-address" to channel.remoteAddress().toString(),
            "local-address" to channel.localAddress().toString(),
        )
        withMDC(params) {
            val builder = makeLoggingEventBuilder(level)
            messageBuilder(builder)
            builder.log()
        }
    }
}
inline fun Logger.log(level: Level, channel: Channel, crossinline messageBuilder: () -> String) {
    log(level, channel) { builder ->
        builder.setMessage(messageBuilder())
    }
}

inline fun Logger.trace(ch: Channel, crossinline messageBuilder: () -> String) {
    log(Level.TRACE, ch, messageBuilder)
}

inline fun Logger.debug(ch: Channel, crossinline messageBuilder: () -> String) {
    log(Level.DEBUG, ch, messageBuilder)
}

inline fun Logger.info(ch: Channel, crossinline messageBuilder: () -> String) {
    log(Level.INFO, ch, messageBuilder)
}

inline fun Logger.warn(ch: Channel, crossinline messageBuilder: () -> String) {
    log(Level.WARN, ch, messageBuilder)
}

inline fun Logger.error(ch: Channel, crossinline messageBuilder: () -> String) {
    log(Level.ERROR, ch, messageBuilder)
}

inline fun Logger.trace(ctx: ChannelHandlerContext, crossinline messageBuilder: () -> String) {
    log(Level.TRACE, ctx.channel(), messageBuilder)
}

inline fun Logger.debug(ctx: ChannelHandlerContext, crossinline messageBuilder: () -> String) {
    log(Level.DEBUG, ctx.channel(), messageBuilder)
}

inline fun Logger.info(ctx: ChannelHandlerContext, crossinline messageBuilder: () -> String) {
    log(Level.INFO, ctx.channel(), messageBuilder)
}

inline fun Logger.warn(ctx: ChannelHandlerContext, crossinline messageBuilder: () -> String) {
    log(Level.WARN, ctx.channel(), messageBuilder)
}

inline fun Logger.error(ctx: ChannelHandlerContext, crossinline messageBuilder: () -> String) {
    log(Level.ERROR, ctx.channel(), messageBuilder)
}


inline fun Logger.log(level: Level, messageBuilder: () -> String) {
    if (isEnabledForLevel(level)) {
        makeLoggingEventBuilder(level).log(messageBuilder())
    }
}

inline fun Logger.trace(messageBuilder: () -> String) {
    if (isTraceEnabled) {
        trace(messageBuilder())
    }
}

inline fun Logger.debug(messageBuilder: () -> String) {
    if (isDebugEnabled) {
        debug(messageBuilder())
    }
}

inline fun Logger.info(messageBuilder: () -> String) {
    if (isInfoEnabled) {
        info(messageBuilder())
    }
}

inline fun Logger.warn(messageBuilder: () -> String) {
    if (isWarnEnabled) {
        warn(messageBuilder())
    }
}

inline fun Logger.error(messageBuilder: () -> String) {
    if (isErrorEnabled) {
        error(messageBuilder())
    }
}


class LoggingConfig {

    init {
        val logManager = LogManager.getLogManager()
        System.getProperty("log.config.source")?.let withSource@{ source ->
            val urls = LoggingConfig::class.java.classLoader.getResources(source)
            while (urls.hasMoreElements()) {
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