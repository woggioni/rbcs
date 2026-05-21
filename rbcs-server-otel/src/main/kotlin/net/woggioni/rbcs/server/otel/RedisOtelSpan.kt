package net.woggioni.rbcs.server.otel

import io.opentelemetry.api.trace.Span
import net.woggioni.rbcs.api.RedisSpan

internal class RedisOtelSpan(
    val delegate: Span,
) : RedisSpan {

    override fun setAttribute(key: String, value: String) {
        delegate.setAttribute(key, value)
    }

    override fun setAttribute(key: String, value: Long) {
        delegate.setAttribute(key, value)
    }
}
