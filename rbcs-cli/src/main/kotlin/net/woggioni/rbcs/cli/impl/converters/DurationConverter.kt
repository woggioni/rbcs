package net.woggioni.rbcs.cli.impl.converters

import java.time.Duration
import picocli.CommandLine


class DurationConverter : CommandLine.ITypeConverter<Duration> {
    override fun convert(value: String): Duration {
        return Duration.parse(value)
    }
}