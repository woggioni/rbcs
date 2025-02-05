package net.woggioni.gbcs.graal;

import net.woggioni.gbcs.server.GradleBuildCacheServer;
import net.woggioni.jwo.Application;

import java.nio.file.Path;
import java.time.Duration;

public class NativeServer {

    private static Path findConfigurationFile(Application app, String fileName) {
        final var confDir = app.computeConfigurationDirectory();
        final var  configurationFile = confDir.resolve(fileName);
        return configurationFile;
    }

    static void run(Duration timeout) throws Exception {
        final var app = Application.builder("gbcs")
                .configurationDirectoryEnvVar("GBCS_CONFIGURATION_DIR")
                .configurationDirectoryPropertyKey("net.woggioni.gbcs.conf.dir")
                .build();

        final var configurationFile = findConfigurationFile(app, "gbcs-server.xml");
        final var cfg = GradleBuildCacheServer.Companion.loadConfiguration(configurationFile);
        try(final var handle = new GradleBuildCacheServer(cfg).run()) {
            if(timeout != null) {
                Thread.sleep(timeout);
                handle.shutdown();
            }
        }
    }

    private static void setPropertyIfNotPresent(String key, String value) {
        final var previousValue = System.getProperty(key);
        if(previousValue == null) {
            System.setProperty(key, value);
        }
    }

    public static void main(String[] args) throws Exception {
        setPropertyIfNotPresent("logback.configurationFile", "net/woggioni/gbcs/graal/logback.xml");
        setPropertyIfNotPresent("io.netty.leakDetectionLevel", "DISABLED");
        run(null);
    }
}
