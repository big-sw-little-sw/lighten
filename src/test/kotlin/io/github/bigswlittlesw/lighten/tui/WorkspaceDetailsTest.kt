package io.github.bigswlittlesw.lighten.tui

import io.github.bigswlittlesw.lighten.application.ConfigurationEvaluation
import io.github.bigswlittlesw.lighten.application.DecisionChoice
import io.github.bigswlittlesw.lighten.application.LightenSession
import io.github.bigswlittlesw.lighten.fs.PathInspector
import io.github.bigswlittlesw.lighten.fs.PathObservation
import io.github.bigswlittlesw.lighten.fs.PathState
import io.github.bigswlittlesw.lighten.reconcile.replacedSourcePath
import io.github.bigswlittlesw.lighten.tui.WorkspaceViewTest.Companion.render
import io.github.bigswlittlesw.lighten.tui.WorkspaceViewTest.Companion.rightPane
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** Workspace Details for each kind of row, read from the rendered pane. */
class WorkspaceDetailsTest {
    @TempDir lateinit var temporary: Path
    private lateinit var root: Path
    private lateinit var session: LightenSession

    @BeforeEach
    fun fixture() {
        root = temporary.toRealPath()
        val local = Files.createDirectories(root.resolve("local"))
        val home = Files.createDirectories(root.resolve("home"))
        val body = StringBuilder("{\"lighten\": {\"target-root\": \"$local\", \"relocations\": [\n")
        fun add(name: String, extra: String = "") {
            body.append("  {\"source-path\": \"${home.resolve(name)}\", \"target-path\": \"${local.resolve(name)}\"$extra},\n")
        }
        fun both(name: String, extra: String = "") {
            Files.writeString(Files.createDirectories(home.resolve(name)).resolve("file"), "source")
            Files.writeString(Files.createDirectories(local.resolve(name)).resolve("file"), "target")
            add(name, extra)
        }
        Files.writeString(Files.createDirectories(home.resolve("move")).resolve("file"), "source")
        add("move")
        add("link")
        Files.createSymbolicLink(home.resolve("synced"), Files.createDirectories(local.resolve("synced")))
        add("synced")
        both("choose")
        both("archived", ", \"when-source-and-target-directories-exist\": \"adopt\", \"when-adopting-target\": \"archive-source\"")
        both("kept", ", \"when-source-and-target-directories-exist\": \"adopt\", \"when-adopting-target\": \"discard-source\"")
        Files.writeString(home.resolve("blocked"), "a file")
        add("blocked")
        val archiveFile = Files.writeString(root.resolve("archive-file"), "a file")
        both("filed", ", \"when-source-and-target-directories-exist\": \"adopt\", \"when-adopting-target\": \"archive-source\"" +
            ", \"archive-root\": \"$archiveFile\"")
        // Its source's directory is a file, and its only choice, keeping the target, needs that directory too.
        val directoryFile = Files.writeString(root.resolve("directory-file"), "a file")
        Files.createDirectories(local.resolve("orphan"))
        body.append("  {\"source-path\": \"${directoryFile.resolve("orphan")}\", \"target-path\": \"${local.resolve("orphan")}\", " +
            "\"when-only-target-exists\": \"adopt-target\"},\n")
        add("unreadable")
        Files.createSymbolicLink(home.resolve("leftover"), Files.createDirectories(local.resolve("leftover")))
        Files.createDirectories(replacedSourcePath(home.resolve("leftover"), local.resolve("leftover")))
        add("leftover")
        Files.createSymbolicLink(home.resolve("elsewhere"), Files.createDirectories(root.resolve("other")))
        Files.createDirectories(local.resolve("elsewhere"))
        // A rule that links the source to an existing target still does not replace a link to somewhere else.
        add("elsewhere", ", \"when-only-target-exists\": \"adopt-target\"")
        // A link to a disk that is not mounted: what it points to is missing, and the target exists.
        Files.createSymbolicLink(home.resolve("unmounted"), root.resolve("nas/unmounted"))
        Files.createDirectories(local.resolve("unmounted"))
        add("unmounted")
        val config = Files.writeString(root.resolve("config.json"), body.append("]}}\n"))
        // Permissions cannot make a path unreadable when tests run as root, so inspection says so instead.
        val inspector = PathInspector()
        val inspect = { path: Path ->
            if (path == home.resolve("unreadable")) PathObservation(PathState.INACCESSIBLE) else inspector.inspect(path)
        }
        session = LightenSession(config, evaluator = ConfigurationEvaluation(inspect = inspect))
    }

    @Test
    fun rowsNoRuleGovernsHaveNoDecisionLine() {
        for (name in listOf("move", "link", "synced", "blocked", "unreadable", "leftover", "elsewhere")) {
            val details = details(name)
            assertFalse(details.contains("Decision") || details.contains("Your rule") || details.contains("Your choice"), details)
            assertFalse(details.contains("Archive:"), details)
        }
    }

    @Test
    fun aVerifiedMoveCarriesNoDeletionWarning() {
        val details = details("move")
        assertTrue(details.contains("Will do: copy the source to the target, check the copy, then replace the source"), details)
        assertFalse(details.contains("⚠"), details)
        assertFalse(details.contains("deletes"), details)
    }

    @Test
    fun linkAndInSyncSayWhatTheyDo() {
        assertTrue(details("link").contains("Will do: create an empty target directory and link the source to it."))
        assertTrue(details("synced").contains("Will do: nothing; already in sync."))
    }

    @Test
    fun aRowThatNeedsAChoiceNamesItsRuleAndOffersTheArchiveDestination() {
        val details = details("choose")
        assertTrue(details.contains("Decision: ask each time (your configuration)"), details)
        assertTrue(details.contains("Will do: nothing until you choose."), details)
        assertFalse(details.contains("Archive:"), details)
        assertTrue(squeezed(details).contains(squeezed("Move the source to " + archive("choose"))), details)
    }

    @Test
    fun aChoiceIsTheDecisionAndMovesTheArchiveDestinationToPaths() {
        session.choose(root.resolve("home/choose"), DecisionChoice.ADOPT_AND_ARCHIVE_SOURCE)
        val details = details("choose")
        assertTrue(squeezed(details).contains(squeezed("Decision: keep target, archive source (your choice, this run only)")), details)
        assertTrue(squeezed(details).contains(squeezed("Archive: " + archive("choose"))), details)
        assertTrue(details.contains("Move the source to the archive and"), details)
        assertEquals(1, Regex(Regex.escape(".lighten-archive")).findAll(details).count(), details)
    }

    @Test
    fun aChoiceThatDoesNotArchiveLeavesArchiveOutOfPaths() {
        session.choose(root.resolve("home/choose"), DecisionChoice.ADOPT_AND_DISCARD_SOURCE)
        val details = details("choose")
        assertTrue(squeezed(details).contains(squeezed("Decision: keep target, delete source (your choice, this run only)")), details)
        assertFalse(details.contains("Archive:"), details)
        assertTrue(details.contains(DELETES_DATA), details)
    }

    @Test
    fun aSavedArchiveRuleIsTheDecisionAndShowsTheArchiveInPaths() {
        val details = details("archived")
        assertTrue(squeezed(details).contains(squeezed("Decision: keep target, archive source (your configuration)")), details)
        assertTrue(squeezed(details).contains(squeezed("Archive: " + archive("archived"))), details)
        assertFalse(details.contains("⚠"), details)
    }

    @Test
    fun aSavedDeleteRuleWarnsThatDataIsDeleted() {
        val details = details("kept")
        assertTrue(squeezed(details).contains(squeezed("Decision: keep target, delete source (your configuration)")), details)
        assertTrue(details.contains(DELETES_DATA), details)
        assertFalse(details.contains("Archive:"), details)
    }

    @Test
    fun blockedAndUnreadableRowsShowTheProblemTheyPointTo() {
        val blocked = details("blocked")
        assertTrue(blocked.contains("Will do: nothing until you fix the problem below, then check again."), blocked)
        assertTrue(blocked.contains("Problem: source is a file; relocations require directories."), blocked)
        val unreadable = details("unreadable")
        assertTrue(screen("unreadable", 200, 80).contains("[Can't read]"))
        assertTrue(unreadable.contains("Problem: source cannot be inspected."), unreadable)
        val filed = details("filed")
        assertTrue(filed.contains("Will do: nothing until you fix the problem below, then check again."), filed)
        assertTrue(squeezed(filed).contains(squeezed("Problem: ${root.resolve("archive-file")} is a file, not a directory.")), filed)
        assertTrue(squeezed(filed).contains(squeezed(CHOOSE_AROUND_DIRECTORY)), filed)
    }

    /** Details name both paths and the fix, show the link's current destination under Paths, and offer no choices. */
    @Test
    fun aSourceLinkToSomewhereElseIsBlockedWithHowToFixIt() {
        for (size in listOf(80 to 24, 120 to 30, 200 to 80)) {
            val screen = screen("elsewhere", size.first, size.second)
            assertTrue(screen.lines().any { it.contains("❯") && it.contains("[Blocked]") }, screen)
        }
        val details = details("elsewhere")
        val source = root.resolve("home/elsewhere")
        val other = root.resolve("other")
        assertTrue(details.contains("Will do: nothing until you fix the problem below, then check again."), details)
        assertTrue(squeezed(details).contains(squeezed(
            "Problem: $source links to $other, not to ${root.resolve("local/elsewhere")}. Remove the link, or set " +
                "its target to where it points.",
        )), details)
        assertTrue(squeezed(details).contains(squeezed("Current link destination: $other")), details)
        assertFalse(details.contains("○") || details.contains("●"), details)
        assertFalse(details.contains("points somewhere else"), details)
    }

    /** A broken link to somewhere else is blocked like a working one, and its problem says what is missing. */
    @Test
    fun aBrokenSourceLinkToSomewhereElseIsBlockedAndSaysWhatIsMissing() {
        for (size in listOf(80 to 24, 120 to 30, 200 to 80)) {
            val screen = screen("unmounted", size.first, size.second)
            assertTrue(screen.lines().any { it.contains("❯") && it.contains("[Blocked]") }, screen)
        }
        val details = details("unmounted")
        val source = root.resolve("home/unmounted")
        val missing = root.resolve("nas/unmounted")
        assertTrue(details.contains("Will do: nothing until you fix the problem below, then check again."), details)
        assertTrue(squeezed(details).contains(squeezed(
            "Problem: $source links to $missing, not to ${root.resolve("local/unmounted")}. What it links to does not " +
                "exist now (perhaps an unmounted disk). Mount the disk or fix the link, then check again.",
        )), details)
        assertTrue(squeezed(details).contains(squeezed("Current link destination: $missing")), details)
        assertFalse(details.contains("The source link is broken"), details)
        assertFalse(details.contains("○") || details.contains("●"), details)
    }

    @Test
    fun theChooseAroundLineNeedsAChoiceThatAvoidsTheDirectory() {
        val orphan = details("orphan")
        assertTrue(squeezed(orphan).contains(squeezed("Problem: ${root.resolve("directory-file")} is a file, not a directory.")), orphan)
        assertFalse(squeezed(orphan).contains(squeezed(CHOOSE_AROUND_DIRECTORY)), orphan)
        assertFalse(squeezed(details("blocked")).contains(squeezed(CHOOSE_AROUND_DIRECTORY)))
    }

    @Test
    fun aLeftoverIsDeletedWithAWarningAndItsPath() {
        val details = details("leftover")
        assertTrue(squeezed(details).contains(squeezed("Will do: $LEFT_BEHIND_DELETED")), details)
        assertFalse(details.contains("contents of source and target"), details)
        assertTrue(details.contains("an interrupted replacement left the original source here"), details)
        assertTrue(details.contains(DELETES_DATA), details)
        val leftover = replacedSourcePath(root.resolve("home/leftover"), root.resolve("local/leftover"))
        assertTrue(squeezed(details).contains(squeezed("Left behind: $leftover")), details)
    }

    @Test
    fun theSummaryCountsOnlyRowsThatDeleteData() {
        val model = session.evaluation() as ConfigurationEvaluation.Loaded
        assertEquals("Of these: 1 with warnings · 2 delete data", WorkspaceView.summary(model.items).risks)
    }

    @Test
    fun theDecisionLineShowsAboveTheChoicesAtBothSizes() {
        for (size in listOf(80 to 24, 120 to 30)) {
            val pane = squeezed(rightPane(screen("choose", size.first, size.second), size.first))
            assertTrue(pane.contains(squeezed("Decision: ask each time (your configuration)")), pane)
            assertTrue(pane.indexOf("Decision:") < pane.indexOf("○"), pane)
        }
    }

    /** The Details pane with the list focused, tall enough that nothing scrolls. */
    private fun details(name: String): String = rightPane(screen(name, 200, 80), 200)

    private fun screen(name: String, width: Int, height: Int): String {
        val model = session.evaluation() as ConfigurationEvaluation.Loaded
        val index = WorkspaceView.visibleItems(model, true).indexOfFirst { it.relocation.sourcePath.endsWith(name) }
        val list = WorkspaceView.list().selected(index)
        return render(WorkspaceView.render(session, list, true, false, WORKSPACE_LIST, true, 0, DetailViewport()), width, height)
    }

    private fun archive(name: String): Path {
        val model = session.evaluation() as ConfigurationEvaluation.Loaded
        return checkNotNull(model.observations.first { it.relocation.sourcePath.endsWith(name) }.archiveDestination).path
    }

    /** Wrapped text joins without the spaces at its breaks, so comparisons drop all spaces. */
    private fun squeezed(text: String) = text.replace(" ", "")
}
