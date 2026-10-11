package io.github.bigswlittlesw.lighten.reconcile

import io.github.bigswlittlesw.lighten.config.Relocation
import io.github.bigswlittlesw.lighten.config.WhenAdoptingTarget
import io.github.bigswlittlesw.lighten.config.WhenOnlyTargetExists
import io.github.bigswlittlesw.lighten.config.WhenSourceAndTargetDirectoriesExist
import io.github.bigswlittlesw.lighten.config.defaultArchiveRoot
import io.github.bigswlittlesw.lighten.fs.PathInspector
import io.github.bigswlittlesw.lighten.fs.PathObservation
import io.github.bigswlittlesw.lighten.fs.PathState
import io.github.bigswlittlesw.lighten.fs.PathText
import io.github.bigswlittlesw.lighten.fs.SymlinkTargetAvailability
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ReconciliationPlannerTest {
    @Test
    fun sourceDirectoryWithAbsentTargetRequiresStagedPublication(@TempDir root: Path) {
        val source = Files.createDirectories(root.resolve("home/cache"))

        val plan = plan(Relocation(source, root.resolve("local/cache")))

        assertEquals(RelocationOutcome.CONVERGED, plan.relocations.first().outcome)
        assertTrue(plan.actions().any { it is ReconciliationAction.MigrateDirectoryForPublication })
        assertTrue(plan.actions().any { it is ReconciliationAction.ReplaceDirectoryWithSymlink })
        assertFalse(plan.hasBlockedActions())
    }

    @Test
    fun bothDirectoriesRequireADecisionByDefault(@TempDir root: Path) {
        val source = Files.createDirectories(root.resolve("home/cache"))
        val target = Files.createDirectories(root.resolve("local/cache"))

        val plan = plan(Relocation(source, target))

        assertEquals(RelocationOutcome.UNRESOLVED, plan.relocations.first().outcome)
        assertTrue(plan.hasConflicts())
    }

    @Test
    fun leaveUnchangedIsSuccessfulButNotConverged(@TempDir root: Path) {
        val source = Files.createDirectories(root.resolve("home/cache"))
        val target = Files.createDirectories(root.resolve("local/cache"))

        val plan = plan(Relocation(source, target,
                whenSourceAndTargetDirectoriesExist = WhenSourceAndTargetDirectoriesExist.LEAVE_UNCHANGED))

        assertEquals(RelocationOutcome.UNCHANGED, plan.relocations.first().outcome)
        assertEquals("leave-unchanged", plan.actions().first().type)
    }

    @Test
    fun onlyTargetRequiresAdoptTargetDecision(@TempDir root: Path) {
        val source = root.resolve("home/cache")
        val target = Files.createDirectories(root.resolve("local/cache"))

        val unresolved = plan(Relocation(source, target))
        val adopted = plan(Relocation(source, target, whenOnlyTargetExists = WhenOnlyTargetExists.ADOPT_TARGET))

        assertTrue(unresolved.hasConflicts())
        assertEquals(RelocationOutcome.CONVERGED, adopted.relocations.first().outcome)
    }

    @Test
    fun archiveSourceUsesTheSourceNameUnderTheArchiveRoot(@TempDir root: Path) {
        val source = Files.createDirectories(root.resolve("home/cache"))
        val target = Files.createDirectories(root.resolve("local/cache"))
        val archiveRoot = root.resolve("archive")

        val plan = plan(archiving(source, target, archiveRoot))

        assertEquals(listOf(archiveRoot.resolve("cache")), archiveTargets(plan))
        assertEquals(ReconciliationAction.EnsureDirectory(archiveRoot, ReconciliationAction.EnsureDirectory.Role.ARCHIVE_ROOT), plan.actions().first())
    }

    @Test
    fun archiveSourceAddsASuffixWhenTheNameExistsAndBlocksWhenThatExistsToo(@TempDir root: Path) {
        val source = Files.createDirectories(root.resolve("home/cache"))
        val target = Files.createDirectories(root.resolve("local/cache"))
        val archiveRoot = root.resolve("archive")
        val relocation = archiving(source, target, archiveRoot)
        Files.createDirectories(archiveRoot.resolve("cache"))

        val suffixed = archiveTargets(plan(relocation)).single()

        assertEquals(archiveRoot.resolve("cache-" + sha256Hex(source.toRealPath().toString()).take(8)), suffixed)
        assertEquals(listOf(suffixed), archiveTargets(plan(relocation)), "a re-run gives the same destination")
        Files.createDirectories(suffixed)
        assertEquals(listOf(ReconciliationAction.Blocked(source, PathText("source archive destination already exists"))),
                plan(relocation).actions())
    }

    @Test
    fun relocationsWithTheSameSourceNameArchiveToDifferentDeterministicNames(@TempDir root: Path) {
        val archiveRoot = root.resolve("archive")
        val relocations = listOf("a", "b").map { parent ->
            archiving(Files.createDirectories(root.resolve("$parent/cache")),
                    Files.createDirectories(root.resolve("local/$parent-cache")), archiveRoot)
        }

        val targets = archiveTargets(ReconciliationPlanner().plan(states(relocations)))

        assertEquals(relocations.map { archiveRoot.resolve("cache-" + sha256Hex(it.sourcePath.toRealPath().toString()).take(8)) },
                targets)
        assertEquals(targets, archiveTargets(ReconciliationPlanner().plan(states(relocations))))
        assertEquals(listOf(archiveRoot.resolve("cache")), archiveTargets(plan(relocations.first())),
                "alone, the name is not taken")
    }

    @Test
    fun archiveRootsThatAreTheSameDirectoryShareNames(@TempDir root: Path) {
        val archiveRoot = Files.createDirectories(root.resolve("archive"))
        val alias = Files.createSymbolicLink(root.resolve("alias"), archiveRoot)
        val relocations = listOf(archiveRoot, alias).mapIndexed { i, archive ->
            archiving(Files.createDirectories(root.resolve("$i/cache")),
                    Files.createDirectories(root.resolve("local/$i-cache")), archive)
        }

        val names = archiveTargets(ReconciliationPlanner().plan(states(relocations))).map { it.fileName.toString() }

        assertEquals(2, names.toSet().size, names.toString())
        assertTrue(names.all { it.startsWith("cache-") }, names.toString())
    }

    @Test
    fun archiveSourceIsBlockedWithoutAnInspectedDestination(@TempDir root: Path) {
        val source = Files.createDirectories(root.resolve("home/cache"))
        val target = Files.createDirectories(root.resolve("local/cache"))
        val relocation = archiving(source, target, root.resolve("archive"))
        val inspector = PathInspector()
        val state = RelocationState(relocation, inspector.inspect(source), inspector.inspect(target))

        assertEquals(listOf(ReconciliationAction.Blocked(source, PathText("source archive destination was not inspected"))),
                ReconciliationPlanner().plan(listOf(state)).actions())
    }

    @Test
    fun archiveSourceDefaultsBesideTheSourceAndRejectsAnOverlappingRoot(@TempDir root: Path) {
        val source = Files.createDirectories(root.resolve("home/cache"))
        val target = Files.createDirectories(root.resolve("local/cache"))
        fun archive(archiveRoot: Path) = plan(archiving(source, target, archiveRoot)).relocations.single()

        val default = archive(defaultArchiveRoot(source))
        assertEquals(root.resolve("home/.lighten-archive/cache"),
                default.actions.filterIsInstance<ReconciliationAction.ArchiveDirectory>().single().target)
        for (overlapping in listOf(target.resolve("archive"), source.resolve("archive"))) {
            assertEquals(listOf(ReconciliationAction.Blocked(source, PathText("source archive path overlaps a relocation path"))),
                    archive(overlapping).actions, overlapping.toString())
        }
    }

    @Test
    fun blocksArchivingWhenTheArchiveLocationIsNotADirectory(@TempDir root: Path) {
        val source = Files.createDirectories(root.resolve("home/cache"))
        val target = Files.createDirectories(root.resolve("local/cache"))
        val archiveRoot = root.resolve("archive")
        val cases = mapOf<String, () -> Unit>(
            "is a file, not a directory" to { Files.writeString(archiveRoot, "a file") },
            "is a link, not a directory" to {
                Files.createSymbolicLink(archiveRoot, Files.writeString(root.resolve("file"), "a file"))
            },
            "is a broken link, not a directory" to { Files.createSymbolicLink(archiveRoot, root.resolve("missing")) },
        )
        for ((words, make) in cases) {
            make()

            assertEquals(listOf(ReconciliationAction.Blocked(source, PathText(archiveRoot, " $words"))),
                    plan(archiving(source, target, archiveRoot)).actions(), words)
            Files.delete(archiveRoot)
        }
    }

    @Test
    fun blocksOnTheFirstPathThatIsNotADirectoryAboveAMissingTarget(@TempDir root: Path) {
        val source = Files.createDirectories(root.resolve("home/cache"))
        val file = Files.writeString(root.resolve("local"), "a file")

        val moving = plan(Relocation(source, root.resolve("local/deeper/cache")))
        val creating = plan(Relocation(root.resolve("home/new"), root.resolve("local/deeper/new")))

        for (plan in listOf(moving, creating)) {
            assertEquals(PathText(file, " is a file, not a directory"),
                    plan.actions().filterIsInstance<ReconciliationAction.Blocked>().single().reason)
        }
    }

    @Test
    fun blocksAdoptingWhenTheSourceDirectoryIsAFile(@TempDir root: Path) {
        val target = Files.createDirectories(root.resolve("local/cache"))
        val file = Files.writeString(root.resolve("home"), "a file")

        val plan = plan(Relocation(file.resolve("cache"), target, whenOnlyTargetExists = WhenOnlyTargetExists.ADOPT_TARGET))

        assertEquals(listOf(ReconciliationAction.Blocked(file.resolve("cache"), PathText(file, " is a file, not a directory"))),
                plan.actions())
    }

    @Test
    fun aStagingRootMustBeARealDirectoryButOtherDirectoriesMayBeLinks(@TempDir root: Path) {
        val source = Files.createDirectories(root.resolve("home/cache"))
        val storage = Files.createDirectories(root.resolve("storage"))
        val linked = Files.createSymbolicLink(root.resolve("local"), storage)
        val stagingRoot = root.resolve("staging")

        val throughLinks = plan(Relocation(source, linked.resolve("cache"), stagingRoot = linked.resolve("staging")))
        Files.createSymbolicLink(stagingRoot, Files.createDirectories(root.resolve("elsewhere")))
        val linkedStaging = plan(Relocation(source, linked.resolve("cache"), stagingRoot = stagingRoot))

        assertFalse(throughLinks.hasBlockedActions(), throughLinks.actions().toString())
        assertEquals(PathText("the staging directory must be a real directory, not a link: ", stagingRoot),
                linkedStaging.actions().filterIsInstance<ReconciliationAction.Blocked>().single().reason)
    }

    @Test
    fun aDirectoryInTheWayDoesNotBlockAPlanThatDoesNotNeedIt(@TempDir root: Path) {
        val target = Files.createDirectories(root.resolve("local/cache"))
        val source = Files.createSymbolicLink(Files.createDirectories(root.resolve("home")).resolve("cache"), target)
        Files.writeString(root.resolve("home/.lighten-archive"), "a file")
        Files.writeString(root.resolve("local/.lighten-staging"), "a file")

        assertEquals(listOf(ReconciliationAction.NoOp(source)), plan(Relocation(source, target)).actions())
    }

    @Test
    fun refusesFilesAndTargetSymlinks(@TempDir root: Path) {
        val fileSource = Files.writeString(root.resolve("source-file"), "value")
        val filePlan = plan(Relocation(fileSource, root.resolve("target")))

        val directorySource = Files.createDirectories(root.resolve("home/cache"))
        val target = root.resolve("local/cache")
        Files.createDirectories(target.parent)
        Files.createSymbolicLink(target, Files.createDirectories(root.resolve("other")))
        val symlinkPlan = plan(Relocation(directorySource, target))

        assertTrue(filePlan.hasBlockedActions())
        assertTrue(symlinkPlan.hasBlockedActions())
    }

    /**
     * A broken link to somewhere else, such as a disk that is not mounted, is blocked like a working one, whatever
     * the rules and whether or not the target exists. The reason says that what it points to does not exist now.
     */
    @Test
    fun aBrokenSourceLinkToSomewhereElseIsBlockedWhateverTheRules(@TempDir root: Path) {
        val source = root.resolve("home/cache")
        Files.createDirectories(source.parent)
        val missing = root.resolve("mnt/nas/cache")
        Files.createSymbolicLink(source, missing)
        val target = root.resolve("local/cache")

        for (targetExists in listOf(false, true)) {
            if (targetExists) Files.createDirectories(target)
            for (relocation in listOf(
                Relocation(source, target),
                Relocation(source, target, WhenSourceAndTargetDirectoriesExist.DISCARD, WhenOnlyTargetExists.ADOPT_TARGET),
            )) {
                val plan = plan(relocation)

                assertFalse(plan.hasConflicts())
                assertEquals(listOf(ReconciliationAction.Blocked::class), plan.actions().map { it::class })
                assertEquals(
                    "$source links to $missing, not to $target. What it links to does not exist now (perhaps an " +
                        "unmounted disk). Remove the link, or set its target to where it points",
                    blockReason(plan, 0),
                )
            }
        }
    }

    /** A broken link to the target is handled as before: blocked while the target is missing, else repaired. */
    @Test
    fun aBrokenSourceLinkToTheTargetIsBlockedOnlyWhileTheTargetIsMissing(@TempDir root: Path) {
        val source = root.resolve("home/cache")
        Files.createDirectories(source.parent)
        val target = root.resolve("local/cache")
        Files.createSymbolicLink(source, target)

        assertEquals("broken source link has no target directory", blockReason(plan(Relocation(source, target)), 0))

        // The target can appear between the two observations. The link then looks broken while the target is a directory.
        val brokenLink = PathObservation(PathState.SYMLINK, target, SymlinkTargetAvailability.ABSENT, symlinkText = target)
        val repair = ReconciliationPlanner().plan(listOf(
            RelocationState(Relocation(source, target), brokenLink, PathObservation(PathState.DIRECTORY)),
        ))
        assertEquals(RelocationOutcome.CONVERGED, repair.relocations.single().outcome)
        assertEquals(listOf(ReconciliationAction.ReplaceSymlink(source, target, target)), repair.actions())
    }

    @Test
    fun correctLinksAreTheOnlyNoOpState(@TempDir root: Path) {
        val target = Files.createDirectories(root.resolve("local/cache"))
        val source = root.resolve("home/cache")
        Files.createDirectories(source.parent)
        Files.createSymbolicLink(source, target)

        val plan = plan(Relocation(source, target))

        assertEquals(RelocationOutcome.CONVERGED, plan.relocations.first().outcome)
        assertEquals("no-op", plan.actions().first().type)
    }

    /**
     * No rule replaces a source link to somewhere else. The reason names both paths and both fixes. It names where
     * the link really leads, so the fixture is spelled by real path.
     */
    @Test
    fun aSourceLinkToSomewhereElseIsBlockedWhateverTheRules(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val target = Files.createDirectories(root.resolve("local/cache"))
        val source = root.resolve("home/cache")
        Files.createDirectories(source.parent)
        val other = Files.createDirectories(root.resolve("other"))
        Files.createSymbolicLink(source, other)

        for (relocation in listOf(
            Relocation(source, target),
            Relocation(source, target, WhenSourceAndTargetDirectoriesExist.DISCARD, WhenOnlyTargetExists.ADOPT_TARGET),
        )) {
            val plan = plan(relocation)

            assertEquals(RelocationOutcome.UNRESOLVED, plan.relocations.single().outcome)
            assertFalse(plan.hasConflicts())
            assertEquals(listOf(ReconciliationAction.Blocked::class), plan.actions().map { it::class })
            assertEquals(
                "$source links to $other, not to $target. Remove the link, or set its target to where it points",
                blockReason(plan, 0),
            )
        }
    }

    @Test
    fun blocksInaccessibleSources(@TempDir root: Path) {
        val relocation = Relocation(root.resolve("home/cache"), root.resolve("local/cache"))
        val inaccessible = RelocationState(relocation, PathObservation(PathState.INACCESSIBLE), PathObservation(PathState.ABSENT))

        assertTrue(ReconciliationPlanner().plan(listOf(inaccessible)).hasBlockedActions())
    }

    /** Only the relocations that overlap are blocked, each naming the other. The rest plan as usual. */
    @Test
    fun overlappingRelocationsBlockOnlyThemselvesEachNamingTheOther(@TempDir root: Path) {
        val home = root.resolve("home")
        val local = root.resolve("local")
        val parent = Relocation(home.resolve("parent"), local.resolve("parent"))
        val child = Relocation(home.resolve("parent/child"), local.resolve("child"))
        val apart = Relocation(home.resolve("apart"), local.resolve("apart"))

        val plan = ReconciliationPlanner().plan(states(listOf(parent, apart, child)))

        assertEquals(PathText(home.resolve("parent"), " contains ", home.resolve("parent/child"), ", which is also a relocation").toString(),
            blockReason(plan, 0))
        assertEquals(listOf(ReconciliationAction.EnsureDirectory(local, ReconciliationAction.EnsureDirectory.Role.TARGET_PARENT), ReconciliationAction.CreateDirectory(local.resolve("apart")),
            ReconciliationAction.EnsureDirectory(home, ReconciliationAction.EnsureDirectory.Role.SOURCE_PARENT), ReconciliationAction.CreateSymlink(home.resolve("apart"), local.resolve("apart"))),
            plan.relocations[1].actions)
        assertEquals(PathText(home.resolve("parent/child"), " is inside ", home.resolve("parent"), ", which is also a relocation").toString(),
            blockReason(plan, 2))
        assertTrue(plan.diagnostics.isEmpty())
    }

    @Test
    fun namesTheOtherRelocationsTargetAndASharedTarget(@TempDir root: Path) {
        val home = root.resolve("home")
        val local = root.resolve("local")
        val nested = ReconciliationPlanner().plan(states(listOf(
            Relocation(home.resolve("a"), local.resolve("a")), Relocation(home.resolve("b"), local.resolve("a/b")),
        )))
        assertEquals(PathText(local.resolve("a"), " contains ", local.resolve("a/b"), ", the target of ", home.resolve("b")).toString(),
            blockReason(nested, 0))
        assertEquals(PathText(local.resolve("a/b"), " is inside ", local.resolve("a"), ", the target of ", home.resolve("a")).toString(),
            blockReason(nested, 1))

        val shared = ReconciliationPlanner().plan(states(listOf(
            Relocation(home.resolve("a"), local.resolve("same")), Relocation(home.resolve("b"), local.resolve("same")),
        )))
        assertEquals(PathText(local.resolve("same"), " is also the target of ", home.resolve("b")).toString(), blockReason(shared, 0))
        assertEquals(PathText(local.resolve("same"), " is also the target of ", home.resolve("a")).toString(), blockReason(shared, 1))

        val twice = ReconciliationPlanner().plan(states(listOf(
            Relocation(home.resolve("a"), local.resolve("a")), Relocation(home.resolve("a"), local.resolve("b")),
        )))
        assertEquals(PathText(home.resolve("a"), " is also the source of another relocation").toString(), blockReason(twice, 0))
    }

    @Test
    fun aRelocationWhoseSourceAndTargetOverlapBlocksOnlyItself(@TempDir root: Path) {
        val home = root.resolve("home")
        val plan = ReconciliationPlanner().plan(states(listOf(
            Relocation(home.resolve("cache"), home.resolve("cache/local")),
            Relocation(home.resolve("inside/x"), home.resolve("inside")),
            Relocation(home.resolve("same"), home.resolve("same")),
            Relocation(home.resolve("fine"), root.resolve("local/fine")),
        )))

        assertEquals(PathText("the target ", home.resolve("cache/local"), " is inside the source ", home.resolve("cache")).toString(),
            blockReason(plan, 0))
        assertEquals(PathText("the source ", home.resolve("inside/x"), " is inside the target ", home.resolve("inside")).toString(),
            blockReason(plan, 1))
        assertEquals(PathText("the source ", home.resolve("same"), " and target ", home.resolve("same"), " are the same directory").toString(),
            blockReason(plan, 2))
        assertFalse(plan.relocations[3].actions.any { it is ReconciliationAction.Blocked })
    }

    /**
     * `local` links to `real-local`, so each pair is one place under two spellings. Such overlap blocks only the
     * relocations involved, as overlap as written does.
     */
    @Test
    fun overlapThroughALinkBlocksTheRelocationsInvolved(@TempDir root: Path) {
        val real = Files.createDirectory(root.resolve("real-local"))
        val local = Files.createSymbolicLink(root.resolve("local"), real)
        val home = root.resolve("home")

        val shared = ReconciliationPlanner().plan(states(listOf(
            Relocation(home.resolve("a"), local.resolve("x")), Relocation(home.resolve("b"), real.resolve("x")),
            Relocation(home.resolve("c"), real.resolve("c")),
        )))
        assertEquals(PathText(local.resolve("x"), " is ", real.resolve("x"), " through a link", ", the target of ", home.resolve("b")).toString(),
            blockReason(shared, 0))
        assertEquals(PathText(real.resolve("x"), " is ", local.resolve("x"), " through a link", ", the target of ", home.resolve("a")).toString(),
            blockReason(shared, 1))
        assertFalse(shared.relocations[2].actions.any { it is ReconciliationAction.Blocked })

        val self = ReconciliationPlanner().plan(states(listOf(Relocation(local.resolve("cache"), real.resolve("cache")))))
        assertEquals(PathText("the source ", local.resolve("cache"), " and target ", real.resolve("cache"),
            " are the same directory through a link").toString(), blockReason(self, 0))

        val nested = ReconciliationPlanner().plan(states(listOf(
            Relocation(home.resolve("a"), local.resolve("x")), Relocation(real.resolve("x/inner"), home.resolve("b")),
        )))
        assertEquals(PathText(local.resolve("x"), " contains ", real.resolve("x/inner"), " through a link",
            ", which is also a relocation").toString(), blockReason(nested, 0))
        assertEquals(PathText(real.resolve("x/inner"), " is inside ", local.resolve("x"), " through a link",
            ", the target of ", home.resolve("a")).toString(), blockReason(nested, 1))
    }

    @Test
    fun aStagingRootOnAnotherFilesystemBlocksOnlyAMove(@TempDir root: Path) {
        val source = Files.createDirectories(root.resolve("home/cache"))
        val target = root.resolve("local/cache")
        val staging = root.resolve("local/.staging")
        val move = RelocationState(Relocation(source, target, stagingRoot = staging),
            PathObservation(PathState.DIRECTORY), PathObservation(PathState.ABSENT), stagingElsewhere = true)
        val link = move.copy(relocation = move.relocation.copy(sourcePath = root.resolve("home/new"), targetPath = root.resolve("local/new")),
            source = PathObservation(PathState.ABSENT))

        val plan = ReconciliationPlanner().plan(listOf(move, link))

        assertEquals(PathText("the staging directory ", staging, " is on another filesystem than ", target,
            ", so Lighten can't move the copy there in one step. Set staging-root to a directory on the target's " +
                "filesystem, or remove staging-root to stage beside each target").toString(), blockReason(plan, 0))
        assertFalse(plan.relocations[1].actions.any { it is ReconciliationAction.Blocked })
        assertFalse(ReconciliationPlanner().plan(listOf(move.copy(stagingElsewhere = false))).hasBlockedActions())
    }

    /**
     * Inspection compares file stores as the executor does. `/dev` is its own filesystem on Linux and macOS, so a
     * staging root under it is elsewhere; one beside the target is not.
     */
    @Test
    fun inspectionFindsAStagingRootOnAnotherFilesystem(@TempDir root: Path) {
        val target = root.resolve("local/cache")
        assertTrue(stagingElsewhere(Relocation(root.resolve("home/cache"), target, stagingRoot = Path.of("/dev/lighten-staging"))))
        assertFalse(stagingElsewhere(Relocation(root.resolve("home/cache"), target)))
        assertFalse(stagingElsewhere(Relocation(root.resolve("home/cache"), target, stagingRoot = root.resolve("local/.staging"))))
    }

    companion object {
        private fun archiving(source: Path, target: Path, archiveRoot: Path) = Relocation(source, target,
                WhenSourceAndTargetDirectoriesExist.ADOPT, whenAdoptingTarget = WhenAdoptingTarget.ARCHIVE_SOURCE,
                archiveRoot = archiveRoot)

        private fun plan(relocation: Relocation): ReconciliationPlan = ReconciliationPlanner().plan(states(listOf(relocation)))

        private fun archiveTargets(plan: ReconciliationPlan): List<Path> =
            plan.actions().filterIsInstance<ReconciliationAction.ArchiveDirectory>().map { it.target }

        private fun states(relocations: List<Relocation>): List<RelocationState> =
            inspectRelocations(relocations, PathInspector()::inspect)

        /** The one reason relocation [i] is blocked for, with every path in full. */
        private fun blockReason(plan: ReconciliationPlan, i: Int): String =
            plan.relocations[i].actions.filterIsInstance<ReconciliationAction.Blocked>().single().reason.toString()
    }
}
