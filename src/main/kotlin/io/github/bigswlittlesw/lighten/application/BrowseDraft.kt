package io.github.bigswlittlesw.lighten.application

import io.github.bigswlittlesw.lighten.config.Relocation
import io.github.bigswlittlesw.lighten.config.additionProblem
import io.github.bigswlittlesw.lighten.discovery.CandidateDiscovery
import io.github.bigswlittlesw.lighten.discovery.CandidateObservation
import io.github.bigswlittlesw.lighten.discovery.CandidateObservation.Link
import java.nio.file.Path

/**
 * What Browse shows for one Configuration draft: the suggestions found for its source root, joined with its
 * relocations by source path. It is built from the draft whenever Browse needs it, so it never goes stale, and
 * discovery never edits the draft.
 *
 * `relocations` holds each relocation's source and target as the loader resolves them, each null while it cannot be
 * resolved (a row still being typed). `kept` holds sources taken out of the draft during this Browse visit: they stay
 * listed, so a directory no list suggests can be added back. `ignored` holds the draft's ignored sources: they are
 * always listed, so they can be seen and no longer ignored, and they can't be added.
 */
class BrowseDraft(
    val sourceRoot: Path, relocations: List<Paths>, val discovery: CandidateDiscovery.Result?, kept: Set<Path> = setOf(),
    ignored: Set<Path> = setOf(),
) {
    private val relocations: List<Paths> = relocations.toList()
    private val sources: List<Path?> = relocations.map { it.source }
    private val kept: Set<Path> = kept.toSet()
    private val ignored: Set<Path> = ignored.toSet()
    // The links that block suggestions under them, by the link's path. A linked parent is listed only once it is in
    // the draft or kept, so its own row finds its link here.
    private val linkedParents: Map<Path, Link> = discovery?.candidates.orEmpty()
        .filter { it.observation.kind == CandidateObservation.Kind.BLOCKED_BY_LINK }
        .mapNotNull { it.observation.link }.associateBy { it.path }

    /**
     * The draft's relocations in order, then the suggestions not in it, then the kept and ignored sources neither
     * lists.
     */
    fun entries(): List<Entry> {
        val candidates = discovery?.candidates.orEmpty().associateBy { it.catalog.sourcePath }
        val rows = sources.mapIndexed { row, path -> Entry(path, row, path?.let { candidates[it] }) }
        val used = sources.filterNotNull().toSet()
        return rows +
            candidates.filterKeys { it !in used }.map { (path, candidate) -> Entry(path, null, candidate, path in ignored) } +
            (kept + ignored).filter { it !in used && it !in candidates }.map { Entry(it, null, null, it in ignored) }
    }

    /**
     * What Space can do for `entry`, which every Browse row, mark, note and key follows. A link is never added as a
     * directory: it is taken over, a relocation from the link to its real path, which the planner then finds in sync.
     * Taking over changes nothing on disk, and links inside a linked directory stay as they are.
     */
    fun status(entry: Entry): Status {
        entry.row?.let { return Status.InDraft(it) }
        if (entry.ignored) return Status.Ignored
        // Only an entry with no row is listed by its path, so the path is set.
        val path = checkNotNull(entry.sourcePath)
        val link = link(entry)
        if (link != null && link.path != path) {
            sources.indexOf(link.path).takeIf { it >= 0 }?.let { return Status.MovesWith(it, link.path) }
        }
        if (addable(entry, path)) return Status.CanAdd(path, null)
        if (link == null || link.path in sources) return Status.CannotAdd
        val problem = when {
            link.target != Link.Target.DIRECTORY -> Problem.Target(link.target)
            link.path in ignored -> Problem.Ignored
            else -> overlap(Relocation(link.path, link.pointsTo))
        }
        return if (problem == null) Status.CanAdd(link.path, link) else Status.LinkProblem(link, problem)
    }

    /** The link at `entry`'s path or at the parent that holds it, as the last check saw it. */
    fun link(entry: Entry): Link? = entry.discovery?.observation?.link ?: entry.sourcePath?.let { linkedParents[it] }

    /**
     * A suggestion seen as a directory or as missing can be added as a directory. A kept source can be added back: it
     * was in the draft a moment ago, unless it is a linked parent, which is taken over instead.
     */
    private fun addable(entry: Entry, path: Path): Boolean {
        val candidate = entry.discovery ?: return path in kept && path !in linkedParents
        return when (candidate.observation.kind) {
            CandidateObservation.Kind.DIRECTORY, CandidateObservation.Kind.MISSING -> true
            CandidateObservation.Kind.PENDING, CandidateObservation.Kind.LINK, CandidateObservation.Kind.REGULAR_FILE,
            CandidateObservation.Kind.OTHER, CandidateObservation.Kind.INACCESSIBLE,
            CandidateObservation.Kind.BLOCKED_BY_LINK, CandidateObservation.Kind.BLOCKED_BY_NON_DIRECTORY,
            CandidateObservation.Kind.UNKNOWN,
            -> false
        }
    }

    /**
     * The rule Configuration applies when it adds the relocation ([additionProblem]), so a row offered here is never
     * refused there.
     *
     * shortcut: a take-over's target is a real path, but the draft's paths compare as written. So a draft target
     * spelled through a link that is the same place shows as blocked only on the Workspace after saving. Add a
     * real-path check here when users report it.
     */
    private fun overlap(new: Relocation): Problem.Overlap? {
        val existing = relocations.mapNotNull { paths -> paths.source?.let { source -> paths.target?.let { Relocation(source, it) } } }
        return additionProblem(existing, new)?.let { Problem.Overlap(it.source.takeIf { other -> other != new.sourcePath }) }
    }

    /**
     * `row` is the relocation's index in the draft, or null for a suggestion not in it. `ignored` is never true for a
     * relocation: a draft that lists a path as both is refused on save.
     */
    data class Entry(
        val sourcePath: Path?, val row: Int?, val discovery: CandidateDiscovery.Candidate?, val ignored: Boolean = false,
    )

    /** A draft relocation's source and target as the loader resolves them, each null while it cannot be resolved. */
    data class Paths(val source: Path?, val target: Path?)

    sealed interface Status {
        /** The relocation at `row` in the draft. */
        data class InDraft(val row: Int) : Status

        data object Ignored : Status

        /** Inside the linked `parent`, the relocation at `row` in the draft: it moves with it, so it is not added alone. */
        data class MovesWith(val row: Int, val parent: Path) : Status

        /**
         * Space adds a relocation from `source`. With a `link`, it takes the link over, `source` is the link's path and
         * the target is its real path; without one, the roots derive the target.
         */
        data class CanAdd(val source: Path, val link: Link?) : Status

        /** The link at or above the entry can't be taken over. */
        data class LinkProblem(val link: Link, val problem: Problem) : Status

        /** A file, a path that can't be read, or one not checked yet. */
        data object CannotAdd : Status
    }

    /** Why a link can't be taken over. */
    sealed interface Problem {
        /** The link's real path is not a directory outside the source root. */
        data class Target(val target: Link.Target) : Problem {
            init {
                require(target != Link.Target.DIRECTORY) { "A link to a directory outside the root can be taken over" }
            }
        }

        /** The user ignores the link's path. */
        data object Ignored : Problem

        /**
         * The relocation would overlap the one from `other` in the draft, or its own source and target would overlap
         * when `other` is null.
         */
        data class Overlap(val other: Path?) : Problem
    }
}

/**
 * One discovery for one Configuration session, and the latest result that answers its current check. The UI
 * thread calls it; workers only publish snapshots, so a result for an earlier check is never shown.
 */
class Suggestions(private val worker: CandidateDiscovery) : AutoCloseable {
    private var generation = -1L
    private var latest: CandidateDiscovery.Result? = null
    var request: CandidateDiscovery.Request? = null
        private set

    /** Nonblocking: starts checking `sourceRoot` and the suggestion list at `list`, and forgets the last result. */
    fun check(sourceRoot: Path, list: Path?) {
        generation = worker.refresh(sourceRoot, list)
        request = CandidateDiscovery.Request.of(sourceRoot, list)
        latest = null
    }

    fun result(): CandidateDiscovery.Result? {
        val snapshot = worker.snapshot()
        if (snapshot.generation == generation && snapshot.request == request) latest = snapshot
        return latest
    }

    override fun close() = worker.close()
}
