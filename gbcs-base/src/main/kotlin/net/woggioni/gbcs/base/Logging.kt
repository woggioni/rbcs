package net.woggioni.gbcs.base

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
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

inline fun Logger.log(level : Level, messageBuilder : () -> String) {
    if(isEnabledForLevel(level)) {
        makeLoggingEventBuilder(level).log(messageBuilder())
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