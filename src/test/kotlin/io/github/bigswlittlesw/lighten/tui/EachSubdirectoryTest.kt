package io.github.bigswlittlesw.lighten.tui

import io.github.bigswlittlesw.lighten.application.ConfigurationEvaluation
import io.github.bigswlittlesw.lighten.application.LightenSession
import io.github.bigswlittlesw.lighten.config.ConfigurationLoader
import io.github.bigswlittlesw.lighten.diskTree
import io.github.bigswlittlesw.lighten.discovery.SetupDiscoveryFixture
import io.github.bigswlittlesw.lighten.reconcile.RelocationOutcome
import io.github.bigswlittlesw.lighten.tui.BrowseTest.Companion.await
import io.github.bigswlittlesw.lighten.tui.BrowseTest.Companion.choose
import io.github.bigswlittlesw.lighten.tui.BrowseTest.Companion.chooseGroup
import io.github.bigswlittlesw.lighten.tui.BrowseTest.Companion.enter
import io.github.bigswlittlesw.lighten.tui.BrowseTest.Companion.escape
import io.github.bigswlittlesw.lighten.tui.BrowseTest.Companion.key
import io.github.bigswlittlesw.lighten.tui.BrowseTest.Companion.render
import io.github.bigswlittlesw.lighten.tui.BrowseTest.Companion.row
import io.github.bigswlittlesw.lighten.tui.BrowseTest.Companion.selectedGroup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Browse with each-subdirectory suggestions: each subdirectory found is a row of its own, the keep list stays in the
 * home, and files are never offered. Every test checks that Browse does not change the disk.
 */
class EachSubdirectoryTest {
    @TempDir lateinit var temporary: Path

    /**
     * Each subdirectory of `.gradle` is a row, hidden ones too, and the kept `init.d` is `−` with its reason. Neither
     * `gradle.properties` nor `.gradle` itself is offered. `Space` on the app adds every subdirectory but the kept one.
     */
    @Test fun eachSubdirectoryIsARowAndTheKeepListStaysHome() {
        val root = fixture()
        config(root, "")
        val before = disk(root)
        SetupDiscoveryFixture().use { workers ->
            val ui = browse(root, workers)
            val screen = render(ui)
            for (child in listOf(".gradle/caches", ".gradle/wrapper", ".gradle/.tmp")) {
                assertTrue(row(screen, child).contains("○ $child") && row(screen, child).contains("not moved yet"), screen)
            }
            assertTrue(row(screen, ".gradle/init.d").contains("− .gradle/init.d") && row(screen, ".gradle/init.d").contains(KEPT_NOTE), screen)
            assertFalse(screen.contains("gradle.properties"), screen)
            assertFalse(Regex("[●○−⊘] \\.gradle( |┃)").containsMatchIn(screen), screen)
            choose(ui, ".gradle/init.d")
            key(ui, ' ')
            assertTrue(row(render(ui), ".gradle/init.d").contains("− .gradle/init.d"), render(ui))
            enter(ui)
            val details = details(ui)
            assertTrue(details.has(KEPT_IN_BROWSE), details)
            assertTrue(details.has("as written: each subdirectory of .gradle, except init.d"), details)
            escape(ui)
            chooseGroup(ui, "Gradle")
            assertTrue(selectedGroup(render(ui), "○ Gradle", "0 of 4 added"), render(ui))
            key(ui, ' ')
            // Nothing was skipped, so Browse says nothing: the kept directory is not a row that could be added.
            assertTrue(selectedGroup(render(ui), "● Gradle", "4 of 4 added"), render(ui))
            assertFalse(render(ui).contains("Skipped"), render(ui))
            assertTrue(row(render(ui), ".gradle/init.d").contains("− .gradle/init.d"), render(ui))
            save(ui)
            val written = ConfigurationLoader().read(root.resolve("config.json")).file.relocations.map { it.sourcePath }
            assertEquals(listOf(".tmp", "caches", "jdks", "wrapper").map { home(root, ".gradle/$it") }, written)
        }
        assertEquals(before, disk(root))
    }

    /**
     * A subdirectory that a tool makes later is a new `○` row after checking again; nothing adds it. A parent that does
     * not exist yet is a `−` row that says so, and Space does nothing on it.
     */
    @Test fun aNewSubdirectoryIsANewRowAndAMissingParentSaysSo() {
        val root = fixture()
        config(root, """{"source-path": "${home(root, ".gradle/caches")}"}""")
        SetupDiscoveryFixture().use { workers ->
            val ui = browse(root, workers)
            assertTrue(row(render(ui), ".gradle/caches").contains("● .gradle/caches"), render(ui))
            assertFalse(render(ui).contains(".gradle/native"), render(ui))
            Files.createDirectory(root.resolve("home/.gradle/native"))
            val before = disk(root)
            key(ui, 'r'); await(workers, ui)
            assertTrue(row(render(ui), ".gradle/native").contains("○ .gradle/native"), render(ui))
            assertTrue(row(render(ui), ".gradle/caches").contains("● .gradle/caches"), render(ui))
            val missing = row(render(ui), ".nvm")
            assertTrue(missing.contains("− .nvm") && missing.contains("not created yet"), render(ui))
            choose(ui, ".nvm")
            assertFalse(render(ui).contains("Space:"), render(ui))
            enter(ui)
            assertTrue(details(ui).has(EACH_SUBDIRECTORY_IN_BROWSE), details(ui))
            escape(ui)
            assertEquals(before, disk(root))
        }
    }

    /**
     * A subdirectory that is a link is taken over as it is. A subdirectory inside a linked parent takes over that
     * parent, whether the parent is the listed directory or a directory above it.
     */
    @Test fun linksAndLinkedParentsFollowTheTakeOverRules() {
        val root = fixture()
        config(root, "")
        val before = disk(root)
        SetupDiscoveryFixture().use { workers ->
            val ui = browse(root, workers)
            val screen = render(ui)
            assertTrue(row(screen, ".gradle/jdks").contains("○ .gradle/jdks") && row(screen, ".gradle/jdks").contains(ALREADY_A_LINK), screen)
            val cargo = home(root, ".cargo")
            for (child in listOf(".cargo/git", ".cargo/registry")) {
                assertTrue(row(screen, child).contains("○ $child") && row(screen, child).contains(insideLink(cargo)), screen)
            }
            val share = home(root, ".local/share")
            assertTrue(row(screen, ".local/share/uv/python").contains(insideLink(share)), screen)
            assertTrue(row(screen, ".local/share/uv/credentials").contains("− .local/share/uv/credentials"), screen)
            choose(ui, ".gradle/jdks"); key(ui, ' ')
            choose(ui, ".cargo/git"); key(ui, ' ')
            val taken = render(ui)
            assertTrue(row(taken, ".cargo/registry").contains(insideManaged(cargo)), taken)
            assertTrue(row(taken, ".cargo").contains("● .cargo"), taken)
            save(ui)
            val written = ConfigurationLoader().read(root.resolve("config.json")).file.relocations.associate { it.sourcePath to it.targetPath }
            assertEquals(mapOf(home(root, ".gradle/jdks") to root.resolve("storage/jdks").toString(), cargo to root.resolve("storage/cargo").toString()), written)
            val plan = ConfigurationEvaluation().loadRequired(root.resolve("config.json")).plan.relocations
            assertTrue(plan.all { it.outcome == RelocationOutcome.CONVERGED }, plan.toString())
        }
        assertEquals(before, disk(root))
    }

    /**
     * The configuration moved the whole `.m2` before its list suggested each subdirectory: the relocation stays in sync,
     * its subdirectories move with it, and Details say how to change over. A relocation of a directory that is now on
     * the keep list stays too, and says that the list keeps it.
     */
    @Test fun relocationsMadeBeforeTheListChangedStayAndSaySo() {
        val root = fixture()
        val m2 = home(root, ".m2")
        Files.createDirectories(root.resolve("local/.m2/repository"))
        Files.writeString(root.resolve("local/.m2/settings.xml"), "<settings/>")
        Files.createSymbolicLink(Path.of(m2), root.resolve("local/.m2"))
        config(root, """{"source-path": "$m2"}, {"source-path": "${home(root, ".gradle/init.d")}"}""")
        val before = disk(root)
        SetupDiscoveryFixture().use { workers ->
            val ui = browse(root, workers)
            val screen = render(ui)
            assertTrue(row(screen, ".m2").contains("● .m2"), screen)
            assertTrue(row(screen, ".m2/repository").contains("● .m2/repository") && row(screen, ".m2/repository").contains(insideManaged(m2)), screen)
            assertFalse(screen.contains("settings.xml"), screen)
            assertTrue(row(screen, ".gradle/init.d").contains("● .gradle/init.d") && row(screen, ".gradle/init.d").contains(KEPT_NOTE), screen)
            choose(ui, ".m2"); enter(ui)
            val parent = details(ui)
            assertTrue(parent.has(nowEachSubdirectory(listOf())), parent)
            assertTrue(parent.has(changeOver(m2)), parent)
            escape(ui)
            choose(ui, ".gradle/init.d"); enter(ui)
            assertTrue(details(ui).has(KEPT_IN_CONFIGURATION), details(ui))
            escape(ui)
            escape(ui)
            ui.app.closeEditor()
        }
        assertEquals(before, disk(root))
        val plan = ConfigurationEvaluation().loadRequired(root.resolve("config.json")).plan.relocations
        assertEquals(RelocationOutcome.CONVERGED, plan.single { it.relocation.sourcePath == Path.of(m2) }.outcome)
    }

    /**
     * A home shaped as Gradle, Cargo, uv and nvm leave it, with a list of the user's own. `.cargo` and `.local/share`
     * are links the user made; `.nvm` is not there.
     */
    private fun fixture(): Path {
        val root = Files.createTempDirectory(temporary, "each-").toRealPath()
        fun dir(path: String) = Files.createDirectories(root.resolve(path))
        for (path in listOf("home/.gradle/caches", "home/.gradle/wrapper", "home/.gradle/.tmp", "home/.gradle/init.d",
            "local", "storage/jdks", "storage/cargo/registry", "storage/cargo/git", "storage/share/uv/python",
            "storage/share/uv/credentials", "home/.local")) dir(path)
        Files.writeString(root.resolve("home/.gradle/gradle.properties"), "org.gradle.daemon=true")
        Files.writeString(root.resolve("storage/cargo/env"), "export PATH")
        Files.createSymbolicLink(root.resolve("home/.gradle/jdks"), root.resolve("storage/jdks"))
        Files.createSymbolicLink(root.resolve("home/.cargo"), root.resolve("storage/cargo"))
        Files.createSymbolicLink(root.resolve("home/.local/share"), root.resolve("storage/share"))
        Files.writeString(
            root.resolve("list.json"),
            """
            {"apps": [
              {"name": "Gradle", "category": "JVM", "directories": [{"path": ".gradle", "each-subdirectory": true, "keep": ["init.d"]}]},
              {"name": "Maven", "category": "JVM", "directories": [{"path": ".m2", "each-subdirectory": true}]},
              {"name": "Cargo", "category": "Rust", "directories": [{"path": ".cargo", "each-subdirectory": true}]},
              {"name": "nvm", "category": "JavaScript", "directories": [{"path": ".nvm", "each-subdirectory": true, "keep": [".git"]}]},
              {"name": "uv", "category": "Python",
               "directories": [{"path": ".local/share/uv", "each-subdirectory": true, "keep": ["credentials"]}]}
            ]}
            """.trimIndent(),
        )
        return root
    }

    private fun config(root: Path, relocations: String) {
        Files.writeString(
            root.resolve("config.json"),
            """
            {"lighten": {"source-root": "${root.resolve("home")}", "target-root": "${root.resolve("local")}",
              "suggestion-list": "${root.resolve("list.json")}", "relocations": [$relocations],
              "ignored-source-paths": ["${home(root, "unused")}"]}}
            """.trimIndent(),
        )
    }

    /** The disk under `root`, but the configuration file, which saving rewrites. */
    private fun disk(root: Path): List<String> = diskTree(root, setOf(root.resolve("config.json")))

    private fun home(root: Path, relative: String): String = root.resolve("home").resolve(relative).toString()

    private fun browse(root: Path, workers: SetupDiscoveryFixture): HeadlessTui {
        val ui = HeadlessTui(LightenSession(root.resolve("config.json")), openConfiguration = true, discoveryFactory = workers::get)
        key(ui, 'b'); await(workers, ui)
        return ui
    }

    /** From Browse: back to Configuration's list, save, and confirm replacing the file. */
    private fun save(ui: HeadlessTui) {
        escape(ui); key(ui, 's'); key(ui, 'y')
        assertTrue(render(ui).contains("[1: Workspace]"), render(ui))
    }

    /** Details as the whole pane shows them, without spaces, as wrapping long paths moves them. */
    private fun details(ui: HeadlessTui): String = BrowseTest.all(ui).filterNot(Char::isWhitespace)

    private fun String.has(text: String): Boolean = contains(text.filterNot(Char::isWhitespace))
}
