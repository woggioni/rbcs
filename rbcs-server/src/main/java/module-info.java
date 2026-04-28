import net.woggioni.rbcs.api.CacheProvider;
import net.woggioni.rbcs.server.cache.FileSystemCacheProvider;
import net.woggioni.rbcs.server.cache.InMemoryCacheProvider;

module net.woggioni.rbcs.server {
    requires java.xml;
    requires java.naming;
    requires kotlin.stdlib;
    requires io.netty.codec.http;
    requires io.netty.handler;
    requires net.woggioni.jwo;
    requires net.woggioni.rbcs.common;
    requires net.woggioni.rbcs.api;
    requires io.netty.codec.compression;
    requires io.netty.transport;
    requires io.netty.buffer;
    requires io.netty.common;
    requires io.netty.codec;
    requires io.netty.codec.haproxy;
    requires static io.opentelemetry.api;
    requires static io.opentelemetry.instrumentation.netty_4_1;
    requires static io.opentelemetry.sdk.autoconfigure;
    requires static io.opentelemetry.instrumentation.logback_appender_1_0;
    requires static io.opentelemetry.extension.trace.propagation;
    requires org.slf4j;

    exports net.woggioni.rbcs.server;

    opens net.woggioni.rbcs.server;
    opens net.woggioni.rbcs.server.schema;


    uses CacheProvider;
    provides CacheProvider with FileSystemCacheProvider, InMemoryCacheProvider;
}