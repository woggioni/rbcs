module net.woggioni.gbcs.cli {
    requires org.slf4j;
    requires net.woggioni.gbcs;
    requires info.picocli;
    requires net.woggioni.gbcs.base;
    requires kotlin.stdlib;
    requires net.woggioni.jwo;

    exports net.woggioni.gbcs.cli.impl.converters to info.picocli;
    opens net.woggioni.gbcs.cli.impl.commands to info.picocli;
    opens net.woggioni.gbcs.cli.impl to info.picocli;
    opens net.woggioni.gbcs.cli to info.picocli, net.woggioni.gbcs.base;

    exports net.woggioni.gbcs.cli;
}