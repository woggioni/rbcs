module net.woggioni.rbcs.cli {
    requires org.slf4j;
    requires net.woggioni.rbcs.server;
    requires info.picocli;
    requires net.woggioni.rbcs.common;
    requires net.woggioni.rbcs.client;
    requires kotlin.stdlib;
    requires net.woggioni.jwo;
    requires net.woggioni.rbcs.api;

    exports net.woggioni.rbcs.cli.impl.converters to info.picocli;
    opens net.woggioni.rbcs.cli.impl.commands to info.picocli;
    opens net.woggioni.rbcs.cli.impl to info.picocli;
    opens net.woggioni.rbcs.cli to info.picocli, net.woggioni.rbcs.common;

    exports net.woggioni.rbcs.cli;
}