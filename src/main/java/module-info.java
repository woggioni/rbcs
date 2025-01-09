import net.woggioni.gbcs.api.CacheProvider;
import net.woggioni.gbcs.cache.FileSystemCacheProvider;

module net.woggioni.gbcs {
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
    requires net.woggioni.gbcs.base;
    requires net.woggioni.gbcs.api;

    exports net.woggioni.gbcs;

    opens net.woggioni.gbcs;
    opens net.woggioni.gbcs.schema;

    uses CacheProvider;
    provides CacheProvider with FileSystemCacheProvider;
}