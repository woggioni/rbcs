package net.woggioni.gbcs.cli.impl

import net.woggioni.jwo.Application
import picocli.CommandLine
import java.nio.file.Path


abstract class GbcsCommand : Runnable {

    @CommandLine.Option(names = ["-h", "--help"], usageHelp = true)
    var usageHelp = false
        private set

    protected fun findConfigurationFile(app: Application, fileName : String): Path {
        val confDir = app.computeConfigurationDirectory()
        val configurationFile = confDir.resolve(fileName)
        return configurationFile
    }
}