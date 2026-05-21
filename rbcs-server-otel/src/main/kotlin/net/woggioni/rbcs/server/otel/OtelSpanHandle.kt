package net.woggioni.rbcs.server.otel

import io.opentelemetry.api.trace.Span
import net.woggioni.rbcs.api.SpanHandle

internal class OtelSpanHandle(
    val delegate: Span,
) : SpanHandle {

    override fun setAttribute(key: String, value: String) {
        delegate.setAttribute(key, value)
    }

    override fun setAttribute(key: String, value: Long) {
        delegate.setAttribute(key, value)
    }

    override fun setAttribute(key: String, value: Boolean) {
        delegate.setAttribute(key, value)
    }
}
