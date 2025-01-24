import net.woggioni.gbcs.api.CacheProvider;
import net.woggioni.gbcs.server.cache.FileSystemCacheProvider;
import net.woggioni.gbcs.server.cache.InMemoryCacheProvider;

module net.woggioni.gbcs.server {
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
    requires net.woggioni.gbcs.common;
    requires net.woggioni.gbcs.api;

    exports net.woggioni.gbcs.server;

    opens net.woggioni.gbcs.server;
    opens net.woggioni.gbcs.server.schema;

    uses CacheProvider;
    provides CacheProvider with FileSystemCacheProvider, InMemoryCacheProvider;
}