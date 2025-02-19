package net.woggioni.rbcs.cli.impl.converters

import picocli.CommandLine


class ByteSizeConverter : CommandLine.ITypeConverter<Int> {
    override fun convert(value: String): Int {
        return Integer.decode(value)
    }
}