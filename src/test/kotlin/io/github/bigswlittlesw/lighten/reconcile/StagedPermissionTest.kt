package io.github.bigswlittlesw.lighten.reconcile

import io.github.bigswlittlesw.lighten.config.Relocation
import io.github.bigswlittlesw.lighten.fs.PathInspector
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.channels.FileChannel
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions

/**
 * Staged publication keeps each directory's nine POSIX permission bits, and refuses targets that cannot store them.
 * Relocated directories often go to shared storage, where wider permissions would expose private data. Files and
 * symlinks are copied as the filesystem copies them.
 */
class StagedPermissionTest {
    @TempDir
    lateinit var temporary: Path

    @ParameterizedTest
    @ValueSource(strings = ["rwx------", "rwxr-x---", "rwxr-xr-x", "r-x------"])
    fun emptySourceRootPublishesWithItsMode(sourceMode: String) {
        val root = posixRoot()
        val source = Files.createDirectory(root.resolve("source"))
        mode(source, sourceMode)
        val target = root.resolve("local/target")

        val result = ReconciliationExecutor().execute(plan(source, target))

        assertTrue(result.succeeded(), result.toString())
        assertMode(target, sourceMode)
        assertTrue(Files.isSymbolicLink(source))
        assertEmpty(target)
        assertOnlyLockLeft(target)
    }

    @ParameterizedTest
    @ValueSource(strings = ["rwx------", "rwxr-x---", "rwxr-xr-x"])
    fun publicationPreservesDirectoryModesAndRetainsFileAndLinkBehavior(rootMode: String) {
        val root = posixRoot()
        val source = Files.createDirectory(root.resolve("source"))
        val nested = Files.createDirectory(source.resolve("nested"))
        val empty = Files.createDirectory(nested.resolve("empty"))
        val shared = Files.createDirectory(source.resolve("shared"))
        val file = Files.writeString(source.resolve("entry"), "private contents")
        val executable = Files.writeString(nested.resolve("executable"), "executable contents")
        val outside = Files.createDirectory(root.resolve("outside"))
        Files.writeString(outside.resolve("keep"), "untouched")
        mode(source, rootMode)
        mode(nested, "rwx--x---")
        mode(empty, "r-x------")
        mode(shared, "rwxr-xr-x")
        mode(file, "rw-------")
        mode(executable, "rwx------")
        mode(outside, "rwx------")
        Files.createSymbolicLink(nested.resolve("relative"), Path.of("../entry"))
        Files.createSymbolicLink(source.resolve("broken"), Path.of("missing"))
        Files.createSymbolicLink(source.resolve("external"), outside)
        val target = root.resolve("local/target")
        val published = BooleanArray(1)
        val executor = ReconciliationExecutor()

        val result = executor.execute(plan(source, target), object : ReconciliationExecutor.ProgressListener {
            override fun finished(relocation: RelocationPlan, action: ReconciliationExecutor.ActionExecution) {
                if (action.action is ReconciliationAction.MigrateDirectoryForPublication
                        && action.status == ReconciliationExecutor.ActionStatus.COMPLETED) {
                    published[0] = true
                    assertTrue(Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS))
                    assertMode(source, rootMode)
                    assertMode(nested, "rwx--x---")
                    assertMode(empty, "r-x------")
                    assertMode(target, rootMode)
                    assertMode(target.resolve("nested"), "rwx--x---")
                    assertMode(target.resolve("nested/empty"), "r-x------")
                    assertMode(target.resolve("shared"), "rwxr-xr-x")
                }
            }
        })

        assertTrue(result.succeeded(), result.toString())
        assertTrue(published[0], "must observe publication before source replacement")
        assertTrue(Files.isSymbolicLink(source))
        assertEquals(target, Files.readSymbolicLink(source))
        assertMode(target.resolve("entry"), "rw-------")
        assertMode(target.resolve("nested/executable"), "rwx------")
        assertEquals("private contents", Files.readString(target.resolve("entry")))
        assertEquals("executable contents", Files.readString(target.resolve("nested/executable")))
        assertEquals(Path.of("../entry"), Files.readSymbolicLink(target.resolve("nested/relative")))
        assertEquals(Path.of("missing"), Files.readSymbolicLink(target.resolve("broken")))
        assertEquals(outside, Files.readSymbolicLink(target.resolve("external")))
        assertEquals("untouched", Files.readString(outside.resolve("keep")))
        assertMode(outside, "rwx------")
        assertEmpty(target.resolve("nested/empty"))
        assertOnlyLockLeft(target)

        val repeated = plan(source, target)
        assertTrue(repeated.actions().all { it is ReconciliationAction.NoOp })
        assertTrue(executor.execute(repeated).succeeded())
        assertMode(target, rootMode)
        assertMode(target.resolve("nested"), "rwx--x---")
        assertMode(target.resolve("nested/empty"), "r-x------")
        assertMode(target.resolve("entry"), "rw-------")
    }

    @Test
    fun copierKeepsDirectoriesOwnerOnlyUntilTheirContentsAreCopied() {
        val root = posixRoot()
        val source = Files.createDirectory(root.resolve("source"))
        val nested = Files.createDirectory(source.resolve("nested"))
        val file = Files.writeString(nested.resolve("entry"), "contents")
        mode(nested, "r-x------")
        mode(source, "rwxr-xr-x")
        val copy = Files.createDirectory(root.resolve("operation")).resolve("copy")
        try {
            // Drive the executor's visitor step by step to observe each intermediate mode.
            val visitor = copyVisitor(source, copy)
            visitor.preVisitDirectory(source, Files.readAttributes(source, BasicFileAttributes::class.java))
            assertMode(copy, "rwx------")
            visitor.preVisitDirectory(nested, Files.readAttributes(nested, BasicFileAttributes::class.java))
            assertMode(copy.resolve("nested"), "rwx------")
            visitor.visitFile(file, Files.readAttributes(file, BasicFileAttributes::class.java))
            assertEquals("contents", Files.readString(copy.resolve("nested/entry")))
            visitor.postVisitDirectory(nested, null)
            assertMode(copy.resolve("nested"), "r-x------")
            assertMode(copy, "rwx------")
            visitor.postVisitDirectory(source, null)
            assertMode(copy, "rwxr-xr-x")
            assertMode(source, "rwxr-xr-x")
            assertMode(nested, "r-x------")
        } finally {
            mode(nested, "rwx------")
            if (Files.exists(copy.resolve("nested"))) {
                mode(copy.resolve("nested"), "rwx------")
            }
        }
    }

    @Test
    fun failedCopyRemovesItsRestrictiveDirectoriesAndKeepsUnownedEntries() {
        val root = posixRoot()
        val source = Files.createDirectory(root.resolve("source"))
        // Several read-only siblings, so some are copied with their final mode before the
        // unreadable one fails, whatever the directory listing order.
        val readOnly = listOf("a", "b", "c", "d", "e").map { name ->
            Files.createDirectory(source.resolve(name)).also { directory ->
                Files.writeString(directory.resolve("entry"), "keep")
                mode(directory, "r-x------")
            }
        }
        val nested = Files.createDirectory(source.resolve("unreadable"))
        Files.writeString(nested.resolve("entry"), "keep")
        mode(source, "rwx------")
        val target = root.resolve("local/target")
        val staging = Files.createDirectories(target.parent.resolve(".lighten-staging"))
        val unrelated = Files.writeString(staging.resolve("unowned"), "keep")
        val planned = plan(source, target)
        mode(nested, "---------")
        try {
            assumeFalse(Files.isReadable(nested), "requires directory read denial, not a privileged process")
            val result = ReconciliationExecutor().execute(planned)
            assertUnpublishedFailure(result, source, target)
            assertMode(source, "rwx------")
            assertMode(nested, "---------")
            readOnly.forEach { directory -> assertMode(directory, "r-x------") }
            assertEquals("keep", Files.readString(unrelated))
            Files.list(staging).use { entries ->
                assertEquals(setOf(unrelated, lockOf(stagedCopy(staging, target))), entries.toList().toSet())
            }
        } finally {
            mode(nested, "rwx------")
            readOnly.forEach { directory -> mode(directory, "rwx------") }
        }
        assertEquals("keep", Files.readString(nested.resolve("entry")))
    }

    @Test
    fun unwritableStagingFailsWithoutChangingSourceOrUnownedEntries() {
        val root = posixRoot()
        val source = Files.createDirectory(root.resolve("source"))
        Files.writeString(source.resolve("entry"), "keep")
        val target = root.resolve("local/target")
        val staging = Files.createDirectories(target.parent.resolve(".lighten-staging"))
        val unrelated = Files.writeString(staging.resolve("unowned"), "keep")
        mode(staging, "r-x------")
        try {
            assumeFalse(Files.isWritable(staging), "requires directory write denial, not a privileged process")
            val result = ReconciliationExecutor().execute(plan(source, target))
            assertUnpublishedFailure(result, source, target)
            assertMode(staging, "r-x------")
            assertEquals("keep", Files.readString(source.resolve("entry")))
            assertEquals("keep", Files.readString(unrelated))
        } finally {
            mode(staging, "rwx------")
        }
    }

    /**
     * The source is set aside and linked before deletion, so the entry it cannot delete stays aside, not at the
     * source. No permissions are changed, and the next plan deletes what was left aside.
     */
    @Test
    fun readOnlyPopulatedSourceIsPublishedReadOnlyAndReportsRecoveryWithoutChmod() {
        val root = posixRoot()
        val source = Files.createDirectory(root.resolve("source"))
        Files.writeString(source.resolve("entry"), "keep")
        mode(source, "r-x------")
        val target = root.resolve("local/target")
        val aside = replacedSourcePath(source, target)
        try {
            assumeFalse(Files.isWritable(source), "requires directory write denial, not a privileged process")
            val result = ReconciliationExecutor().execute(plan(source, target))
            assertFalse(result.succeeded())
            assertEquals(ReconciliationExecutor.ExecutionOutcome.FAILED_RECOVERY,
                    result.relocations.first().outcome())
            assertEquals(target, Files.readSymbolicLink(source))
            assertEquals("keep", Files.readString(aside.resolve("entry")))
            assertEquals("keep", Files.readString(target.resolve("entry")))
            assertMode(aside, "r-x------")
            assertMode(target, "r-x------")
            assertOnlyLockLeft(target)
            assertEquals(listOf(ReconciliationAction.DeleteDirectory(aside)),
                    plan(source, target).relocations.single().actions)
        } finally {
            for (directory in listOf(source, aside, target)) {
                if (Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                    mode(directory, "rwx------")
                }
            }
        }
    }

    /**
     * Whatever an earlier run of the same target left at its copy's name is cleared under the lock. A killed copy
     * leaves finished directories with their final modes and may hold symlinks. A copy never produces `0000`, but
     * clearing must not depend on that.
     */
    @Test
    fun aLeftoverOfTheSameTargetIsClearedBeforeNewPublication() {
        val root = posixRoot()
        val source = Files.createDirectory(root.resolve("source"))
        Files.writeString(source.resolve("entry"), "keep")
        val target = root.resolve("local/target")
        val staging = Files.createDirectories(target.parent.resolve(".lighten-staging"))
        val outside = Files.createDirectory(root.resolve("outside"))
        Files.writeString(outside.resolve("entry"), "keep")
        val copy = Files.createDirectory(stagedCopy(staging, target))
        Files.createSymbolicLink(copy.resolve("link"), outside)
        val readOnly = Files.createDirectory(copy.resolve("read-only"))
        Files.writeString(readOnly.resolve("entry"), "stale")
        Files.createSymbolicLink(readOnly.resolve("broken"), Path.of("missing"))
        val unreadable = Files.createDirectory(readOnly.resolve("unreadable"))
        Files.writeString(unreadable.resolve("entry"), "stale")
        mode(unreadable, "---------")
        mode(readOnly, "r-x------")
        mode(copy, "r-x------")
        mode(outside, "r-x------")
        try {
            assumeFalse(Files.isWritable(copy), "requires directory write denial, not a privileged process")
            val result = ReconciliationExecutor().execute(plan(source, target))
            assertTrue(result.succeeded(), result.toString())
            assertEquals("keep", Files.readString(target.resolve("entry")))
            assertOnlyLockLeft(target)
            assertMode(outside, "r-x------")
            assertEquals("keep", Files.readString(outside.resolve("entry")))
        } finally {
            listOf(copy, readOnly, unreadable, outside).filter(Files::exists).forEach { directory -> mode(directory, "rwx------") }
        }
    }

    /** Clearing touches only the target's own name: other targets' leftovers and unowned entries stay as they are. */
    @Test
    fun clearingLeavesOtherTargetsLeftoversAndUnownedEntriesAlone() {
        val root = posixRoot()
        val source = Files.createDirectory(root.resolve("source"))
        Files.writeString(source.resolve("entry"), "keep")
        val target = root.resolve("local/target")
        val staging = Files.createDirectories(target.parent.resolve(".lighten-staging"))
        val foreign = Files.createDirectory(staging.resolve("foreign"))
        val otherCopy = Files.createDirectory(stagedCopy(staging, root.resolve("local/other")))
        val otherLock = Files.createFile(lockOf(otherCopy))
        val restrictive = listOf(foreign, otherCopy)
        restrictive.forEach { directory ->
            Files.writeString(directory.resolve("entry"), "keep")
            mode(directory, "r-x------")
        }
        try {
            val result = ReconciliationExecutor().execute(plan(source, target))
            assertTrue(result.succeeded(), result.toString())
            restrictive.forEach { directory ->
                assertMode(directory, "r-x------")
                assertEquals("keep", Files.readString(directory.resolve("entry")), directory.toString())
            }
            Files.list(staging).use { entries ->
                assertEquals(setOf(foreign, otherCopy, otherLock, lockOf(stagedCopy(staging, target))), entries.toList().toSet())
            }
        } finally {
            restrictive.forEach { directory -> mode(directory, "rwx------") }
        }
    }

    @Test
    fun partlyFailedClearingIsRetriedOnTheNextRun() {
        val root = posixRoot()
        val source = Files.createDirectory(root.resolve("source"))
        Files.writeString(source.resolve("entry"), "keep")
        val target = root.resolve("local/target")
        val staging = Files.createDirectories(target.parent.resolve(".lighten-staging"))
        val copy = Files.createDirectory(stagedCopy(staging, target))
        Files.writeString(copy.resolve("entry"), "stale")
        Files.createFile(lockOf(copy))
        mode(copy, "r-x------")
        // The copy's contents can be deleted, but the copy cannot be removed from the staging root.
        mode(staging, "r-x------")
        try {
            assumeFalse(Files.isWritable(staging), "requires directory write denial, not a privileged process")
            val failed = ReconciliationExecutor().execute(plan(source, target))
            assertUnpublishedFailure(failed, source, target)
            // The entry that could not be deleted, not deleteRecursively's generic summary.
            assertEquals("permission denied: $copy", failed.relocations.first().actions.first().message)
            assertEmpty(copy)
        } finally {
            mode(staging, "rwx------")
        }

        val retried = ReconciliationExecutor().execute(plan(source, target))

        assertTrue(retried.succeeded(), retried.toString())
        assertEquals("keep", Files.readString(target.resolve("entry")))
        assertOnlyLockLeft(target)
    }

    @Test
    fun failedPublicationDeletesAStagedSymlinkWithoutFollowingIt() {
        val root = posixRoot()
        val outside = Files.createDirectory(root.resolve("outside"))
        val kept = Files.writeString(outside.resolve("entry"), "keep")
        val source = Files.createDirectory(root.resolve("source"))
        Files.createSymbolicLink(source.resolve("link"), outside)
        val target = root.resolve("local/target")
        val staging = Files.createDirectories(target.parent.resolve(".lighten-staging"))
        val planned = plan(source, target)
        // The staged copy, link included, is complete; only the final rename into the parent fails.
        mode(target.parent, "r-x------")
        try {
            assumeFalse(Files.isWritable(target.parent), "requires directory write denial, not a privileged process")
            val result = ReconciliationExecutor().execute(planned)
            assertUnpublishedFailure(result, source, target)
            assertOnlyLockLeft(target)
            assertEquals("keep", Files.readString(kept))
            assertEquals(outside, Files.readSymbolicLink(source.resolve("link")))
        } finally {
            mode(target.parent, "rwx------")
        }
    }

    @Test
    fun targetWithoutPosixPermissionsIsRefusedBeforeAnythingIsStaged() {
        val root = posixRoot()
        val source = Files.createDirectory(root.resolve("source"))
        Files.writeString(source.resolve("entry"), "keep")
        mode(source, "rwx------")
        // ZIP is a local, deterministic provider without the POSIX view, not a supported
        // relocation platform or a stand-in for Windows/NFS publication behavior.
        FileSystems.newFileSystem(temporary.resolve("unsupported.zip"), mapOf("create" to "true")).use { zip ->
            val target = zip.getPath("/local/target")
            assertNull(Files.getFileAttributeView(zip.getPath("/"), PosixFileAttributeView::class.java))

            val result = ReconciliationExecutor().execute(plan(source, target))

            assertUnpublishedFailure(result, source, target)
            val failure = result.relocations.first().actions.first()
            assertEquals("cannot preserve directory permissions: no POSIX permission support at /local", failure.message)
            assertEquals(ActionFailure.NoPosixPermissions(zip.getPath("/local")), failure.failure)
            assertFalse(failure.stateDrift)
            assertTrue(Files.notExists(zip.getPath("/local")), "nothing is created on the refused filesystem")
            assertMode(source, "rwx------")
            assertEquals("keep", Files.readString(source.resolve("entry")))
        }
    }

    @Test
    fun stagingRootOnAnotherFilesystemIsRefusedBeforeAnythingIsStaged() {
        val root = posixRoot()
        val source = Files.createDirectory(root.resolve("source"))
        Files.writeString(source.resolve("entry"), "keep")
        val target = root.resolve("local/target")
        FileSystems.newFileSystem(temporary.resolve("staging.zip"), mapOf("create" to "true")).use { zip ->
            val staging = zip.getPath("/staging")
            val inspector = PathInspector()
            val planned = ReconciliationPlanner().plan(listOf(RelocationState(Relocation(source, target, stagingRoot = staging),
                    inspector.inspect(source), inspector.inspect(target))))

            val result = ReconciliationExecutor().execute(planned)

            assertUnpublishedFailure(result, source, target)
            val failure = result.relocations.first().actions.first()
            assertEquals("staging root is not on the target filesystem: /staging", failure.message)
            assertEquals(ActionFailure.StagingElsewhere(staging, target), failure.failure)
            assertFalse(failure.stateDrift)
            assertTrue(Files.notExists(staging))
            assertTrue(Files.notExists(target.parent), "nothing is created before the refusal")
        }
    }

    private fun posixRoot(): Path {
        assumeTrue(Files.getFileStore(temporary).supportsFileAttributeView(PosixFileAttributeView::class.java),
                "requires a POSIX filesystem")
        return temporary.toRealPath()
    }

    companion object {
        private fun mode(path: Path, mode: String) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(mode))
        }

        private fun assertMode(path: Path, mode: String) {
            assertEquals(PosixFilePermissions.fromString(mode), Files.getPosixFilePermissions(path), path.toString())
        }

        private fun assertEmpty(path: Path) {
            Files.list(path).use { entries ->
                assertTrue(entries.findAny().isEmpty(), path.toString())
            }
        }

        private fun assertUnpublishedFailure(result: ReconciliationExecutor.ExecutionResult, source: Path, target: Path) {
            val actions = result.relocations.first().actions
            assertEquals(ReconciliationExecutor.ActionStatus.FAILED, actions.first().status, result.toString())
            assertEquals(ReconciliationExecutor.ActionStatus.PENDING, actions.last().status)
            assertEquals(ReconciliationExecutor.ExecutionOutcome.UNRESOLVED, result.relocations.first().outcome())
            assertTrue(Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS))
            assertTrue(Files.notExists(target, LinkOption.NOFOLLOW_LINKS))
        }

        private fun plan(source: Path, target: Path): ReconciliationPlan {
            val inspector = PathInspector()
            return ReconciliationPlanner().plan(listOf(RelocationState(Relocation(source, target),
                    inspector.inspect(source), inspector.inspect(target),
                    replacedSource = inspector.inspect(replacedSourcePath(source, target)))))
        }
    }
}
