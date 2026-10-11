package io.github.bigswlittlesw.lighten.reconcile

import io.github.bigswlittlesw.lighten.config.Relocation
import io.github.bigswlittlesw.lighten.diskTree
import io.github.bigswlittlesw.lighten.fs.PathInspector
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * The planner judges a source link by its real path, not by its text. A link whose real path is the target's is in
 * sync and is never rewritten. A link whose real path changes later blocks, and nothing moves. Every test compares the
 * whole disk before and after.
 */
class LinkRealPathTest {
    @TempDir lateinit var temporary: Path

    /** The dotfiles shape: links in the home lead through `~/.local-heavy` to storage. Apply changes nothing. */
    @Test fun aLinkThroughALinkInTheHomeIsInSyncAndApplyChangesNothing() {
        val base = dotfiles()
        val before = diskTree(base)
        val plan = plan(dotfileRelocations(base))
        for (relocation in plan.relocations) {
            assertEquals(RelocationOutcome.CONVERGED, relocation.outcome, relocation.toString())
            assertEquals(listOf(ReconciliationAction.NoOp(relocation.relocation.sourcePath)), relocation.actions)
        }
        val executor = ReconciliationExecutor()
        assertEquals(listOf<ReconciliationDiagnostic>(), executor.preflight(plan))
        assertTrue(executor.execute(plan).succeeded())
        assertEquals(before, diskTree(base))
    }

    /**
     * A chain is in sync with the directory at its end, through absolute and relative hops. A link in the middle of
     * the chain as the target is not a real directory, so it blocks.
     */
    @Test fun aChainIsInSyncWithItsEndAndBlocksWithALinkAsTarget() {
        val base = temporary.toRealPath()
        val home = Files.createDirectories(base.resolve("home"))
        val end = Files.createDirectories(base.resolve("data/end"))
        Files.createSymbolicLink(base.resolve("data/hop"), end)
        Files.createSymbolicLink(base.resolve("data/relative-hop"), Path.of("end"))
        Files.createSymbolicLink(home.resolve(".nvm"), base.resolve("data/hop"))
        Files.createSymbolicLink(home.resolve(".relative"), Path.of("../data/relative-hop"))
        Files.createSymbolicLink(home.resolve(".middle"), base.resolve("data/hop"))
        val before = diskTree(base)
        val plan = plan(
            listOf(
                Relocation(home.resolve(".nvm"), end), Relocation(home.resolve(".relative"), end),
                Relocation(home.resolve(".middle"), base.resolve("data/hop")),
            ),
        )
        // Two relocations with one target overlap, so plan each alone.
        val alone = listOf(Relocation(home.resolve(".nvm"), end), Relocation(home.resolve(".relative"), end))
            .map { plan(listOf(it)).relocations.single() }
        for (relocation in alone) assertEquals(listOf(ReconciliationAction.NoOp(relocation.relocation.sourcePath)), relocation.actions)
        assertEquals("target is not a real directory or an absent path", blockReason(plan.relocations[2]))
        assertEquals(before, diskTree(base))
    }

    /**
     * `~/.local-heavy` leads to another disk after the save. Where the links lead now, a directory or nothing, is not
     * the target, so each one blocks and names where it leads. Lighten never rewrites them.
     */
    @Test fun aLinkOnTheWayChangedAfterSavingBlocks() {
        val base = dotfiles()
        val relocations = dotfileRelocations(base)
        val other = Files.createDirectories(base.resolve("other-disk/cache/JetBrains")).parent.parent
        repoint(base.resolve("home/.local-heavy"), other)
        val before = diskTree(base)
        val plan = plan(relocations)
        assertTrue(plan.actions().none { it is ReconciliationAction.ReplaceSymlink }, plan.toString())
        val (jetBrains, cargo) = plan.relocations.map(::blockReason)
        assertTrue(jetBrains.startsWith("${relocations[0].sourcePath} links to ${other.resolve("cache/JetBrains")}, not to ${relocations[0].targetPath}."), jetBrains)
        // `other-disk/cargo` does not exist, so the link is broken now; its text names where it points.
        assertTrue(cargo.startsWith("${relocations[1].sourcePath} links to ${base.resolve("home/.local-heavy/cargo")}, not to ${relocations[1].targetPath}. What it links to does not exist now"), cargo)
        assertEquals(before, diskTree(base))
    }

    /** The link on the way changes between review and apply: preflight finds it, so nothing runs. */
    @Test fun aLinkOnTheWayChangedAfterReviewStopsTheApply() {
        val base = dotfiles()
        val plan = plan(dotfileRelocations(base))
        assertTrue(plan.actions().all { it is ReconciliationAction.NoOp }, plan.toString())
        val other = Files.createDirectories(base.resolve("other-disk/cache/JetBrains")).parent.parent
        Files.createDirectories(other.resolve("cargo"))
        repoint(base.resolve("home/.local-heavy"), other)
        val before = diskTree(base)
        val stale = ReconciliationExecutor().preflight(plan)
        assertEquals(dotfileRelocations(base).map { it.sourcePath }, stale.map { it.source })
        assertTrue(stale.all { it.code == "STALE_PLAN" }, stale.toString())
        assertEquals(before, diskTree(base))
    }

    /**
     * A relative link whose `..` passes another link: its text, read as written, names the target, but the system
     * finds another directory. The real path decides, so it blocks and names where the system finds it.
     */
    @Test fun aLinkWhoseTextNamesTheTargetButLeadsElsewhereBlocks() {
        val base = temporary.toRealPath()
        val home = Files.createDirectories(base.resolve("home"))
        val target = Files.createDirectories(home.resolve("t"))
        Files.createDirectories(base.resolve("other/sub"))
        val elsewhere = Files.createDirectories(base.resolve("other/t"))
        Files.createSymbolicLink(home.resolve("hop"), base.resolve("other/sub"))
        val source = Files.createSymbolicLink(home.resolve("x"), Path.of("hop/../t"))
        val before = diskTree(base)
        val reason = blockReason(plan(listOf(Relocation(source, target))).relocations.single())
        assertTrue(reason.startsWith("$source links to $elsewhere, not to $target."), reason)
        assertEquals(before, diskTree(base))
    }

    /**
     * `base/home` holds `.local-heavy` → `base/disk`, `.cache/JetBrains` → `../.local-heavy/cache/JetBrains` and
     * `.cargo` → `base/home/.local-heavy/cargo`. `disk/cargo` holds a file.
     */
    private fun dotfiles(): Path {
        val base = temporary.toRealPath()
        val home = Files.createDirectories(base.resolve("home/.cache")).parent
        val disk = Files.createDirectories(base.resolve("disk/cache/JetBrains")).parent.parent
        Files.writeString(Files.createDirectories(disk.resolve("cargo")).resolve("payload"), "unchanged")
        Files.createSymbolicLink(home.resolve(".local-heavy"), disk)
        Files.createSymbolicLink(home.resolve(".cache/JetBrains"), Path.of("../.local-heavy/cache/JetBrains"))
        Files.createSymbolicLink(home.resolve(".cargo"), home.resolve(".local-heavy/cargo"))
        return base
    }

    /** What Browse's take-over writes for [dotfiles]: each link to its real path. */
    private fun dotfileRelocations(base: Path): List<Relocation> = listOf(
        Relocation(base.resolve("home/.cache/JetBrains"), base.resolve("disk/cache/JetBrains")),
        Relocation(base.resolve("home/.cargo"), base.resolve("disk/cargo")),
    )

    private fun repoint(link: Path, to: Path) {
        Files.delete(link)
        Files.createSymbolicLink(link, to)
    }

    private fun plan(relocations: List<Relocation>): ReconciliationPlan =
        ReconciliationPlanner().plan(inspectRelocations(relocations, PathInspector()::inspect))

    private fun blockReason(plan: RelocationPlan): String =
        (plan.actions.single() as ReconciliationAction.Blocked).reason.toString()
}
