module net.woggioni.gbcs {
    requires kotlin.stdlib;
    requires io.netty.buffer;
    requires io.netty.transport;
    requires io.netty.codec.http;
    requires io.netty.common;
    requires io.netty.handler;
    requires io.netty.codec;
    requires java.logging;
    requires org.slf4j;
    exports net.woggioni.gbcs;
}