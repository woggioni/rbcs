package net.woggioni.rbcs.cli.impl.converters

import picocli.CommandLine
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Paths


class OutputStreamConverter : CommandLine.ITypeConverter<OutputStream> {
    override fun convert(value: String): OutputStream {
        return Files.newOutputStream(Paths.get(value))
    }
}