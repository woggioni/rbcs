import java.net.URLStreamHandlerFactory;

module net.woggioni.gbcs {
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
    opens net.woggioni.gbcs to net.woggioni.envelope;
    uses java.net.URLStreamHandlerFactory;
}