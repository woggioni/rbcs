package net.woggioni.gbcs.cli.impl.converters

import picocli.CommandLine
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Paths


class InputStreamConverter : CommandLine.ITypeConverter<InputStream> {
    override fun convert(value: String): InputStream {
        return Files.newInputStream(Paths.get(value))
    }
}