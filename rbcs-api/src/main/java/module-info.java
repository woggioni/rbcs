module net.woggioni.rbcs.api {
    requires static lombok;
    requires java.xml;
    requires io.netty.buffer;
    requires io.netty.handler;
    requires io.netty.transport;
    requires io.netty.common;
    exports net.woggioni.rbcs.api;
    exports net.woggioni.rbcs.api.exception;
    exports net.woggioni.rbcs.api.message;
}