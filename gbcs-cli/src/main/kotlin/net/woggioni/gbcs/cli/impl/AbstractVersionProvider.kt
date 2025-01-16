package net.woggioni.gbcs.cli.impl

import picocli.CommandLine
import java.util.jar.Attributes
import java.util.jar.JarFile
import java.util.jar.Manifest


abstract class AbstractVersionProvider : CommandLine.IVersionProvider {
    private val version: String
    private val vcsHash: String

    init {
        val mf = Manifest()
        javaClass.module.getResourceAsStream(JarFile.MANIFEST_NAME).use { `is` ->
            mf.read(`is`)
        }
        val mainAttributes = mf.mainAttributes
        version = mainAttributes.getValue(Attributes.Name.SPECIFICATION_VERSION) ?: throw RuntimeException("Version information not found in manifest")
        vcsHash = mainAttributes.getValue(Attributes.Name.IMPLEMENTATION_VERSION) ?: throw RuntimeException("Version information not found in manifest")
    }

    override fun getVersion(): Array<String?> {
        return if (version.endsWith("-SNAPSHOT")) {
            arrayOf(version, vcsHash)
        } else {
            arrayOf(version)
        }
    }
}
