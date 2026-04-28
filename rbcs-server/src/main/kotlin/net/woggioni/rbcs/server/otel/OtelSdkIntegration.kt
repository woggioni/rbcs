package net.woggioni.rbcs.server.otel

import io.netty.channel.ChannelHandler
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.instrumentation.netty.v4_1.NettyServerTelemetry
import net.woggioni.rbcs.common.createLogger
import net.woggioni.rbcs.common.info

object OtelSdkIntegration {

    private val log = createLogger<OtelSdkIntegration>()

    private val appenderAvailable: Boolean by lazy {
        runCatching {
            Class.forName("io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender")
        }.fold(
            onSuccess = { true },
            onFailure = {
                log.info { "OpenTelemetry logback appender not on classpath" }
                false
            },
        )
    }

    fun initialize() {
        log.info { "Initializing OpenTelemetry SDK with auto-configuration" }

        val sdk = io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk.builder()
            .setResultAsGlobal()
            .build()
            .openTelemetrySdk

        if (appenderAvailable) {
            runCatching {
                val clazz = Class.forName("io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender")
                clazz.getMethod("install", Class.forName("io.opentelemetry.api.OpenTelemetry"))
                    .invoke(null, sdk)
                log.info { "OpenTelemetry logback appender installed" }
            }.onFailure { ex ->
                val msg = ex.localizedMessage ?: ex.javaClass.name
                log.info { "Failed to install OpenTelemetry logback appender: $msg" }
            }
        }

        log.info { "OpenTelemetry SDK initialized successfully" }
    }

    fun createHandler(): ChannelHandler {
        return NettyServerTelemetry.create(GlobalOpenTelemetry.get()).createCombinedHandler()
    }
}
