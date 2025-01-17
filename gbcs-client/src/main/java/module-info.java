module net.woggioni.gbcs.client {
    requires io.netty.handler;
    requires io.netty.codec.http;
    requires io.netty.transport;
    requires kotlin.stdlib;
    requires io.netty.common;
    requires io.netty.buffer;
    requires java.xml;
    requires net.woggioni.gbcs.common;
    requires net.woggioni.gbcs.api;
    requires io.netty.codec;
    requires org.slf4j;

    exports net.woggioni.gbcs.client;

    opens net.woggioni.gbcs.client.schema;
}