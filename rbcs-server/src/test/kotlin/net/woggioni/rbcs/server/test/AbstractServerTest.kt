package net.woggioni.rbcs.server.test

import net.woggioni.rbcs.api.Configuration
import net.woggioni.rbcs.server.RemoteBuildCacheServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path


@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
abstract class AbstractServerTest {

    protected lateinit var cfg : Configuration

    protected lateinit var testDir : Path

    private var serverHandle : RemoteBuildCacheServer.ServerHandle? = null

    @BeforeAll
    fun setUp0(@TempDir tmpDir : Path) {
        this.testDir = tmpDir
        setUp()
        startServer(cfg)
    }

    @AfterAll
    fun tearDown0() {
        tearDown()
        stopServer()
    }

    abstract fun setUp()

    abstract fun tearDown()

    private fun startServer(cfg : Configuration) {
        this.serverHandle = RemoteBuildCacheServer(cfg).run()
    }

    private fun stopServer() {
        this.serverHandle?.let {
            it.sendShutdownSignal()
            it.get()
        }
    }
}