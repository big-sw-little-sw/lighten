package io.github.bigswlittlesw.lighten.reconcile

import io.github.bigswlittlesw.lighten.config.WhenAdoptingTarget
import io.github.bigswlittlesw.lighten.config.WhenOnlyTargetExists
import io.github.bigswlittlesw.lighten.config.WhenSourceAndTargetDirectoriesExist
import io.github.bigswlittlesw.lighten.config.intersects
import io.github.bigswlittlesw.lighten.fs.PathState
import io.github.bigswlittlesw.lighten.fs.PathText
import io.github.bigswlittlesw.lighten.fs.RelocationSourceState
import io.github.bigswlittlesw.lighten.fs.SymlinkTargetAvailability
import io.github.bigswlittlesw.lighten.reconcile.ReconciliationAction.EnsureDirectory.Role
import java.nio.file.Path

/** Makes a plan from observations. It never changes the filesystem. */
class ReconciliationPlanner {
    /**
     * Makes one plan per state, in order. Relocations that overlap are blocked, and each reason names the other
     * relocation ([overlapReason]). The other relocations plan as usual.
     */
    fun plan(states: List<RelocationState>): ReconciliationPlan = ReconciliationPlan(
        states.mapIndexed { i, state ->
            overlapReason(i, states)?.let { blocked(state, it) }
                ?: blockedByStagingElsewhere(state, blockedByNotADirectory(state, plan(state)))
        },
        listOf(), states,
    )

    private fun plan(state: RelocationState): RelocationPlan {
        val relocation = state.relocation
        val source = relocation.sourcePath
        val target = relocation.targetPath
        val sourceState = state.sourceState()
        val replacedSourceLeft = state.replacedSource?.state == PathState.DIRECTORY
        if (replacedSourceLeft && sourceState == RelocationSourceState.DIRECTORY) {
            return blocked(state, PathText("an interrupted replacement left the original source at ", replacedSource(state)))
        }
        return when (sourceState) {
            RelocationSourceState.CORRECT_SYMLINK -> when {
                state.target.state != PathState.DIRECTORY -> unsupportedTarget(state)
                replacedSourceLeft -> deleteReplacedSource(state)
                else -> outcome(state, listOf(ReconciliationAction.NoOp(source)))
            }
            RelocationSourceState.ABSENT -> when (state.target.state) {
                PathState.ABSENT -> outcome(
                    state, listOf(
                        ReconciliationAction.EnsureDirectory(target.parent, Role.TARGET_PARENT),
                        ReconciliationAction.CreateDirectory(target),
                        ReconciliationAction.EnsureDirectory(source.parent, Role.SOURCE_PARENT),
                        ReconciliationAction.CreateSymlink(source, target),
                    ),
                )
                PathState.DIRECTORY -> onlyTargetExists(state)
                PathState.FILE, PathState.SYMLINK, PathState.INACCESSIBLE, PathState.OTHER -> unsupportedTarget(state)
            }
            RelocationSourceState.DIRECTORY -> when (state.target.state) {
                PathState.ABSENT -> migrateSourceForPublication(state)
                PathState.DIRECTORY -> bothDirectoriesExist(state)
                PathState.FILE, PathState.SYMLINK, PathState.INACCESSIBLE, PathState.OTHER -> unsupportedTarget(state)
            }
            RelocationSourceState.FILE -> blocked(state, PathText("source is a file; relocations require directories"))
            RelocationSourceState.WRONG_SYMLINK -> blocked(state, wrongLinkReason(state))
            // A link to somewhere else looks broken while its disk is not mounted, so it is blocked like any other.
            RelocationSourceState.BROKEN_SYMLINK -> if (!linksToTarget(state)) blocked(state, wrongLinkReason(state))
            else when (state.target.state) {
                PathState.DIRECTORY ->
                    if (state.source.symlinkText == target) outcome(state, listOf(replacementLink(state)))
                    else blocked(state, writtenOtherwiseReason(state))
                PathState.ABSENT -> blocked(state, PathText("the source link to the target is broken. $BROKEN_LINK_ADVICE"))
                PathState.FILE, PathState.SYMLINK, PathState.INACCESSIBLE, PathState.OTHER -> unsupportedTarget(state)
            }
            RelocationSourceState.INACCESSIBLE -> blocked(state, PathText("source cannot be inspected"))
            RelocationSourceState.OTHER -> blocked(state, PathText("source has an unsupported filesystem state"))
        }
    }
}

/**
 * Returns why relocation [i] of [states] overlaps, or null. Its source can overlap its target, or one of its paths
 * can overlap a path of another relocation, which the reason names.
 *
 * A path inside another path overlaps it, and so does the same path. A target shared by two relocations is one case.
 * Paths compare as written first, then by their real spellings. So `/home/u/x` and `/var/home/u/x` overlap when
 * `/home` links to `/var/home`. The reason names only the first overlap, in configuration order.
 */
private fun overlapReason(i: Int, states: List<RelocationState>): PathText? =
    selfOverlap(states[i]) ?: states.withIndex().filter { it.index != i }
        .firstNotNullOfOrNull { (_, other) -> pairOverlap(states[i], other) }

private fun selfOverlap(state: RelocationState): PathText? {
    val (source, target) = places(state)
    val overlap = overlap(source, target) ?: return null
    val link = overlap.link()
    return when (overlap.relation) {
        Relation.SAME -> PathText("the source ", source.path, " and target ", target.path, " are the same directory$link")
        Relation.CONTAINS -> PathText("the target ", target.path, " is inside the source ", source.path, link)
        Relation.INSIDE -> PathText("the source ", source.path, " is inside the target ", target.path, link)
    }
}

/**
 * The first overlap of [state]'s source or target with [other]'s, naming [other]'s path and what it is: a
 * relocation (its source), or the target of the relocation whose source it names.
 */
private fun pairOverlap(state: RelocationState, other: RelocationState): PathText? {
    val theirs = places(other)
    for (mine in places(state)) {
        for ((j, their) in theirs.withIndex()) {
            val overlap = overlap(mine, their) ?: continue
            val isSource = j == 0
            val what: List<Any> =
                if (isSource) listOf(", which is also a relocation") else listOf(", the target of ", other.relocation.sourcePath)
            val words: List<Any> = when (overlap.relation) {
                Relation.CONTAINS -> listOf(mine.path, " contains ", their.path, overlap.link()) + what
                Relation.INSIDE -> listOf(mine.path, " is inside ", their.path, overlap.link()) + what
                Relation.SAME -> when {
                    overlap.throughLink -> listOf(mine.path, " is ", their.path, overlap.link()) + what
                    isSource -> listOf(mine.path, " is also the source of another relocation")
                    else -> listOf(mine.path, " is also the target of ", other.relocation.sourcePath)
                }
            }
            return PathText(*words.toTypedArray())
        }
    }
    return null
}

/** A relocation path as written and as its real spelling. */
private data class Place(val path: Path, val real: Path)

/** The source, then the target. */
private fun places(state: RelocationState): List<Place> =
    listOf(state.relocation.sourcePath, state.relocation.targetPath).map { Place(it, state.realSpellings[it] ?: it) }

/** How the first path relates to the second: the same, containing it, or inside it. */
private enum class Relation { SAME, CONTAINS, INSIDE }

private data class Overlap(val relation: Relation, val throughLink: Boolean) {
    fun link(): String = if (throughLink) " through a link" else ""
}

/** Compares the paths as written first. Only when they do not overlap as written, compares their real spellings. */
private fun overlap(left: Place, right: Place): Overlap? =
    relation(left.path, right.path)?.let { Overlap(it, throughLink = false) }
        ?: relation(left.real, right.real)?.let { Overlap(it, throughLink = true) }

private fun relation(left: Path, right: Path): Relation? = when {
    left == right -> Relation.SAME
    right.startsWith(left) -> Relation.CONTAINS
    left.startsWith(right) -> Relation.INSIDE
    else -> null
}

/**
 * Blocks [planned] when it copies a directory through a staging root on another filesystem than the target. Inspection
 * finds this ([stagingElsewhere]). The copy cannot then move into place in one step. The executor checks again before
 * it copies.
 */
private fun blockedByStagingElsewhere(state: RelocationState, planned: RelocationPlan): RelocationPlan {
    if (!state.stagingElsewhere) return planned
    val migration = planned.actions.filterIsInstance<ReconciliationAction.MigrateDirectoryForPublication>().firstOrNull()
        ?: return planned
    return blocked(
        state,
        PathText(
            "the staging directory ", migration.effectiveStagingRoot, " is on another filesystem than ", migration.target,
            ", so Lighten can't move the copy there in one step. Set staging-root to a directory on the target's " +
                "filesystem, or remove staging-root to stage beside each target",
        ),
    )
}

/**
 * Blocks [planned] when a directory one of its actions needs is in the way (see [inspectDirectories]), so the apply does not
 * stop at that step. The reason names the first such path, in action order.
 */
private fun blockedByNotADirectory(state: RelocationState, planned: RelocationPlan): RelocationPlan {
    val directory = planned.actions.asSequence().flatMap(::neededDirectories).firstOrNull(state.notDirectories::containsKey)
        ?: return planned
    val inTheWay = state.notDirectories.getValue(directory)
    val stagingRoot = effectiveStagingRoot(state.relocation.targetPath, state.relocation.stagingRoot)
    // Only the staging root must not be a link. Elsewhere a link to a directory is allowed, so a link in the way there
    // points to something that is not a directory. It gets the general reason.
    val linkedStagingRoot = directory == stagingRoot && inTheWay.path == directory && inTheWay.observation.state == PathState.SYMLINK
        && inTheWay.observation.symlinkTargetAvailability != SymlinkTargetAvailability.ABSENT
    return blocked(
        state,
        if (linkedStagingRoot) PathText("the staging directory must be a real directory, not a link: ", directory)
        else notADirectoryReason(inTheWay),
    )
}

/**
 * The directories the executor makes or works in for an action. Every other path an action uses is a source, target,
 * archive destination or replaced source, whose observations the planner has already checked.
 */
private fun neededDirectories(action: ReconciliationAction): List<Path> = when (action) {
    is ReconciliationAction.EnsureDirectory -> listOf(action.path)
    is ReconciliationAction.MigrateDirectoryForPublication -> listOfNotNull(action.target.parent, action.effectiveStagingRoot)
    is ReconciliationAction.CreateDirectory, is ReconciliationAction.ArchiveDirectory,
    is ReconciliationAction.DeleteDirectory, is ReconciliationAction.CreateSymlink,
    is ReconciliationAction.ReplaceDirectoryWithSymlink, is ReconciliationAction.ReplaceSymlink,
    is ReconciliationAction.NoOp, is ReconciliationAction.LeaveUnchanged, is ReconciliationAction.Blocked -> listOf()
}

private fun notADirectoryReason(inTheWay: RelocationState.NotADirectory): PathText {
    val observation = inTheWay.observation
    return PathText(
        inTheWay.path,
        when (observation.state) {
            PathState.FILE -> " is a file, not a directory"
            PathState.SYMLINK ->
                if (observation.symlinkTargetAvailability == SymlinkTargetAvailability.ABSENT) " is a broken link, not a directory"
                else " is a link, not a directory"
            PathState.INACCESSIBLE -> " can't be read, so Lighten can't tell if it is a directory"
            PathState.ABSENT, PathState.DIRECTORY, PathState.OTHER -> " is not a directory"
        },
    )
}

/**
 * A source link to somewhere else is blocked, never replaced: it may belong to another tool, and no rule or choice
 * replaces it. The reason names both paths. When the link leads to an existing directory, it gives both fixes: remove
 * the link, or set the target to where it points. When what it links to is missing, setting the target there would
 * not help, so it gives [BROKEN_LINK_ADVICE]: a disk that is not mounted is the usual cause, and the link is right
 * once it is.
 *
 * The reason does not name the tool that owns the link. Add that when Lighten can recognize links that dotfile
 * managers such as GNU Stow or chezmoi make.
 */
private fun wrongLinkReason(state: RelocationState): PathText {
    // A wrong link is a symlink observation, which always has a link target.
    val pointsTo = state.source.linkDestination!!
    val missing = state.source.symlinkTargetAvailability == SymlinkTargetAvailability.ABSENT
    return PathText(
        state.relocation.sourcePath, " links to ", pointsTo, ", not to ", state.relocation.targetPath, ". " +
            if (missing) BROKEN_LINK_ADVICE else "Remove the link, or set its target to where it points",
    )
}

/**
 * How every reason for a broken source link ends. The link may belong to another tool and be right once its disk is
 * mounted, so the advice never says to point the target at it.
 */
private const val BROKEN_LINK_ADVICE =
    "What it links to does not exist now (perhaps an unmounted disk). Mount the disk or fix the link, then check again"

/**
 * A broken source link whose text, read as written, names the target, but is not the target exactly: a relative link,
 * or one whose `..` passes another link, which the system resolves elsewhere. Lighten writes a link as the target
 * exactly, so this link is not one it made, and it is never replaced.
 */
private fun writtenOtherwiseReason(state: RelocationState): PathText = PathText(
    // A symlink observation always has its text.
    state.relocation.sourcePath, " is a broken link written as ", state.source.symlinkText!!, ", not as ",
    state.relocation.targetPath, ", so Lighten does not replace it. $BROKEN_LINK_ADVICE",
)

/**
 * Whether a broken source link's text names the target. A broken link has no real path, so only its text can say
 * where it points.
 */
private fun linksToTarget(state: RelocationState): Boolean =
    state.source.symlinkTarget == state.relocation.targetPath.toAbsolutePath().normalize()

private fun migrateSourceForPublication(state: RelocationState): RelocationPlan {
    val relocation = state.relocation
    return outcome(
        state, listOf(
            ReconciliationAction.MigrateDirectoryForPublication(
                relocation.sourcePath, relocation.targetPath, relocation.stagingRoot,
            ),
            ReconciliationAction.ReplaceDirectoryWithSymlink(relocation.sourcePath, relocation.targetPath),
        ),
    )
}

private fun replacedSource(state: RelocationState): Path =
    replacedSourcePath(state.relocation.sourcePath, state.relocation.targetPath)

/** See [replacedSourcePath] for why deleting it is safe. */
private fun deleteReplacedSource(state: RelocationState): RelocationPlan {
    val path = replacedSource(state)
    val warning = ReconciliationDiagnostic(
        ReconciliationDiagnostic.Severity.WARNING, path,
        "REPLACED_SOURCE_LEFT", PathText("an interrupted replacement left the original source here; it will be deleted"),
    )
    val actions = listOf(ReconciliationAction.DeleteDirectory(path))
    return RelocationPlan(state.relocation, RelocationOutcome.CONVERGED, actions, listOf(warning))
}

private fun onlyTargetExists(state: RelocationState): RelocationPlan =
    if (state.relocation.whenOnlyTargetExists == WhenOnlyTargetExists.ADOPT_TARGET)
        outcome(
            state, listOf(
                ReconciliationAction.EnsureDirectory(state.relocation.sourcePath.parent, Role.SOURCE_PARENT),
                ReconciliationAction.CreateSymlink(state.relocation.sourcePath, state.relocation.targetPath),
            ),
        )
    else conflict(state, state.relocation.targetPath, "a real target directory requires an adopt-target decision")

private fun bothDirectoriesExist(state: RelocationState): RelocationPlan =
    when (state.relocation.whenSourceAndTargetDirectoriesExist) {
        WhenSourceAndTargetDirectoriesExist.PROMPT -> conflict(
            state, state.relocation.sourcePath,
            "both source and target directories exist; choose which directory is authoritative",
        )
        WhenSourceAndTargetDirectoriesExist.ADOPT -> adoptTarget(state)
        WhenSourceAndTargetDirectoriesExist.LEAVE_UNCHANGED -> unchanged(state)
        WhenSourceAndTargetDirectoriesExist.DISCARD -> discardDirectories(state)
    }

private fun adoptTarget(state: RelocationState): RelocationPlan =
    when (state.relocation.whenAdoptingTarget) {
        WhenAdoptingTarget.PROMPT -> conflict(
            state, state.relocation.sourcePath, "adopting the target requires a source disposition",
        )
        WhenAdoptingTarget.DISCARD_SOURCE -> outcome(
            state, listOf(
                ReconciliationAction.ReplaceDirectoryWithSymlink(state.relocation.sourcePath, state.relocation.targetPath),
            ),
        )
        WhenAdoptingTarget.ARCHIVE_SOURCE -> archiveSource(state)
    }

private fun archiveSource(state: RelocationState): RelocationPlan {
    val relocation = state.relocation
    val destination = state.archiveDestination ?: return blocked(state, PathText("source archive destination was not inspected"))
    val archivePath = destination.path
    if (intersects(archivePath, relocation.sourcePath) || intersects(archivePath, relocation.targetPath)) {
        return blocked(state, PathText("source archive path overlaps a relocation path"))
    }
    if (destination.observation.state != PathState.ABSENT) {
        return blocked(state, PathText("source archive destination already exists"))
    }
    return outcome(
        state, listOf(
            ReconciliationAction.EnsureDirectory(archivePath.parent, Role.ARCHIVE_ROOT),
            ReconciliationAction.ArchiveDirectory(relocation.sourcePath, archivePath),
            ReconciliationAction.CreateSymlink(relocation.sourcePath, relocation.targetPath),
        ),
    )
}

private fun unchanged(state: RelocationState): RelocationPlan = RelocationPlan(
    state.relocation, RelocationOutcome.UNCHANGED,
    listOf(ReconciliationAction.LeaveUnchanged(state.relocation.sourcePath)), listOf(),
)

private fun discardDirectories(state: RelocationState): RelocationPlan {
    val relocation = state.relocation
    val actions = listOf(
        ReconciliationAction.DeleteDirectory(relocation.sourcePath),
        ReconciliationAction.DeleteDirectory(relocation.targetPath),
        ReconciliationAction.EnsureDirectory(relocation.targetPath.parent, Role.TARGET_PARENT),
        ReconciliationAction.CreateDirectory(relocation.targetPath),
        ReconciliationAction.EnsureDirectory(relocation.sourcePath.parent, Role.SOURCE_PARENT),
        ReconciliationAction.CreateSymlink(relocation.sourcePath, relocation.targetPath),
    )
    val warning = ReconciliationDiagnostic(
        ReconciliationDiagnostic.Severity.WARNING, relocation.sourcePath,
        "DIRECTORIES_DISCARDED", PathText("discard will permanently remove both directory trees"),
    )
    return RelocationPlan(relocation, RelocationOutcome.CONVERGED, actions, listOf(warning))
}

private fun unsupportedTarget(state: RelocationState): RelocationPlan =
    blocked(state, PathText("target is not a real directory or an absent path"))

private fun outcome(state: RelocationState, actions: List<ReconciliationAction>): RelocationPlan =
    RelocationPlan(state.relocation, RelocationOutcome.CONVERGED, actions, listOf())

/**
 * Only planned for a broken symlink to the target while the target is a directory, so the source observation has a
 * link target.
 */
private fun replacementLink(state: RelocationState): ReconciliationAction.ReplaceSymlink =
    ReconciliationAction.ReplaceSymlink(
        state.relocation.sourcePath, state.relocation.targetPath, state.source.symlinkTarget!!,
    )

private fun conflict(state: RelocationState, path: Path, reason: String): RelocationPlan = RelocationPlan(
    state.relocation, RelocationOutcome.UNRESOLVED, listOf(), listOf(), ReconciliationConflict(path, reason),
)

private fun blocked(state: RelocationState, reason: PathText): RelocationPlan = RelocationPlan(
    state.relocation, RelocationOutcome.UNRESOLVED,
    listOf(ReconciliationAction.Blocked(state.relocation.sourcePath, reason)), listOf(),
)
