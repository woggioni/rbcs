import net.woggioni.gbcs.url.ClasspathUrlStreamHandlerFactoryProvider;

module net.woggioni.gbcs {
    requires java.sql;
    requires java.xml;
    requires java.logging;
    requires kotlin.stdlib;
    requires io.netty.buffer;
    requires io.netty.transport;
    requires io.netty.codec.http;
    requires io.netty.common;
    requires io.netty.handler;
    requires io.netty.codec;
    requires org.slf4j;
    requires net.woggioni.jwo;

    exports net.woggioni.gbcs;
    exports net.woggioni.gbcs.url;
//    opens net.woggioni.gbcs to net.woggioni.envelope;
    provides java.net.URLStreamHandlerFactory with ClasspathUrlStreamHandlerFactoryProvider;
    uses java.net.URLStreamHandlerFactory;
}