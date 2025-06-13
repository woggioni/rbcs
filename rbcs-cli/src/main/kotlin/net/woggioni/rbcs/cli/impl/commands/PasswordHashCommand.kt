package net.woggioni.rbcs.cli.impl.commands

import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter
import net.woggioni.jwo.UncloseableOutputStream
import net.woggioni.rbcs.cli.impl.RbcsCommand
import net.woggioni.rbcs.cli.impl.converters.OutputStreamConverter
import net.woggioni.rbcs.common.PasswordSecurity.hashPassword
import picocli.CommandLine


@CommandLine.Command(
    name = "password",
    description = ["Generate a password hash to add to RBCS configuration file"],
    showDefaultValues = true
)
class PasswordHashCommand : RbcsCommand() {
    @CommandLine.Option(
        names = ["-o", "--output-file"],
        description = ["Write the output to a file instead of stdout"],
        converter = [OutputStreamConverter::class],
        showDefaultValue = CommandLine.Help.Visibility.NEVER,
        paramLabel = "OUTPUT_FILE"
    )
    private var outputStream: OutputStream = UncloseableOutputStream(System.out)

    override fun run() {
        val password1 = String(System.console().readPassword("Type your password:"))
        val password2 = String(System.console().readPassword("Type your password again for confirmation:"))
        if(password1 != password2) throw IllegalArgumentException("Passwords do not match")

        PrintWriter(OutputStreamWriter(outputStream, Charsets.UTF_8)).use {
            it.println(hashPassword(password1))
        }
    }
}