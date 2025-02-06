import net.woggioni.rbcs.api.CacheProvider;
import net.woggioni.rbcs.server.cache.FileSystemCacheProvider;
import net.woggioni.rbcs.server.cache.InMemoryCacheProvider;

module net.woggioni.rbcs.server {
    requires java.sql;
    requires java.xml;
    requires java.logging;
    requires java.naming;
    requires kotlin.stdlib;
    requires io.netty.buffer;
    requires io.netty.transport;
    requires io.netty.codec.http;
    requires io.netty.common;
    requires io.netty.handler;
    requires io.netty.codec;
    requires org.slf4j;
    requires net.woggioni.jwo;
    requires net.woggioni.rbcs.common;
    requires net.woggioni.rbcs.api;

    exports net.woggioni.rbcs.server;

    opens net.woggioni.rbcs.server;
    opens net.woggioni.rbcs.server.schema;

    uses CacheProvider;
    provides CacheProvider with FileSystemCacheProvider, InMemoryCacheProvider;
}