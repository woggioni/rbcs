import net.woggioni.gbcs.api.CacheProvider;
import net.woggioni.gbcs.url.ClasspathUrlStreamHandlerFactoryProvider;
import net.woggioni.gbcs.cache.FileSystemCacheProvider;

open module net.woggioni.gbcs {
//    exports net.woggioni.gbcs.cache to net.woggioni.gbcs.test;
//    exports net.woggioni.gbcs.configuration to net.woggioni.gbcs.test;
//    exports net.woggioni.gbcs.url to net.woggioni.gbcs.test;
//    exports net.woggioni.gbcs to net.woggioni.gbcs.test;
//    opens net.woggioni.gbcs.schema to net.woggioni.gbcs.test;

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

//    exports net.woggioni.gbcs;
//    exports net.woggioni.gbcs.url;
//    opens net.woggioni.gbcs to net.woggioni.envelope;
    provides java.net.URLStreamHandlerFactory with ClasspathUrlStreamHandlerFactoryProvider;
    uses java.net.URLStreamHandlerFactory;
//    uses net.woggioni.gbcs.api.Cache;
    uses CacheProvider;

    provides CacheProvider with FileSystemCacheProvider;
}