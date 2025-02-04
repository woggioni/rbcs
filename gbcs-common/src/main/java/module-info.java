module net.woggioni.gbcs.common {
    requires java.xml;
    requires java.logging;
    requires org.slf4j;
    requires kotlin.stdlib;
    requires net.woggioni.jwo;
    requires io.netty.buffer;

    provides java.net.spi.URLStreamHandlerProvider with net.woggioni.gbcs.common.GbcsUrlStreamHandlerFactory;
    exports net.woggioni.gbcs.common;
}