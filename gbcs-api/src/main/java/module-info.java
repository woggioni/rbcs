module net.woggioni.gbcs.api {
    requires static lombok;
    requires java.xml;
    requires io.netty.buffer;
    exports net.woggioni.gbcs.api;
    exports net.woggioni.gbcs.api.exception;
    exports net.woggioni.gbcs.api.event;
}