package io.github.bigswlittlesw.lighten.tui

import io.github.bigswlittlesw.lighten.application.BothExistRule
import io.github.bigswlittlesw.lighten.application.DecisionChoice
import io.github.bigswlittlesw.lighten.application.PlanBadge
import io.github.bigswlittlesw.lighten.config.WhenAdoptingTarget
import io.github.bigswlittlesw.lighten.config.WhenOnlyTargetExists
import io.github.bigswlittlesw.lighten.config.WhenSourceAndTargetDirectoriesExist
import io.github.bigswlittlesw.lighten.discovery.CandidateObservation
import io.github.bigswlittlesw.lighten.fs.PathState
import io.github.bigswlittlesw.lighten.reconcile.ActionFailure
import io.github.bigswlittlesw.lighten.reconcile.CopyDifference
import io.github.bigswlittlesw.lighten.reconcile.ReconciliationAction
import io.github.bigswlittlesw.lighten.reconcile.SpecialFileKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.file.Path
import java.time.Instant

class WordingTest {
    @ParameterizedTest
    @CsvSource(
        "PROMPT, PROMPT, Ask each time", "PROMPT, DISCARD_SOURCE, Ask each time",
        "ADOPT, PROMPT, 'Keep target, ask about source'", "ADOPT, DISCARD_SOURCE, 'Keep target, delete source'",
        "ADOPT, ARCHIVE_SOURCE, 'Keep target, archive source'", "LEAVE_UNCHANGED, PROMPT, Leave both as they are",
        "DISCARD, ARCHIVE_SOURCE, 'Delete both, start empty'",
    )
    fun bothExist(both: WhenSourceAndTargetDirectoriesExist, adopting: WhenAdoptingTarget, label: String) {
        assertEquals(label, bothExistLabel(BothExistRule.of(both, adopting)))
    }

    @ParameterizedTest
    @CsvSource("PROMPT, Ask each time", "ADOPT_TARGET, 'Keep target, link source'")
    fun onlyTarget(value: WhenOnlyTargetExists, label: String) {
        assertEquals(label, onlyTargetLabel(value))
    }

    @ParameterizedTest
    @CsvSource("PROMPT, Ask each time", "DISCARD_SOURCE, Delete source", "ARCHIVE_SOURCE, Archive source")
    fun adopting(value: WhenAdoptingTarget, label: String) {
        assertEquals(label, adoptingLabel(value))
    }

    @ParameterizedTest
    @CsvSource(
        "ADOPT_TARGET, 'Keep target, link source'", "ADOPT_AND_DISCARD_SOURCE, 'Keep target, delete source'",
        "ADOPT_AND_ARCHIVE_SOURCE, 'Keep target, archive source'", "LEAVE_UNCHANGED, Leave both as they are",
        "DISCARD_BOTH, 'Delete both, start empty'",
    )
    fun choicesReadAsTheRuleValueTheyStandFor(choice: DecisionChoice, label: String) {
        assertEquals(label, choiceLabel(choice))
    }

    @Test
    fun badgesUseTheDesignVocabulary() {
        assertEquals(
            listOf("Choose", "Blocked", "Can't read", "Warning", "Move", "Keep target", "Link", "Archive", "Delete", "Left as is", "In sync"),
            PlanBadge.entries.map(::badgeLabel),
        )
    }

    /** One relocation can create two parent directories, so each step says whose parent it creates. */
    @ParameterizedTest
    @CsvSource(
        "TARGET_PARENT, Create parent of target", "SOURCE_PARENT, Create parent of source",
        "ARCHIVE_ROOT, Create archive directory",
    )
    fun parentStepsSayWhichDirectory(role: ReconciliationAction.EnsureDirectory.Role, label: String) {
        assertEquals(label, actionLabel(ReconciliationAction.EnsureDirectory(Path.of("/home/me"), role)))
    }

    @Test
    fun replaceDialogNamesTheFileAsShownAndSaysCommentsAreLost() {
        assertEquals("Replace ~/.lighten.json?",
            replaceConfigurationTitle(Path.of(System.getProperty("user.home"), ".lighten.json")))
        assertEquals(1, REPLACE_CONFIGURATION_BODY.count { "Comments" in it })
    }

    @Test
    fun browseRowNotesArePlain() {
        val path = Path.of("/home/me/.cache/uv")
        fun note(kind: CandidateObservation.Kind, vararg reasons: CandidateObservation.Reason) = observationNote(
            CandidateObservation(path, kind, null, 1, Instant.EPOCH, reasons.map { CandidateObservation.Diagnostic(path, it, "detail") }),
        )
        assertEquals("checking…", note(CandidateObservation.Kind.PENDING))
        assertEquals("not created yet", note(CandidateObservation.Kind.MISSING))
        assertEquals("already a link", note(CandidateObservation.Kind.LINK))
        assertEquals("not a directory", note(CandidateObservation.Kind.REGULAR_FILE))
        assertEquals("can't read: permission denied", note(CandidateObservation.Kind.INACCESSIBLE, CandidateObservation.Reason.ACCESS_DENIED))
        assertEquals("can't read", note(CandidateObservation.Kind.INACCESSIBLE))
    }

    /** Each link problem fits a row's 40-cell note; the `L` line and its result say what happened in plain words. */
    @Test
    fun takingOverLinksIsSaidPlainly() {
        // A link to a directory outside the root can be taken over, so it has no problem note.
        val notes = CandidateObservation.Link.Target.entries.filter { it != CandidateObservation.Link.Target.DIRECTORY }
            .map(::linkTargetNote)
        assertEquals(
            listOf(
                "link points inside your home", "link points to another link", "link points to a file", "link is broken",
                "link target is unclear", "can't read where the link points",
            ),
            notes,
        )
        assertTrue((notes + insideLink("~/.cache") + insideManaged("~/.cache")).all { it.length <= 40 }, notes.toString())
        assertEquals("1 directory is a link you made. Press L to take it over.", linksYouMade(1))
        assertEquals("3 directories are links you made. Press L to take them over.", linksYouMade(3))
        assertEquals("Took over 2.", tookOverLinks(2, listOf(), 0))
        assertEquals(
            "Took over 1. Skipped 1 that overlaps ~/a. Left out 2 links with problems; Enter on one says why.",
            tookOverLinks(1, listOf("~/a"), 2),
        )
        assertEquals("Took over 0. Left out 1 link with a problem; Enter on it says why.", tookOverLinks(0, listOf(), 1))
    }

    @Test
    fun theInSyncCountSaysHowToRevealOrHide() {
        assertEquals("Relocations · c: show 1 in sync", relocationsTitle(1, shown = false))
        assertEquals("Relocations · c: hide 12 in sync", relocationsTitle(12, shown = true))
        assertEquals("Relocations", relocationsTitle(0, shown = false))
    }

    /**
     * One sentence per failure kind: what is there, what Lighten expected, and what to do. Results show nothing
     * else for a failure, so each keeps the executor's paths and the system's reason. Home shows as `~`.
     */
    @Test
    fun failedStepsInPlainWords() {
        for ((failure, words) in failureKinds()) {
            assertEquals(words, failureWords(failure, CONFIG), failure.toString())
        }
    }

    @Test
    fun aSkippedSocketIsNamedAndSeveralAreCounted() {
        val socket = HOME.resolve(".local/share/zed/zed-stable.sock")
        assertEquals("Skipped ~/.local/share/zed/zed-stable.sock; programs recreate it.", skippedSockets(listOf(socket)))
        assertEquals("Skipped 3 sockets; programs recreate them.", skippedSockets(listOf(socket, socket, socket)))
    }

    companion object {
        private val HOME: Path = Path.of(System.getProperty("user.home"))
        val CONFIG: Path = HOME.resolve(".lighten.json")

        /** Every failure kind, with its sentence; [ApplyViewTest] renders them too. */
        fun failureKinds(): List<Pair<ActionFailure, String>> {
            val archive = Path.of("/scratch/archive/tool-b")
            val source = HOME.resolve(".cache/tool-a")
            val target = Path.of("/scratch/local/tool-a")
            return listOf(
                ActionFailure.Drift(archive, PathState.ABSENT, PathState.FILE) to "/scratch/archive/tool-b already " +
                    "exists as a file. Lighten expected nothing there. Move or remove it.",
                ActionFailure.Drift(target, PathState.ABSENT, PathState.DIRECTORY) to "/scratch/local/tool-a already " +
                    "exists as a directory. Lighten expected nothing there. Move or remove it.",
                ActionFailure.Drift(source, PathState.DIRECTORY, PathState.ABSENT) to
                    "~/.cache/tool-a no longer exists. Lighten expected a directory there.",
                ActionFailure.Drift(Path.of("/scratch/archive"), PathState.DIRECTORY, PathState.SYMLINK) to
                    "/scratch/archive is a link. Lighten expected a directory there.",
                ActionFailure.Drift(source, PathState.SYMLINK, PathState.OTHER) to
                    "~/.cache/tool-a is a special file. Lighten expected a link there.",
                ActionFailure.Drift(source, PathState.DIRECTORY, PathState.INACCESSIBLE) to
                    "~/.cache/tool-a can't be read. Lighten expected a directory there. Check its permissions.",
                ActionFailure.LinkChanged(source, target, Path.of("/elsewhere")) to
                    "~/.cache/tool-a now links to /elsewhere. Lighten expected it to link to /scratch/local/tool-a.",
                ActionFailure.StagingElsewhere(Path.of("/scratch/local/.staging"), target) to "The staging directory " +
                    "/scratch/local/.staging is not on the same filesystem as /scratch/local/tool-a, so Lighten can't " +
                    "move the copy there in one step. Set the staging-root setting in ~/.lighten.json to a directory on " +
                    "the target's filesystem.",
                ActionFailure.NoPosixPermissions(Path.of("/mnt/usb")) to "/mnt/usb is on a filesystem without Unix " +
                    "permissions, so Lighten can't keep the directory's permissions when it copies it. Use a location " +
                    "on a filesystem that has them.",
                ActionFailure.Busy(target, here = false) to
                    "Another Lighten is moving a directory to /scratch/local/tool-a. Wait for it to finish.",
                ActionFailure.Busy(target, here = true) to "Lighten is already moving another directory to /scratch/local/tool-a.",
                ActionFailure.CopyChanged(source.resolve("index.db"), CopyDifference.FILE_DIFFERS) to "~/.cache/tool-a/" +
                    "index.db changed while Lighten was copying it (the copied file doesn't match), so Lighten threw " +
                    "the copy away and moved nothing. Close any app that uses it.",
                ActionFailure.CopyChanged(source.resolve("sub"), CopyDifference.MISSING_DIRECTORY) to "~/.cache/tool-a/sub " +
                    "changed while Lighten was copying it (the directory is missing from the copy), so Lighten threw the " +
                    "copy away and moved nothing. Close any app that uses it.",
                ActionFailure.CopyChanged(source.resolve("current"), CopyDifference.LINK_DIFFERS) to "~/.cache/tool-a/" +
                    "current changed while Lighten was copying it (the copied link points elsewhere), so Lighten threw " +
                    "the copy away and moved nothing. Close any app that uses it.",
                ActionFailure.CopyChanged(source.resolve("lock"), CopyDifference.EXTRA_ENTRY) to "~/.cache/tool-a/lock " +
                    "changed while Lighten was copying it (the copy has it, but the source no longer does), so Lighten " +
                    "threw the copy away and moved nothing. Close any app that uses it.",
                ActionFailure.Unmovable(source.resolve("ipc"), SpecialFileKind.NAMED_PIPE) to "~/.cache/tool-a/ipc is " +
                    "a named pipe; Lighten can't move it, so it threw the copy away and moved nothing. Remove it, or " +
                    "move this directory yourself.",
                ActionFailure.Unmovable(source.resolve("tty"), SpecialFileKind.DEVICE) to "~/.cache/tool-a/tty is " +
                    "a device file; Lighten can't move it, so it threw the copy away and moved nothing. Remove it, or " +
                    "move this directory yourself.",
                ActionFailure.PermissionsNotKept(source) to "The copy of ~/.cache/tool-a didn't keep its permissions, " +
                    "so Lighten threw the copy away and moved nothing. Check that the target's filesystem keeps Unix " +
                    "permissions.",
                ActionFailure.PermissionsNotRestored(target, "Operation not permitted") to "Lighten copied the directory " +
                    "to /scratch/local/tool-a but couldn't set the copy's permissions back: operation not permitted. " +
                    "The source is still in place. Give /scratch/local/tool-a the source's permissions.",
                ActionFailure.DifferentFilesystems(source, archive) to "~/.cache/tool-a and /scratch/archive/tool-b are " +
                    "on different filesystems, so Lighten can't move one to the other in one step. Change the " +
                    "configuration so both are on one filesystem.",
                ActionFailure.AccessDenied(source) to
                    "Lighten isn't allowed to change ~/.cache/tool-a. Check its owner and permissions.",
                ActionFailure.Gone(source) to "~/.cache/tool-a no longer exists. Lighten expected it there.",
                ActionFailure.AlreadyExists(target) to
                    "/scratch/local/tool-a already exists. Lighten expected nothing there. Move or remove it.",
                ActionFailure.Io(target, null, "No space left on device") to
                    "Lighten couldn't change /scratch/local/tool-a: no space left on device.",
                ActionFailure.Io(source, target, "Too many levels of symbolic links") to "Lighten couldn't move or " +
                    "copy ~/.cache/tool-a to /scratch/local/tool-a: too many levels of symbolic links.",
                ActionFailure.Io(null, null, "Interrupted during visual-test delay") to
                    "Lighten couldn't read or change a file: interrupted during visual-test delay.",
            )
        }
    }
}
