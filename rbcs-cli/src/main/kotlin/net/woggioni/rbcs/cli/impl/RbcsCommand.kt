package net.woggioni.rbcs.cli.impl

import java.nio.file.Path
import net.woggioni.jwo.Application
import picocli.CommandLine


abstract class RbcsCommand : Runnable {

    @CommandLine.Option(names = ["-h", "--help"], usageHelp = true)
    var usageHelp = false
        private set

    protected fun findConfigurationFile(app: Application, fileName : String): Path {
        val confDir = app.computeConfigurationDirectory(false)
        val configurationFile = confDir.resolve(fileName)
        return configurationFile
    }
}