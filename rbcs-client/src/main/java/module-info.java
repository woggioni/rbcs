module net.woggioni.rbcs.client {
    requires io.netty.handler;
    requires io.netty.codec.http;
    requires io.netty.transport;
    requires kotlin.stdlib;
    requires io.netty.common;
    requires io.netty.buffer;
    requires java.xml;
    requires net.woggioni.rbcs.common;
    requires net.woggioni.rbcs.api;
    requires io.netty.codec;
    requires org.slf4j;

    exports net.woggioni.rbcs.client;

    opens net.woggioni.rbcs.client.schema;
}