module net.woggioni.rbcs.api {
    requires static lombok;
    requires io.netty.handler;
    requires io.netty.common;
    requires net.woggioni.rbcs.common;
    requires io.netty.transport;
    requires io.netty.codec.http;
    requires io.netty.buffer;
    requires org.slf4j;
    requires java.xml;


    exports net.woggioni.rbcs.api;
    exports net.woggioni.rbcs.api.exception;
    exports net.woggioni.rbcs.api.message;
}