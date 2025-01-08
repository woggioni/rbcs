package net.woggioni.gbcs.test;


import net.woggioni.gbcs.GradleBuildCacheServer;
import net.woggioni.gbcs.api.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public abstract class AbstractServerTest {

    protected Configuration cfg;
    protected Path testDir;
    private GradleBuildCacheServer.ServerHandle serverHandle;

    @BeforeAll
    public void setUp0(@TempDir Path tmpDir) {
        this.testDir = tmpDir;
        setUp();
        startServer(cfg);
    }

    @AfterAll
    public void tearDown0() {
        tearDown();
        stopServer();
    }

    protected abstract void setUp();

    protected abstract void tearDown();

    private void startServer(Configuration cfg) {
        this.serverHandle = new GradleBuildCacheServer(cfg).run();
    }

    private void stopServer() {
        if (serverHandle != null) {
            try (GradleBuildCacheServer.ServerHandle handle = serverHandle) {
                handle.shutdown();
            }
        }
    }
}