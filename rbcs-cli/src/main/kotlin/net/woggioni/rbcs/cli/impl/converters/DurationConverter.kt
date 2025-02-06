package net.woggioni.rbcs.cli.impl.converters

import picocli.CommandLine
import java.time.Duration


class DurationConverter : CommandLine.ITypeConverter<Duration> {
    override fun convert(value: String): Duration {
        return Duration.parse(value)
    }
}