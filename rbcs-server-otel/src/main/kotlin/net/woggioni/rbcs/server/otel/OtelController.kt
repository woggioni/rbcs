package net.woggioni.rbcs.server.otel

import io.netty.channel.ChannelHandler
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender
import io.opentelemetry.instrumentation.netty.v4_1.NettyServerTelemetry
import io.opentelemetry.instrumentation.runtimetelemetry.RuntimeTelemetry
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk
import net.woggioni.rbcs.api.RedisSpan
import net.woggioni.rbcs.api.TelemetryController
import net.woggioni.rbcs.common.createLogger
import net.woggioni.rbcs.common.info

class OtelController : TelemetryController {

    private val log = createLogger<OtelController>()

    private val tracer by lazy {
        GlobalOpenTelemetry.getTracer("net.woggioni.rbcs.server.redis", "0.5.0")
    }

    override fun initialize() {
        log.info { "Initializing OpenTelemetry SDK with auto-configuration" }

        val sdk = AutoConfiguredOpenTelemetrySdk.builder()
            .setResultAsGlobal()
            .build()
            .openTelemetrySdk
        RuntimeTelemetry.create(sdk)

        runCatching {
            OpenTelemetryAppender.install(sdk)
            log.info { "OpenTelemetry logback appender installed" }
        }.onFailure { ex ->
            val msg = ex.localizedMessage ?: ex.javaClass.name
            log.info { "Failed to install OpenTelemetry logback appender: $msg" }
        }
        log.info { "OpenTelemetry SDK initialized successfully" }
    }

    override fun createHandler(): ChannelHandler {
        return NettyServerTelemetry.create(GlobalOpenTelemetry.get()).createCombinedHandler()
    }

    override fun startRedisSpan(command: String, key: String): RedisSpan? {
        val span = tracer.spanBuilder(command)
            .setSpanKind(SpanKind.CLIENT)
            .setAttribute("db.system", "redis")
            .setAttribute("db.operation", command)
            .startSpan()
        return RedisOtelSpan(span)
    }

    override fun endRedisSpan(span: RedisSpan?) {
        (span as? RedisOtelSpan)?.delegate?.end()
    }

    override fun endRedisSpan(span: RedisSpan?, error: Throwable) {
        val s = (span as? RedisOtelSpan)?.delegate ?: return
        s.recordException(error)
        s.setStatus(StatusCode.ERROR)
        s.end()
    }
}
