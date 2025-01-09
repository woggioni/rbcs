package net.woggioni.gbcs.cli.impl

import picocli.CommandLine


abstract class GbcsCommand : Runnable {

    @CommandLine.Option(names = ["-h", "--help"], usageHelp = true)
    var usageHelp = false
        private set
}