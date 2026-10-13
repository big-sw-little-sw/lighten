package io.github.bigswlittlesw.lighten.discovery

import io.github.bigswlittlesw.lighten.concurrent.DISCOVERY_CONCURRENCY
import io.github.bigswlittlesw.lighten.concurrent.mapBounded
import io.github.bigswlittlesw.lighten.config.CandidateCatalog
import io.github.bigswlittlesw.lighten.config.CandidateDiagnostic
import io.github.bigswlittlesw.lighten.config.CandidateParser
import io.github.bigswlittlesw.lighten.config.CandidateSource
import io.github.bigswlittlesw.lighten.discovery.CandidateDiscovery.SourceOutcome
import io.github.bigswlittlesw.lighten.discovery.CandidateDiscovery.SourceProblem
import io.github.bigswlittlesw.lighten.discovery.CandidateDiscovery.SourceStatus
import io.github.bigswlittlesw.lighten.discovery.CandidateObservation.Diagnostic
import io.github.bigswlittlesw.lighten.discovery.CandidateObservation.Kind
import io.github.bigswlittlesw.lighten.discovery.CandidateObservation.Reason
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Finds which suggested directories exist under the source root, for one Configuration session. `refresh`,
 * `snapshot`, `cancel` and `close` never touch the disk and never wait for a worker thread.
 *
 * - The built-in list is parsed on the calling thread, from bytes read once per process.
 * - The user's list is read on its own thread.
 * - A run thread resolves the source root, then inspects the suggestions, [Workers.n] at a time, in list order.
 *   It lists the parent of each each-subdirectory suggestion as part of its inspection, then inspects the
 *   subdirectories found. Paths that only the user's list names are inspected in a second batch, after that list
 *   is accepted.
 * - `snapshot` reads what the threads recorded and applies the time limits.
 *
 * Reading the user's list, and each inspection of the root or a suggestion, has five seconds from the start of
 * its disk access. A parent's listing is part of its inspection, so it shares that time limit. A later result is
 * refused, even when no snapshot was taken at the time limit.
 *
 * Each refresh starts a new generation. Older work stops before its next path and its results are dropped, so rows
 * show as pending until the new generation inspects them. A list that fails to read gives no suggestions.
 * Cancelling or closing drops all the session's data. The limits on threads hold for the whole process, also across
 * closed sessions ([Workers]).
 */
class CandidateDiscovery internal constructor(
    private val workers: Workers,
    private val clock: () -> Long,
    private val sharedReader: (Path) -> ByteArray,
    private val bundledReader: (Path) -> CandidateCatalog.Snapshot,
    private val metadata: CandidateMetadata,
) : AutoCloseable {
    // This instance's monitor guards these fields. Worker threads take it only to record an attempt.
    private var generation: Long = 0
    private var closed = false
    private var request: Request? = null
    // Each list's outcome as of the refresh. The entry for the user's list is combined with `sharedRead` when read.
    private val sources = LinkedHashMap<CandidateSource, SourceOutcome>()
    private var sharedRead: Attempt<SharedFile>? = null
    private var anchor: Attempt<CandidateMetadata.Anchor>? = null
    private val inspections = HashMap<Path, Attempt<CandidateObservation>>()
    // Paths handed to a batch in this generation, so a path both lists name is inspected once.
    private val scheduled = HashSet<Path>()
    // The scheduled paths that an each-subdirectory suggestion names as its parent: their inspection lists them.
    private val parents = HashSet<Path>()

    constructor() : this(PROCESS_WORKERS, System::nanoTime, ::readShared, CandidateCatalog::bundled, CandidateMetadata())

    /** Both paths must already be absolute: this does not expand `~` and does not save the list's location. */
    @Synchronized
    fun refresh(root: Path, sharedLocation: Path?): Long {
        check(!closed) { "Discovery is closed" }
        val next = Request.of(root, sharedLocation)
        cancel()
        request = next
        val generation = generation
        val bundled = resolved(CandidateCatalog.BUNDLED, catching { bundledReader(next.root) })
        sources[bundled.source] = bundled
        val paths = schedule(bundled)
        workers.chain { run(generation, next.root, paths) }
        next.sharedLocation?.let { startShared(generation, next.root, it) }
        return generation
    }

    /**
     * The result's collections are read-only copies, and its observations never change. It gives no total size.
     * A candidate's `ancestors` are the other suggestions that contain it. Neither the parent of an each-subdirectory
     * suggestion nor a subdirectory on its keep list is one of them, as neither is suggested itself.
     */
    @Synchronized
    fun snapshot(): Result {
        val request = this.request ?: return Result(generation, null, listOf(), listOf(), null)
        val sources = currentSources()
        val rootFailure = rootFailure(request.root)
        val subdirectories = if (rootFailure != null) mapOf() else parents.mapNotNull { parent ->
            observe(parent)?.subdirectories?.let { parent to it }
        }.toMap()
        val candidates = CandidateCatalog.merge(sources.mapNotNull { it.catalog }, subdirectories).candidates
        val identities = candidates.filterNot { it.listsSubdirectories || it.onKeepList }.mapTo(HashSet()) { it.sourcePath }
        val rows = candidates.map { candidate ->
            val path = candidate.sourcePath
            val observation = rootFailure?.let { CandidateObservation.unknown(path, generation, it.reason, it.detail) }
                ?: observe(path) ?: pending(path)
            val ancestors = generateSequence(path.parent) { it.parent }
                .takeWhile { it.startsWith(request.root) }
                .filter { it in identities }
                .toList()
            Candidate(candidate, observation, ancestors)
        }
        return Result(generation, request, sources, rows, rootFailure)
    }

    @Synchronized
    fun cancel() {
        generation++
        request = null
        sources.clear()
        sharedRead = null
        anchor = null
        inspections.clear()
        scheduled.clear()
        parents.clear()
    }

    @Synchronized
    override fun close() {
        if (closed) return
        cancel()
        closed = true
    }

    private fun startShared(generation: Long, root: Path, location: Path) {
        val source = CandidateSource(CandidateSource.Kind.SHARED, location.toString())
        if (!workers.sharedRead.compareAndSet(false, true)) {
            sources[source] = failed(
                source, listOf(),
                SourceProblem(SourceProblem.Kind.PREVIOUS_PENDING, "Previous read still pending; manual setup remains available"),
            )
            return
        }
        sources[source] = SourceOutcome(source, null, SourceStatus.PENDING, listOf(), listOf())
        val started = clock()
        sharedRead = Attempt.Running(started)
        Thread.ofVirtual().name("lighten-shared-list").start {
            val result = catching {
                val bytes = sharedReader(location)
                SharedFile(CandidateParser().parse(source, root, bytes), modified(location))
            }
            synchronized(this@CandidateDiscovery) {
                // Free the slot in the same monitor block as the publish, so a refresh right after it never sees a
                // finished read as stuck.
                workers.sharedRead.set(false)
                if (this.generation == generation) publishShared(generation, source, Attempt.Done(started, clock(), result))
            }
        }
    }

    private fun publishShared(generation: Long, source: CandidateSource, done: Attempt.Done<SharedFile>) {
        sharedRead = done
        val paths = schedule(sharedOutcome(sources.getValue(source), done))
        if (paths.isNotEmpty()) workers.chain { inspectBatch(generation, paths) }
    }

    /**
     * Takes the paths of an accepted list that no earlier batch of this generation has taken, and notes which of its
     * paths to list. A path that another list already took is listed too, when this list asks.
     */
    private fun schedule(outcome: SourceOutcome): List<Path> {
        if (outcome.status != SourceStatus.CURRENT) return listOf()
        val definitions = outcome.catalog?.definitions.orEmpty()
        val taken = definitions.map { it.sourcePath }.filter { scheduled.add(it) }
        val listed = definitions.filter { it.eachSubdirectory != null }.map { it.sourcePath }.filter { parents.add(it) }
        // A parent taken earlier as a plain path has been inspected without its listing, so it is inspected again.
        return taken + listed.filter { it !in taken }
    }

    // Both run on a chained run thread: the previous run has returned, so this one has all `workers.n` slots.
    private fun run(generation: Long, root: Path, paths: List<Path>) {
        attempt(generation, { anchor = it }) { metadata.anchor(root) }
        inspectBatch(generation, paths)
    }

    /** Inspects `paths`, listing the parents among them, then inspects the subdirectories found that are new. */
    private fun inspectBatch(generation: Long, paths: List<Path>) {
        val anchor = synchronized(this) { usableAnchor(generation) } ?: return
        inspect(generation, anchor, paths)
        val subdirectories = synchronized(this) {
            if (this.generation != generation) return
            paths.filter { it in parents }.flatMap { observe(it)?.subdirectories.orEmpty() }.filter { scheduled.add(it) }
        }
        inspect(generation, anchor, subdirectories)
    }

    private fun inspect(generation: Long, anchor: CandidateMetadata.Anchor, paths: List<Path>) {
        val listed = synchronized(this) { paths.filter { it in parents }.toSet() }
        mapBounded(paths, workers.n, { synchronized(this) { this.generation != generation } }) { path ->
            attempt(generation, { inspections[path] = it }) { metadata.inspect(anchor, path, generation, path in listed) }
        }
    }

    private fun usableAnchor(generation: Long): CandidateMetadata.Anchor? {
        val done = anchor as? Attempt.Done ?: return null
        if (this.generation != generation || expired(done, METADATA_NANOS)) return null
        return done.result.getOrNull()
    }

    /**
     * Records `Running`, does [io] outside the monitor, then records `Done`. Both records happen under the monitor
     * and only while [generation] is current, so work from an older generation never publishes.
     */
    private inline fun <T> attempt(generation: Long, record: (Attempt<T>) -> Unit, io: () -> T) {
        val started = synchronized(this) {
            if (this.generation != generation) return
            clock().also { record(Attempt.Running(it)) }
        }
        val result = catching(io)
        synchronized(this) { if (this.generation == generation) record(Attempt.Done(started, clock(), result)) }
    }

    private fun currentSources(): List<SourceOutcome> = sources.values.map {
        if (it.source.kind == CandidateSource.Kind.SHARED) sharedOutcome(it, sharedRead) else it
    }

    private fun sharedOutcome(pending: SourceOutcome, attempt: Attempt<SharedFile>?): SourceOutcome {
        if (attempt == null) return pending
        if (expired(attempt, SOURCE_NANOS)) {
            return failed(pending.source, listOf(), SourceProblem(SourceProblem.Kind.DEADLINE, "Source response deadline exceeded"))
        }
        return when (attempt) {
            is Attempt.Running -> pending
            is Attempt.Done -> resolved(pending.source, attempt.result.map { it.catalog })
                .copy(modified = attempt.result.getOrNull()?.modified)
        }
    }

    /** What this generation saw at [path], or `null` while its inspection waits or runs. */
    private fun observe(path: Path): CandidateObservation? {
        val attempt = inspections[path] ?: return null
        if (expired(attempt, METADATA_NANOS)) {
            return CandidateObservation.unknown(path, generation, Reason.DEADLINE, "Inspection response deadline exceeded")
        }
        return when (attempt) {
            is Attempt.Running -> null
            is Attempt.Done -> attempt.result.getOrElse {
                CandidateObservation.unknown(path, generation, Reason.IO_ERROR, readFailure(it))
            }
        }
    }

    private fun pending(path: Path): CandidateObservation =
        CandidateObservation(path, Kind.PENDING, null, generation, Instant.now(), listOf())

    private fun rootFailure(root: Path): Diagnostic? {
        val attempt = anchor ?: return null
        if (expired(attempt, METADATA_NANOS)) return Diagnostic(root, Reason.DEADLINE, "Root inspection timed out")
        val failure = (attempt as? Attempt.Done)?.result?.exceptionOrNull() ?: return null
        val reason = if (failure is AccessDeniedException) Reason.ACCESS_DENIED else Reason.IO_ERROR
        return Diagnostic(root, reason, readFailure(failure))
    }

    private fun expired(attempt: Attempt<*>, limit: Long): Boolean = when (attempt) {
        is Attempt.Running -> clock() - attempt.started
        is Attempt.Done -> attempt.finished - attempt.started
    } >= limit

    @ConsistentCopyVisibility
    data class Request private constructor(val root: Path, val sharedLocation: Path?) {
        companion object {
            /** Both paths must be absolute. Requests compare by the normalized paths. */
            fun of(root: Path, sharedLocation: Path?): Request =
                Request(normalized(root), sharedLocation?.let(::normalized))
        }
    }

    enum class SourceStatus { PENDING, CURRENT, FAILED }

    data class SourceProblem(val kind: Kind, val detail: String) {
        enum class Kind { MISSING, UNREADABLE, NOT_REGULAR, IO_ERROR, DEADLINE, PREVIOUS_PENDING }
    }

    /** `modified` is when your list's file last changed, when it was read; the built-in list has none. */
    data class SourceOutcome(
        val source: CandidateSource, val catalog: CandidateCatalog.Snapshot?,
        val status: SourceStatus, val diagnostics: List<CandidateDiagnostic>,
        val problems: List<SourceProblem>, val modified: Instant? = null,
    )

    data class Candidate(
        val catalog: CandidateCatalog.Candidate, val observation: CandidateObservation,
        val ancestors: List<Path>,
    )

    data class Result(
        val generation: Long, val request: Request?, val sources: List<SourceOutcome>,
        val candidates: List<Candidate>, val rootFailure: Diagnostic?,
    )

    /** Your list as read: its parse and when the file last changed, or null when that could not be read. */
    private data class SharedFile(val catalog: CandidateCatalog.Snapshot, val modified: Instant?)

    /** One piece of background I/O. Times come from the injected clock. */
    private sealed interface Attempt<out T> {
        val started: Long

        data class Running(override val started: Long) : Attempt<Nothing>

        data class Done<T>(override val started: Long, val finished: Long, val result: kotlin.Result<T>) : Attempt<T>
    }
}

/**
 * Limits on discovery threads for the whole process. Every session shares them, so reopening Configuration cannot
 * add more threads stuck on a mount that does not respond. A blocked `stat` ignores interrupts, so the only limit is
 * to never start more work:
 *
 * - Runs are chained. Each starts only after the previous run has returned, and a run inspects at most [n] paths
 *   at a time, so at most [n] inspections are in flight across refreshes and sessions.
 * - At most one shared-list read is in flight; a refresh during it reports `PREVIOUS_PENDING`.
 */
internal class Workers(val n: Int = DISCOVERY_CONCURRENCY) {
    val sharedRead = AtomicBoolean()
    private var last: Thread? = null

    @Synchronized
    fun chain(run: () -> Unit) {
        val previous = last
        last = Thread.ofVirtual().name("lighten-discovery").start {
            previous?.join()
            run()
        }
    }
}

private const val SOURCE_NANOS = 5_000_000_000L

internal const val METADATA_NANOS = 5_000_000_000L

private val PROCESS_WORKERS = Workers()

// The message leaves out the path: Browse shows the list's location beside it.
private class NotRegular : IOException("not a regular file")

private fun readShared(location: Path): ByteArray {
    // Following a link here is allowed: the user chose this location. If a FIFO replaces the file after the check,
    // the open can block. The one read in flight and the time limit still bound that.
    if (!Files.readAttributes(location, BasicFileAttributes::class.java).isRegularFile) {
        throw NotRegular()
    }
    return Files.newInputStream(location).use { it.readNBytes(CandidateParser.MAX_BYTES + 1) }
}

// Runs on the user's list thread after the read, so the same time limit bounds a slow `stat`. The time is only
// shown, so a failure leaves it out and does not fail the list.
private fun modified(location: Path): Instant? =
    try {
        Files.getLastModifiedTime(location).toInstant()
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

// Only an Exception is an outcome of the work; an Error still propagates.
private inline fun <T> catching(work: () -> T): Result<T> =
    try {
        Result.success(work())
    } catch (e: Exception) {
        Result.failure(e)
    }

private fun resolved(source: CandidateSource, result: Result<CandidateCatalog.Snapshot>): SourceOutcome =
    result.fold(
        { catalog ->
            if (catalog.accepted()) SourceOutcome(source, catalog, SourceStatus.CURRENT, listOf(), listOf())
            else failed(source, catalog.diagnostics, null)
        },
        { failed(source, listOf(), sourceProblem(it)) },
    )

private fun failed(source: CandidateSource, diagnostics: List<CandidateDiagnostic>, problem: SourceProblem?): SourceOutcome =
    SourceOutcome(source, null, SourceStatus.FAILED, diagnostics, listOfNotNull(problem))

private fun sourceProblem(failure: Throwable): SourceProblem {
    val kind = when (failure) {
        is NoSuchFileException -> SourceProblem.Kind.MISSING
        is NotRegular -> SourceProblem.Kind.NOT_REGULAR
        is AccessDeniedException, is SecurityException -> SourceProblem.Kind.UNREADABLE
        else -> SourceProblem.Kind.IO_ERROR
    }
    return SourceProblem(kind, readFailure(failure))
}

private fun normalized(path: Path): Path {
    require(path.isAbsolute) { "Absolute path required" }
    return path.normalize()
}
