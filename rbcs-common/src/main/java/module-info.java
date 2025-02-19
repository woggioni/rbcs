module net.woggioni.rbcs.common {
    requires java.xml;
    requires java.logging;
    requires org.slf4j;
    requires kotlin.stdlib;
    requires net.woggioni.jwo;
    requires io.netty.buffer;
    requires io.netty.transport;

    provides java.net.spi.URLStreamHandlerProvider with net.woggioni.rbcs.common.RbcsUrlStreamHandlerFactory;
    exports net.woggioni.rbcs.common;
}