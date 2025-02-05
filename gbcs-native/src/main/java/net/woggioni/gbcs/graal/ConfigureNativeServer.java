package net.woggioni.gbcs.graal;

import java.time.Duration;

public class ConfigureNativeServer {

    public static void main(String[] args) throws Exception {
        NativeServer.run(Duration.ofSeconds(60));
    }
}
