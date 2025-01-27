module net.woggioni.gbcs.cli {
    requires org.slf4j;
    requires net.woggioni.gbcs.server;
    requires info.picocli;
    requires net.woggioni.gbcs.common;
    requires net.woggioni.gbcs.client;
    requires kotlin.stdlib;
    requires net.woggioni.jwo;
    requires net.woggioni.gbcs.api;
    requires io.netty.codec.http;

    exports net.woggioni.gbcs.cli.impl.converters to info.picocli;
    opens net.woggioni.gbcs.cli.impl.commands to info.picocli;
    opens net.woggioni.gbcs.cli.impl to info.picocli;
    opens net.woggioni.gbcs.cli to info.picocli, net.woggioni.gbcs.common;

    exports net.woggioni.gbcs.cli;
}