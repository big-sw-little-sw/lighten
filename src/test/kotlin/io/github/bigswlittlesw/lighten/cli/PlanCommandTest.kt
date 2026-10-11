package io.github.bigswlittlesw.lighten.cli

import io.github.bigswlittlesw.lighten.application.ConfigurationEvaluation
import io.github.bigswlittlesw.lighten.config.ConfigurationLoader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path

class PlanCommandTest {

    @Test
    fun pathOverridesAffectOnlyFirstRelocationAndPreserveJsonContract(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val config = root.resolve("config.json")
        val json = ("""
                {
                  "lighten": {
                    "target-root": "%s",
                    "relocations": [
                      {"source-path": "%s", "target-path": "%s"},
                      {"source-path": "%s", "target-path": "%s"}
                    ]
                  }
                }
                """.trimIndent() + "\n").format(root, root.resolve("old-source"), root.resolve("old-target"),
            root.resolve("second-source"), root.resolve("second-target"))
        Files.writeString(config, json)
        val source = root.resolve("new-source")
        val target = root.resolve("new-target")
        val command = LightenCommand.createCommandLine()
        val out = StringWriter()
        val err = StringWriter()
        command.setOut(PrintWriter(out, true))
        command.setErr(PrintWriter(err, true))

        assertEquals(0, command.execute("plan", "-c", config.toString(), "--json",
            "--source-path", source.toString(), "--target-path", target.toString()))
        val expected = ConfigurationEvaluation().loadRequired(config,
            ConfigurationLoader.PathOverride(source, target))
        val rendered = StringWriter()
        renderPlanJson(expected, PrintWriter(rendered, true))
        assertEquals(rendered.toString(), out.toString())
        assertEquals(source, expected.plan.relocations.first().relocation.sourcePath)
        assertEquals(root.resolve("second-source"), expected.plan.relocations.last().relocation.sourcePath)
        assertEquals(json, Files.readString(config))
        assertTrue(Files.notExists(source))
        assertTrue(Files.notExists(target))

        assertEquals(2, command.execute("plan", "-c", config.toString(), "--json", "--source-path", source.toString()))
        assertTrue(err.toString().contains("must be provided together"))
    }

    @Test
    fun rendersPlanAsJson(@TempDir root: Path) {
        val source = Files.createDirectories(root.resolve("source"))
        val target = Files.createDirectories(root.resolve("target"))
        val config = root.resolve("config.json")
        Files.writeString(config, ("""
                {
                  "lighten": {
                    "target-root": "%s",
                    "relocations": [
                      {"source-path": "%s", "target-path": "%s", "when-source-and-target-directories-exist": "leave-unchanged"}
                    ]
                  }
                }
                """.trimIndent() + "\n").format(root, source, target))

        val command = LightenCommand.createCommandLine()
        val out = StringWriter()
        command.setOut(PrintWriter(out, true))

        assertEquals(0, command.execute("plan", "--config", config.toString(), "--json"))
        val output = out.toString()
        assertTrue(output.contains("\"outcome\":\"unchanged\""))
        assertTrue(output.contains("\"relocations\":["))
        assertTrue(output.contains("\"actions\":["))
    }

    /**
     * A conflict's resolutions are the Workspace's choices for it, by name, so scripts and people see the same names.
     * A source link to somewhere else is not a conflict. It is a blocked action with its reason.
     */
    @Test
    fun conflictResolutionsAreTheWorkspaceChoices(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        for (name in listOf("local/only", "home/both", "local/both", "home/ask", "local/ask", "local/elsewhere", "local/link")) {
            Files.createDirectories(root.resolve(name))
        }
        Files.createSymbolicLink(root.resolve("home/link"), root.resolve("local/elsewhere"))
        val config = root.resolve("config.json")
        val relocations = listOf("only" to "", "both" to "", "ask" to ", \"when-source-and-target-directories-exist\": \"adopt\"", "link" to "")
            .joinToString(",\n") { (name, rule) ->
                "{\"source-path\": \"${root.resolve("home/$name")}\", \"target-path\": \"${root.resolve("local/$name")}\"$rule}"
            }
        Files.writeString(config, "{\"lighten\": {\"target-root\": \"${root.resolve("local")}\", \"relocations\": [$relocations]}}\n")
        val command = LightenCommand.createCommandLine()
        val out = StringWriter()
        command.setOut(PrintWriter(out, true))

        assertEquals(0, command.execute("plan", "-c", config.toString(), "--json"))
        val both = "[\"adopt-and-discard-source\",\"adopt-and-archive-source\",\"leave-unchanged\",\"discard-both\"]"
        assertEquals(
            listOf("[\"adopt-target\"]", both, both),
            Regex("\"resolutions\":(\\[[^]]*])").findAll(out.toString()).map { it.groupValues[1] }.toList(),
            out.toString(),
        )
        val link = root.resolve("home/link")
        val blocked = "{\"type\":\"blocked\",\"path\":\"$link\",\"destructive\":false,\"reason\":\"$link links to " +
            "${root.resolve("local/elsewhere")}, not to ${root.resolve("local/link")}. Remove the link, or set its target " +
            "to where it points\"}"
        assertTrue(out.toString().contains("\"outcome\":\"unresolved\",\"diagnostics\":[],\"actions\":[$blocked]}"), out.toString())
        assertTrue(out.toString().startsWith("{\"schema\":1,\"blocked\":true,\"conflicts\":true,"), out.toString())
    }

    @Test
    fun rendersAnArchiveLocationThatIsAFileAsABlockedAction(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val source = Files.createDirectories(root.resolve("source"))
        val target = Files.createDirectories(root.resolve("target"))
        val archive = Files.writeString(root.resolve("archive"), "a file")
        val config = root.resolve("config.json")
        Files.writeString(config, ("""
                {
                  "lighten": {
                    "target-root": "%s",
                    "relocations": [
                      {"source-path": "%s", "target-path": "%s", "when-source-and-target-directories-exist": "adopt",
                       "when-adopting-target": "archive-source", "archive-root": "%s"}
                    ]
                  }
                }
                """.trimIndent() + "\n").format(root, source, target, archive))
        val command = LightenCommand.createCommandLine()
        val out = StringWriter()
        command.setOut(PrintWriter(out, true))

        assertEquals(0, command.execute("plan", "--config", config.toString(), "--json"))
        val output = out.toString()
        assertTrue(output.contains("\"blocked\":true"), output)
        assertTrue(output.contains("\"type\":\"blocked\""), output)
        assertTrue(output.contains("\"reason\":\"$archive is a file, not a directory\""), output)
    }

    /** A broken link to somewhere else is a blocked step, never a `replace-symlink` one. */
    @Test
    fun rendersABrokenLinkToSomewhereElseAsABlockedAction(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val source = Files.createSymbolicLink(root.resolve("app"), root.resolve("nas/app"))
        val target = Files.createDirectories(root.resolve("local/app"))
        val config = Files.writeString(root.resolve("config.json"),
            "{\"lighten\": {\"target-root\": \"${root.resolve("local")}\", \"relocations\": " +
                "[{\"source-path\": \"$source\", \"target-path\": \"$target\"}]}}\n")
        val command = LightenCommand.createCommandLine()
        val out = StringWriter()
        command.setOut(PrintWriter(out, true))

        assertEquals(0, command.execute("plan", "--config", config.toString(), "--json"))
        val output = out.toString()
        val blocked = "{\"type\":\"blocked\",\"path\":\"$source\",\"destructive\":false,\"reason\":\"$source links to " +
            "${root.resolve("nas/app")}, not to $target. What it links to does not exist now (perhaps an unmounted disk). " +
            "Mount the disk or fix the link, then check again\"}"
        assertTrue(output.contains("\"actions\":[$blocked]"), output)
        assertFalse(output.contains("replace-symlink"), output)
    }

    @Test
    fun rendersPlanAsJsonWithSubcommandShortConfig(@TempDir root: Path) {
        val source = Files.createDirectories(root.resolve("source"))
        val target = Files.createDirectories(root.resolve("target"))
        val config = root.resolve("config.json")
        Files.writeString(config, ("""
                {
                  "lighten": {
                    "target-root": "%s",
                    "relocations": [
                      {"source-path": "%s", "target-path": "%s", "when-source-and-target-directories-exist": "leave-unchanged"}
                    ]
                  }
                }
                """.trimIndent() + "\n").format(root, source, target))

        val command = LightenCommand.createCommandLine()
        val out = StringWriter()
        command.setOut(PrintWriter(out, true))

        assertEquals(0, command.execute("plan", "-c", config.toString(), "--json"))
        val output = out.toString()
        assertTrue(output.contains("\"outcome\":\"unchanged\""))
        assertTrue(output.contains("\"relocations\":["))
        assertTrue(output.contains("\"actions\":["))
    }

    @Test
    fun rendersPlanAsJsonWithTopLevelConfig(@TempDir root: Path) {
        val source = Files.createDirectories(root.resolve("source"))
        val target = Files.createDirectories(root.resolve("target"))
        val config = root.resolve("config.json")
        Files.writeString(config, ("""
                {
                  "lighten": {
                    "target-root": "%s",
                    "relocations": [
                      {"source-path": "%s", "target-path": "%s", "when-source-and-target-directories-exist": "leave-unchanged"}
                    ]
                  }
                }
                """.trimIndent() + "\n").format(root, source, target))

        val command = LightenCommand.createCommandLine()
        val out = StringWriter()
        command.setOut(PrintWriter(out, true))

        assertEquals(0, command.execute("--config", config.toString(), "plan", "--json"))
        val output = out.toString()
        assertTrue(output.contains("\"outcome\":\"unchanged\""))
        assertTrue(output.contains("\"relocations\":["))
        assertTrue(output.contains("\"actions\":["))
    }

    @Test
    fun rendersPlanAsJsonWithTopLevelShortConfig(@TempDir root: Path) {
        val source = Files.createDirectories(root.resolve("source"))
        val target = Files.createDirectories(root.resolve("target"))
        val config = root.resolve("config.json")
        Files.writeString(config, ("""
                {
                  "lighten": {
                    "target-root": "%s",
                    "relocations": [
                      {"source-path": "%s", "target-path": "%s", "when-source-and-target-directories-exist": "leave-unchanged"}
                    ]
                  }
                }
                """.trimIndent() + "\n").format(root, source, target))

        val command = LightenCommand.createCommandLine()
        val out = StringWriter()
        command.setOut(PrintWriter(out, true))

        assertEquals(0, command.execute("-c", config.toString(), "plan", "--json"))
        val output = out.toString()
        assertTrue(output.contains("\"outcome\":\"unchanged\""))
        assertTrue(output.contains("\"relocations\":["))
        assertTrue(output.contains("\"actions\":["))
    }

    @Test
    fun nonInteractivePlanWithoutJsonFailsGracefully(@TempDir root: Path) {
        val source = Files.createDirectories(root.resolve("source"))
        val target = Files.createDirectories(root.resolve("target"))
        val config = root.resolve("config.json")
        Files.writeString(config, ("""
                {
                  "lighten": {
                    "target-root": "%s",
                    "relocations": [
                      {"source-path": "%s", "target-path": "%s"}
                    ]
                  }
                }
                """.trimIndent() + "\n").format(root, source, target))

        val command = LightenCommand.createCommandLine()
        val err = StringWriter()
        command.setErr(PrintWriter(err, true))

        assertEquals(2, command.execute("plan", "--config", config.toString()))
        assertTrue(err.toString().contains("Lighten TUI requires an interactive terminal. Use --json for automation."))
    }

    @Test
    fun rendersUnconfiguredPlanAsJson() {
        val command = LightenCommand.createCommandLine()
        val out = StringWriter()
        command.setOut(PrintWriter(out, true))

        assertEquals(0, command.execute("plan", "--config", ConfigurationLoader.DEFAULT_PATH.toString(), "--json"))
        assertTrue(out.toString().contains("\"relocations\":[]"))
    }
}
