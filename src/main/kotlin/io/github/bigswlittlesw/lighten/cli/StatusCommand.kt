package io.github.bigswlittlesw.lighten.cli

import io.github.bigswlittlesw.lighten.application.ConfigurationEvaluation
import io.github.bigswlittlesw.lighten.application.isUnconfiguredDefault
import io.github.bigswlittlesw.lighten.tui.launchTui
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.ParentCommand
import picocli.CommandLine.Spec
import java.util.concurrent.Callable

@Command(name = "status", description = ["Show the state of the configured relocations."])
internal class StatusCommand : Callable<Int> {
    @ParentCommand
    private lateinit var parent: LightenCommand

    @Option(names = ["--json"], description = ["Emit JSON."])
    private var json = false

    @Spec
    private lateinit var spec: CommandLine.Model.CommandSpec

    override fun call(): Int {
        val configPath = parent.config
        if (!json) {
            return launchTui(configPath, parent.debugStepDelayMillis, spec.commandLine().err)
        }
        val output = spec.commandLine().out
        if (isUnconfiguredDefault(configPath)) {
            renderStatusJson(configPath, listOf(), output, configured = false)
            return CommandLine.ExitCode.OK
        }
        val snapshots = ConfigurationEvaluation().loadRequired(configPath).observations.map { state ->
            StatusSnapshot(
                state.relocation.sourcePath, state.relocation.targetPath,
                state.sourceState(),
            )
        }
        renderStatusJson(configPath, snapshots, output)
        return CommandLine.ExitCode.OK
    }
}
