import net.woggioni.gbcs.api.CacheProvider;
import net.woggioni.gbcs.url.ClasspathUrlStreamHandlerFactoryProvider;
import net.woggioni.gbcs.cache.FileSystemCacheProvider;

open module net.woggioni.gbcs {
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

    provides java.net.URLStreamHandlerFactory with ClasspathUrlStreamHandlerFactoryProvider;
    uses java.net.URLStreamHandlerFactory;
    uses CacheProvider;

    provides CacheProvider with FileSystemCacheProvider;
}