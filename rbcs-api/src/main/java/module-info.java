module net.woggioni.rbcs.api {
    requires static lombok;
    requires java.xml;
    requires io.netty.buffer;
    exports net.woggioni.rbcs.api;
    exports net.woggioni.rbcs.api.exception;
    exports net.woggioni.rbcs.api.event;
}