module net.woggioni.rbcs.server.otel {
    requires net.woggioni.rbcs.common;
    requires kotlin.stdlib;
    requires io.netty.transport;
    requires io.netty.common;
    requires io.netty.buffer;
    requires org.slf4j;

    requires ch.qos.logback.core;
    requires ch.qos.logback.classic;
    requires io.opentelemetry.api;
    requires io.opentelemetry.sdk.autoconfigure;
    requires io.opentelemetry.instrumentation.runtime_telemetry;
    requires io.opentelemetry.instrumentation.netty_4_1;
    requires io.opentelemetry.instrumentation.logback_appender_1_0;
    requires io.opentelemetry.extension.trace.propagation;
    requires net.woggioni.rbcs.api;

    provides net.woggioni.rbcs.api.TelemetryController with net.woggioni.rbcs.server.otel.OtelController;
}
