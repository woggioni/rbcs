open module net.woggioni.gbcs.test {
    requires net.woggioni.gbcs;
    requires net.woggioni.gbcs.api;
    requires java.naming;
    requires org.bouncycastle.pkix;
    requires org.bouncycastle.provider;
    requires io.netty.codec.http;
    requires net.woggioni.gbcs.base;
    requires java.net.http;
    requires static lombok;
    requires org.junit.jupiter.params;

    exports net.woggioni.gbcs.test to org.junit.platform.commons;
//    opens net.woggioni.gbcs.test to org.junit.platform.commons;
}