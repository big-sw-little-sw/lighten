package io.github.bigswlittlesw.lighten.tui

import io.github.bigswlittlesw.lighten.application.ConfigurationEvaluation
import io.github.bigswlittlesw.lighten.application.LightenSession
import io.github.bigswlittlesw.lighten.config.ConfigurationLoader
import io.github.bigswlittlesw.lighten.discovery.SetupDiscoveryFixture
import io.github.bigswlittlesw.lighten.diskTree
import io.github.bigswlittlesw.lighten.reconcile.ReconciliationAction
import io.github.bigswlittlesw.lighten.reconcile.RelocationOutcome
import io.github.bigswlittlesw.lighten.tui.BrowseTest.Companion.await
import io.github.bigswlittlesw.lighten.tui.BrowseTest.Companion.choose
import io.github.bigswlittlesw.lighten.tui.BrowseTest.Companion.chooseGroup
import io.github.bigswlittlesw.lighten.tui.BrowseTest.Companion.enter
import io.github.bigswlittlesw.lighten.tui.BrowseTest.Companion.escape
import io.github.bigswlittlesw.lighten.tui.BrowseTest.Companion.key
import io.github.bigswlittlesw.lighten.tui.BrowseTest.Companion.render
import io.github.bigswlittlesw.lighten.tui.BrowseTest.Companion.selected
import io.github.bigswlittlesw.lighten.tui.BrowseTest.Companion.selectedGroup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.regex.Pattern

/**
 * Browse takes over links that the user made: Space or `L` adds a relocation from the link to its real path, so the
 * next plan finds it in sync. Every test checks that nothing on disk changes, from opening Browse to saving.
 */
class TakeOverTest {
    @TempDir lateinit var temporary: Path

    @Test fun spaceTakesOverALinkAndTheSavedRelocationIsInSync() {
        val root = fixture()
        val before = tree(root)
        SetupDiscoveryFixture().use { workers ->
            val ui = browse(root, workers)
            choose(ui, "abs-link")
            val offered = render(ui)
            assertTrue(row(offered, "abs-link").contains("○ abs-link") && row(offered, "abs-link").contains("already a link"), offered)
            assertTrue(offered.contains("Space: Take over"), offered)
            key(ui, ' ')
            assertTrue(selected(render(ui), "● abs-link"), render(ui))
            assertTrue(render(ui).contains("Space: Remove"), render(ui))
            // Space again takes it out, and it can be taken over again.
            key(ui, ' ')
            assertTrue(selected(render(ui), "○ abs-link"), render(ui))
            key(ui, ' ')
            choose(ui, "rel-link"); key(ui, ' ')
            choose(ui, "under-root"); key(ui, ' ')
            choose(ui, "chain"); key(ui, ' ')
            save(ui)
            assertEquals(before, tree(root))
            val written = ConfigurationLoader().read(root.resolve("config.json")).file.relocations.associate { it.sourcePath to it.targetPath }
            assertEquals(root.resolve("storage/abs").toString(), written[home(root, "abs-link")])
            // A relative link's target is its real path, in full, and so is the end of a chain.
            assertEquals(root.resolve("storage/rel").toString(), written[home(root, "rel-link")])
            assertEquals(root.resolve("storage/end").toString(), written[home(root, "chain")])
            // A link to where the roots would put it anyway needs no target in the file.
            assertTrue(written.containsKey(home(root, "under-root")) && written[home(root, "under-root")] == null, written.toString())
            val plan = ConfigurationEvaluation().loadRequired(root.resolve("config.json")).plan.relocations
                .associate { it.relocation.sourcePath.fileName.toString() to it }
            for (name in listOf("abs-link", "rel-link", "under-root", "chain")) {
                assertEquals(RelocationOutcome.CONVERGED, plan.getValue(name).outcome, name)
                assertTrue(plan.getValue(name).actions.all { it is ReconciliationAction.NoOp }, name)
            }
            assertTrue(render(ui).contains("c: show 4 in sync"), render(ui))
        }
        assertEquals(before, tree(root))
    }

    /**
     * Space on a directory inside a linked parent takes over the parent, once, even though no list suggests it. The
     * directories under it then show a dim `●`, and the links inside the parent stay as they are.
     */
    @Test fun aLinkedParentIsTakenOverOnceAndTheLinksInsideItStay() {
        val root = fixture()
        val before = tree(root)
        SetupDiscoveryFixture().use { workers ->
            val ui = browse(root, workers)
            val cache = root.resolve("home/.cache").toString()
            choose(ui, ".cache/uv")
            assertTrue(row(render(ui), ".cache/uv").contains("inside $cache, which is a link"), render(ui))
            enter(ui)
            val details = details(ui)
            assertTrue(details.has("Space takes over $cache: Lighten adds it with ${root.resolve("storage/cache")} as its target."), details)
            escape(ui)
            key(ui, ' ')
            val taken = render(ui)
            for (child in listOf(".cache/uv", ".cache/pip")) {
                assertTrue(row(taken, child).contains("● $child") && row(taken, child).contains("inside $cache, which Lighten manages"), taken)
            }
            assertTrue(row(taken, ".cache").contains("● .cache") && row(taken, ".cache").contains("already a link"), taken)
            // The other directory under the parent is covered too: Space on it takes the parent out again.
            choose(ui, ".cache/pip")
            assertTrue(render(ui).contains("Space: Remove"), render(ui))
            key(ui, ' ')
            assertTrue(row(render(ui), ".cache/uv").contains("○ .cache/uv"), render(ui))
            assertTrue(row(render(ui), ".cache").contains("○ .cache"), render(ui))
            key(ui, ' ')
            save(ui)
            val written = ConfigurationLoader().read(root.resolve("config.json")).file.relocations.map { it.sourcePath }
            assertEquals(listOf(home(root, "plain"), cache), written)
        }
        assertEquals(before, tree(root))
        assertEquals(Path.of("../uv-elsewhere"), Files.readSymbolicLink(root.resolve("storage/cache/uv")))
        assertEquals(root.resolve("storage/abs-example"), Files.readSymbolicLink(root.resolve("storage/cache/example")))
    }

    /**
     * A link that can't be taken over stays `−` with its reason, and Space does nothing. A heading whose rows all
     * have link problems says so.
     */
    @Test fun linksWithProblemsStayWithTheirReasons() {
        val root = fixture()
        val before = tree(root)
        SetupDiscoveryFixture().use { workers ->
            val ui = browse(root, workers)
            val screen = render(ui)
            for ((name, note) in listOf(
                "chain-home" to "link points inside your home", "broken" to "link is broken", "loop-a" to "link is broken",
                "to-file" to "link points to a file", "inside" to "link points inside your home",
            )) {
                assertTrue(row(screen, name).contains("− $name") && row(screen, name).contains(note), screen)
                choose(ui, name)
                val selected = render(ui)
                assertFalse(selected.contains("Space:"), selected)
                key(ui, ' ')
                assertEquals(selected, render(ui))
            }
            chooseGroup(ui, "Problems")
            assertTrue(selectedGroup(render(ui), "− Problems", "all links with problems"), render(ui))
            choose(ui, "broken"); enter(ui)
            val details = details(ui)
            assertTrue(details.has("Lighten can't take over ${root.resolve("home/broken")}: the link is broken"), details)
            ui.app.closeEditor()
        }
        assertEquals(before, tree(root))
    }

    /** `L` takes over every shown link that can be, each linked parent once, and says what it left out. */
    @Test fun lTakesOverEveryLinkOnceAndLeavesProblemsOut() {
        val root = fixture()
        val before = tree(root)
        SetupDiscoveryFixture().use { workers ->
            val ui = browse(root, workers)
            val offered = render(ui)
            // abs-link, rel-link, under-root, chain, and the linked parents .cache and .local.
            assertTrue(offered.contains("6 directories are links you made. Press L to take them over."), offered)
            key(ui, '?')
            assertTrue(render(ui).contains("Take over every shown link you made"), render(ui))
            key(ui, '?')
            key(ui, 'L')
            val taken = render(ui)
            assertTrue(taken.contains("Took over 6. Left out 5 links with problems; Enter on one says why."), taken)
            assertFalse(taken.contains("Press L"), taken)
            // Pressing it again finds nothing more to take over.
            key(ui, 'l')
            assertEquals(taken, render(ui))
            key(ui, '?')
            assertFalse(render(ui).contains("Take over every shown link you made"), render(ui))
            key(ui, '?')
            save(ui)
            val written = ConfigurationLoader().read(root.resolve("config.json")).file.relocations.map { it.sourcePath }.toSet()
            assertEquals(
                setOf("plain", "abs-link", "rel-link", "under-root", "chain", ".cache", ".local").map { home(root, it) }.toSet(), written,
            )
            val plan = ConfigurationEvaluation().loadRequired(root.resolve("config.json")).plan
            assertTrue(plan.relocations.filter { it.relocation.sourcePath.fileName.toString() != "plain" }
                .all { it.outcome == RelocationOutcome.CONVERGED }, plan.toString())
        }
        assertEquals(before, tree(root))
    }

    /** `f` counts and shows a row inside a linked parent as found, before and after the parent is taken over. */
    @Test fun aRowInsideALinkedParentIsFound() {
        val root = fixture()
        val before = tree(root)
        SetupDiscoveryFixture().use { workers ->
            val ui = browse(root, workers)
            key(ui, 'f')
            val filtered = render(ui)
            for (path in listOf(".cache/uv", ".cache/pip", ".local/share/uv")) assertTrue(row(filtered, path).contains("○ $path"), filtered)
            // The links and the linked rows: abs-link, rel-link, under-root, chain, the five problem links, and five rows
            // inside .cache and .local; .cache/example is hidden as usually not needed. `plain` is in the configuration.
            assertTrue(filtered.contains("14 found on this machine, plus 1 in your configuration"), filtered)
            choose(ui, ".cache/uv"); key(ui, ' ')
            assertTrue(row(render(ui), ".cache/pip").contains("● .cache/pip"), render(ui))
            ui.app.closeEditor()
        }
        assertEquals(before, tree(root))
    }

    /**
     * The shape dotfiles make: links in the home lead through another link in the home, `~/.local-heavy`, to storage.
     * Browse takes each over with its real path, a linked parent too, and the plan finds them in sync. Their texts
     * stay as the dotfiles wrote them. When `~/.local-heavy` later leads to another disk, the plan blocks them.
     */
    @Test fun linksThroughALinkInTheHomeAreTakenOverWithTheirRealPaths() {
        val root = dotfiles()
        val disk = root.resolve("disk")
        val before = tree(root)
        SetupDiscoveryFixture().use { workers ->
            val ui = browse(root, workers)
            val screen = render(ui)
            assertTrue(screen.contains("3 directories are links you made. Press L to take them over."), screen)
            assertTrue(row(screen, ".cache/JetBrains").contains("○ .cache/JetBrains") && row(screen, ".cache/JetBrains").contains("already a link"), screen)
            assertTrue(row(screen, ".m2/repository").contains("inside ${root.resolve("home/.m2")}, which is a link"), screen)
            choose(ui, ".cache/JetBrains"); enter(ui)
            val details = details(ui)
            assertTrue(details.has("Link: ${home(root, ".cache/JetBrains")} → ${disk.resolve("cache/JetBrains")} (written as ../.local-heavy/cache/JetBrains)"), details)
            assertTrue(details.has("Space takes over this link: Lighten adds it with ${disk.resolve("cache/JetBrains")} as its target."), details)
            escape(ui)
            key(ui, 'L')
            assertTrue(render(ui).contains("Took over 3."), render(ui))
            save(ui)
            assertEquals(before, tree(root))
            val written = ConfigurationLoader().read(root.resolve("config.json")).file.relocations.associate { it.sourcePath to it.targetPath }
            assertEquals(
                mapOf(".cache/JetBrains" to "cache/JetBrains", ".cargo" to "cargo", ".m2" to "m2")
                    .map { (source, target) -> home(root, source) to disk.resolve(target).toString() }.toMap(),
                written,
            )
            val plan = ConfigurationEvaluation().loadRequired(root.resolve("config.json")).plan
            assertTrue(plan.relocations.all { it.outcome == RelocationOutcome.CONVERGED && it.actions.all { a -> a is ReconciliationAction.NoOp } }, plan.toString())
            assertTrue(render(ui).contains("✔ 3 in sync"), render(ui))

            // Another disk behind `~/.local-heavy`, with the same directories: the links now lead elsewhere.
            val other = root.resolve("other-disk")
            for (name in listOf("cache/JetBrains", "cargo", "m2/repository")) Files.createDirectories(other.resolve(name))
            Files.delete(root.resolve("home/.local-heavy"))
            Files.createSymbolicLink(root.resolve("home/.local-heavy"), other)
            val changed = tree(root)
            key(ui, 'r')
            val blocked = ConfigurationEvaluation().loadRequired(root.resolve("config.json")).plan.relocations
            for (relocation in blocked) {
                val action = relocation.actions.single() as ReconciliationAction.Blocked
                val now = other.resolve(disk.relativize(relocation.relocation.targetPath))
                assertTrue(action.reason.toString().contains("links to $now, not to ${relocation.relocation.targetPath}"), action.toString())
            }
            assertTrue(render(ui).contains("[Blocked]"), render(ui))
            assertEquals(changed, tree(root))
        }
    }

    /**
     * Two relocations in the file that overlap each other do not stop a take-over that overlaps neither: Browse offers
     * it and Configuration adds it, with one rule. Saving still reports the overlap.
     */
    @Test fun anOverlapAlreadyInTheDraftDoesNotRefuseATakeOver() {
        val root = fixture()
        Files.createDirectories(root.resolve("home/outer/inner"))
        config(
            root,
            """{"source-path": "${home(root, "outer")}", "target-path": "${root.resolve("local/outer")}"},
               {"source-path": "${home(root, "outer/inner")}", "target-path": "${root.resolve("local/inner")}"}""",
        )
        val before = tree(root)
        SetupDiscoveryFixture().use { workers ->
            val ui = browse(root, workers)
            choose(ui, "abs-link")
            assertTrue(render(ui).contains("Space: Take over"), render(ui))
            key(ui, ' ')
            val taken = render(ui)
            assertTrue(selected(taken, "● abs-link"), taken)
            assertFalse(taken.contains("Not added"), taken)
            key(ui, 'L')
            assertFalse(render(ui).contains("Skipped"), render(ui))
            ui.app.closeEditor()
        }
        assertEquals(before, tree(root))
    }

    /** With no link to take over, Browse shows no `L` line, Help does not list it, and `L` does nothing. */
    @Test fun lDoesNothingWhenNoLinkCanBeTakenOver() {
        val root = fixture()
        Files.writeString(root.resolve("list.json"), """{"directories": [{"path": "broken"}]}""")
        SetupDiscoveryFixture().use { workers ->
            val ui = browse(root, workers)
            // The linked parents come from the built-in fixture list; ignore them so nothing is left to take over.
            choose(ui, ".cache/uv"); key(ui, 'x')
            choose(ui, ".local/share/uv"); key(ui, 'x')
            choose(ui, ".local/share/uv/tools"); key(ui, 'x')
            val before = render(ui)
            assertFalse(before.contains("Press L"), before)
            key(ui, 'L')
            assertEquals(before, render(ui))
            key(ui, '?')
            assertFalse(render(ui).contains("Take over every"), render(ui))
            ui.app.closeEditor()
        }
    }

    /**
     * A take-over that would overlap the configuration, or a linked parent the user ignores, stays `−` and Details
     * say why. Two links to one target: once one is taken over, the other overlaps it.
     */
    @Test fun overlapsAndIgnoredParentsRefuseATakeOver() {
        val root = fixture()
        Files.createSymbolicLink(root.resolve("home/twin"), root.resolve("storage/rel"))
        Files.createDirectories(root.resolve("home/owner"))
        Files.writeString(
            root.resolve("list.json"),
            """{"directories": [{"path": "abs-link"}, {"path": "rel-link"}, {"path": "twin"}, {"path": ".cache/pip"}]}""",
        )
        // .cache/pip is configured, so taking over .cache would contain it. Another relocation already uses abs-link's
        // target, and .local is ignored.
        config(
            root,
            """{"source-path": "${home(root, ".cache/pip")}", "target-path": "${root.resolve("local/pip")}"},
               {"source-path": "${home(root, "owner")}", "target-path": "${root.resolve("storage/abs")}"}""",
            ignored = home(root, ".local"),
        )
        val before = tree(root)
        SetupDiscoveryFixture().use { workers ->
            val ui = browse(root, workers)
            val screen = render(ui)
            val cache = root.resolve("home/.cache")
            assertTrue(row(screen, ".cache/uv").contains("− .cache/uv") && row(screen, ".cache/uv").contains("inside $cache, which is a link"), screen)
            assertTrue(row(screen, "abs-link").contains("− abs-link") && row(screen, "abs-link").contains("link overlaps ${root.resolve("home/owner")}"), screen)
            assertTrue(row(screen, ".local/share/uv").contains("− .local/share/uv"), screen)
            choose(ui, ".cache/uv"); enter(ui)
            assertTrue(details(ui).has("it would overlap ${root.resolve("home/.cache/pip")} in your configuration"), details(ui))
            escape(ui)
            choose(ui, ".local/share/uv"); enter(ui)
            assertTrue(details(ui).has("Lighten can't take over ${root.resolve("home/.local")}: you ignore it."), details(ui))
            escape(ui)
            choose(ui, "twin")
            assertTrue(row(render(ui), "twin").contains("○ twin"), render(ui))
            choose(ui, "rel-link"); key(ui, ' ')
            assertTrue(row(render(ui), "twin").contains("− twin") && row(render(ui), "twin").contains("link overlaps ${root.resolve("home/rel-link")}"), render(ui))
            ui.app.closeEditor()
        }
        assertEquals(before, tree(root))
    }

    /** Space on an app with two directories under one linked parent takes the parent over once, and skips nothing. */
    @Test fun spaceOnAGroupTakesOverEachLinkedParentOnce() {
        val root = fixture()
        val before = tree(root)
        SetupDiscoveryFixture().use { workers ->
            val ui = browse(root, workers)
            // uv has two directories under the linked .local: Space on the app takes the parent over once.
            chooseGroup(ui, "uv")
            key(ui, ' ')
            val screen = render(ui)
            assertFalse(screen.contains("Skipped"), screen)
            assertTrue(row(screen, ".local").contains("● .local"), screen)
            assertTrue(row(screen, ".local/share/uv").contains("which Lighten manages"), screen)
            assertTrue(row(screen, ".cache/uv").contains("which Lighten manages"), screen)
            // Every uv row now moves with a linked parent: the heading's mark is `●`, as theirs is.
            assertTrue(selectedGroup(screen, "● uv", "all managed"), screen)
            save(ui)
            val written = ConfigurationLoader().read(root.resolve("config.json")).file.relocations.map { it.sourcePath }
            assertEquals(listOf(home(root, "plain"), home(root, ".cache"), home(root, ".local")), written)
        }
        assertEquals(before, tree(root))
    }

    /**
     * The link changes after Browse took it over and before the plan: the next check blocks it and names where it
     * points now. Nothing on disk changes, and Lighten never replaces the user's link.
     */
    @Test fun aLinkThatChangesBeforeTheCheckIsBlockedNotReplaced() {
        val root = fixture()
        SetupDiscoveryFixture().use { workers ->
            val ui = browse(root, workers)
            choose(ui, "abs-link"); key(ui, ' ')
            Files.delete(root.resolve("home/abs-link"))
            Files.createSymbolicLink(root.resolve("home/abs-link"), root.resolve("storage/rel"))
            // Checking again keeps the draft: the row stays in it.
            key(ui, 'r'); await(workers, ui)
            assertTrue(row(render(ui), "abs-link").contains("● abs-link"), render(ui))
            val before = tree(root)
            save(ui)
            val plan = ConfigurationEvaluation().loadRequired(root.resolve("config.json")).plan.relocations
                .single { it.relocation.sourcePath.fileName.toString() == "abs-link" }
            val blocked = plan.actions.single() as ReconciliationAction.Blocked
            assertTrue(blocked.reason.toString().contains("links to ${root.resolve("storage/rel")}"), blocked.toString())
            assertTrue(render(ui).contains("[Blocked]"), render(ui))
            assertEquals(before, tree(root))
        }
    }

    private fun fixture(): Path {
        val root = Files.createTempDirectory(temporary, "links-").toRealPath()
        fun dir(path: String) = Files.createDirectories(root.resolve(path))
        fun link(path: String, to: Path) = Files.createSymbolicLink(root.resolve(path), to)
        for (path in listOf("home/plain", "home/real", "local/under-root", "storage/abs", "storage/rel", "storage/end",
            "storage/cache/pip", "storage/uv-elsewhere", "storage/abs-example", "storage/share/uv")) dir(path)
        Files.writeString(root.resolve("storage/file"), "x")
        Files.writeString(root.resolve("storage/abs/payload"), "unchanged")
        link("home/abs-link", root.resolve("storage/abs"))
        link("home/rel-link", Path.of("../storage/rel"))
        link("home/under-root", root.resolve("local/under-root"))
        link("storage/hop", root.resolve("storage/end"))
        link("home/chain", root.resolve("storage/hop"))
        // A chain that ends in the home.
        link("storage/hop-home", root.resolve("home/real"))
        link("home/chain-home", Path.of("../storage/hop-home"))
        link("home/broken", root.resolve("storage/gone"))
        link("home/loop-a", Path.of("loop-b"))
        link("home/loop-b", Path.of("loop-a"))
        link("home/to-file", root.resolve("storage/file"))
        link("home/inside", root.resolve("home/real"))
        // A linked parent with a relative and an absolute link inside it, and one with a link nested inside it.
        link("home/.cache", root.resolve("storage/cache"))
        link("storage/cache/uv", Path.of("../uv-elsewhere"))
        link("storage/cache/example", root.resolve("storage/abs-example"))
        link("home/.local", root.resolve("storage/local"))
        dir("storage/local")
        link("storage/local/share", root.resolve("storage/share"))
        Files.writeString(
            root.resolve("list.json"),
            """
            {"apps": [
              {"name": "Made by hand", "category": "Links",
               "directories": [{"path": "abs-link"}, {"path": "rel-link"}, {"path": "under-root"}, {"path": "chain"}]},
              {"name": "Problems", "category": "Broken",
               "directories": [{"path": "chain-home"}, {"path": "broken"}, {"path": "loop-a"}, {"path": "to-file"}, {"path": "inside"}]}
             ],
             "directories": [{"path": ".cache/pip"}]}
            """.trimIndent(),
        )
        config(root, """{"source-path": "${home(root, "plain")}"}""")
        return root
    }

    /**
     * Links as the maintainer's dotfiles make them: relative links in the home through `~/.local-heavy`, which leads
     * to machine-local storage, `disk`. `~/.m2` is a linked parent, spelled through the same link.
     */
    private fun dotfiles(): Path {
        val root = Files.createTempDirectory(temporary, "dotfiles-").toRealPath()
        for (path in listOf("home/.cache", "local", "disk/cache/JetBrains", "disk/cargo", "disk/m2/repository")) {
            Files.createDirectories(root.resolve(path))
        }
        Files.writeString(root.resolve("disk/cargo/payload"), "unchanged")
        Files.createSymbolicLink(root.resolve("home/.local-heavy"), root.resolve("disk"))
        Files.createSymbolicLink(root.resolve("home/.cache/JetBrains"), Path.of("../.local-heavy/cache/JetBrains"))
        Files.createSymbolicLink(root.resolve("home/.cargo"), Path.of(".local-heavy/cargo"))
        Files.createSymbolicLink(root.resolve("home/.m2"), Path.of(".local-heavy/m2"))
        Files.writeString(
            root.resolve("list.json"),
            """{"directories": [{"path": ".cache/JetBrains"}, {"path": ".cargo"}, {"path": ".m2/repository"}]}""",
        )
        config(root, "")
        return root
    }

    private fun config(root: Path, relocations: String, ignored: String? = null) {
        Files.writeString(
            root.resolve("config.json"),
            """
            {"lighten": {"source-root": "${root.resolve("home")}", "target-root": "${root.resolve("local")}",
              "suggestion-list": "${root.resolve("list.json")}", "relocations": [$relocations]
              ${ignored?.let { ""","ignored-source-paths": ["$it"]""" }.orEmpty()}}}
            """.trimIndent(),
        )
    }

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

    /** The list row that shows `path`, by its mark and path. */
    private fun row(screen: String, path: String): String =
        screen.lines().firstOrNull { Regex("[●○−⊘] " + Pattern.quote(path) + "( |┃)").containsMatchIn(it) }
            ?: throw AssertionError("No row for $path\n$screen")

    /** The disk under `root`, but the file that a save writes. */
    private fun tree(root: Path): List<String> = diskTree(root, except = setOf(root.resolve("config.json")))
}
