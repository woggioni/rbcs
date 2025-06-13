package net.woggioni.rbcs.cli.impl.converters

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Paths
import picocli.CommandLine


class InputStreamConverter : CommandLine.ITypeConverter<InputStream> {
    override fun convert(value: String): InputStream {
        return Files.newInputStream(Paths.get(value))
    }
}