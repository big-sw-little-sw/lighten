package io.github.bigswlittlesw.lighten.config

import java.io.IOException
import java.nio.file.Path

/**
 * Reads and merges suggestion lists. It never looks at the suggested directories on disk and never decides which
 * suggestions Browse shows.
 *
 * Each [Snapshot] copies the lists it is given, so a later change to a list passed in cannot change it.
 */
object CandidateCatalog {
    val BUNDLED = CandidateSource(CandidateSource.Kind.BUNDLED, "/candidates.json")

    /** Parses the bundled list for [root]. The resource is read once per process, so later calls do no I/O. */
    fun bundled(root: Path): Snapshot {
        val parser = CandidateParser()
        return bundledBytes.fold(
            { bytes ->
                if (bytes == null) {
                    parser.failure(BUNDLED, root, CandidateDiagnostic.Kind.RESOURCE, "The built-in list is missing from Lighten")
                } else parser.parse(BUNDLED, root, bytes)
            },
            { e ->
                parser.failure(
                    BUNDLED, root, CandidateDiagnostic.Kind.RESOURCE,
                    "Cannot read the built-in list: " + e.message,
                )
            },
        )
    }

    // `null` when the resource is missing. The parser only reads the array, so sharing it is safe.
    private val bundledBytes: Result<ByteArray?> by lazy {
        try {
            Result.success(
                CandidateCatalog::class.java.getResourceAsStream(BUNDLED.location)
                    ?.use { it.readNBytes(CandidateParser.MAX_BYTES + 1) },
            )
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    /**
     * Candidates keep the order of `snapshots`. Within a candidate the user's list comes before the built-in list.
     * So the user's list decides the app and advice that Browse shows, and Details lists it first.
     * A list that failed gives its diagnostics and no definitions.
     *
     * An each-subdirectory record is a candidate for its own path, and also one for each of its `subdirectories`
     * found on this machine, right after it. Such a candidate keeps the record as it is, with the parent's path.
     */
    fun merge(snapshots: List<Snapshot>, subdirectories: Map<Path, List<Path>> = mapOf()): Merged {
        require(snapshots.distinctBy { it.root }.size <= 1) { "Cannot merge snapshots from different roots" }
        val candidates = snapshots.flatMap { it.definitions }
            .flatMap { definition ->
                val children = if (definition.eachSubdirectory == null) listOf() else subdirectories[definition.sourcePath].orEmpty()
                (listOf(definition.sourcePath) + children).map { it to definition }
            }
            .groupBy({ it.first }, { it.second })
            .map { (path, definitions) -> Candidate(path, definitions.sortedBy { it.source.kind != CandidateSource.Kind.SHARED }) }
        return Merged(candidates, snapshots.flatMap { it.diagnostics })
    }

    /**
     * Whether the built-in list's keep list names `path`, a source under `root`. It parses the built-in list once
     * and reads no file, so a screen can ask it on every frame.
     */
    fun builtInKeeps(root: Path, path: Path): Boolean {
        if (!path.startsWith(root) || path == root) return false
        val placed = BUNDLED_ROOT.resolve(root.relativize(path))
        return bundledEachSubdirectory.any { it.keeps(placed) }
    }

    // The root only places the paths, so one parse serves every source root.
    private val BUNDLED_ROOT: Path = Path.of("/")

    private val bundledEachSubdirectory: List<CandidateDefinition> by lazy {
        bundled(BUNDLED_ROOT).definitions.filter { it.eachSubdirectory != null }
    }

    /** One source's parse result: either definitions or the diagnostics that rejected it, never both. */
    @ConsistentCopyVisibility
    data class Snapshot private constructor(
        val source: CandidateSource, val root: Path, val definitions: List<CandidateDefinition>,
        val diagnostics: List<CandidateDiagnostic>,
    ) {
        init {
            require(definitions.isEmpty() || diagnostics.isEmpty()) { "A rejected source cannot contain definitions" }
            require(
                definitions.all { definition ->
                    definition.source == source
                            && definition.sourcePath == resolveCandidatePath(root, definition.originalPath)
                },
            ) { "Definition does not belong to this source/root" }
            require(diagnostics.all { diagnostic -> diagnostic.source == source }) {
                "Diagnostic does not belong to this source"
            }
        }

        fun accepted(): Boolean = diagnostics.isEmpty()

        companion object {
            fun of(
                source: CandidateSource, root: Path, definitions: List<CandidateDefinition>,
                diagnostics: List<CandidateDiagnostic>,
            ): Snapshot = Snapshot(
                source, normalizedSourceRoot(root),
                definitions.toList(), diagnostics.toList(),
            )
        }
    }

    data class Candidate(val sourcePath: Path, val definitions: List<CandidateDefinition>) {
        init {
            require(definitions.isNotEmpty() && definitions.all { d -> d.names(sourcePath) }) {
                "Candidate needs matching definition occurrences"
            }
        }

        /**
         * The parent of an each-subdirectory record, which Browse does not suggest as a whole: the first definition,
         * which decides, suggests its subdirectories instead.
         */
        val listsSubdirectories: Boolean
            get() = definitions.first().let { it.eachSubdirectory != null && it.sourcePath == sourcePath }

        /** The first definition's keep list names this subdirectory. */
        val onKeepList: Boolean get() = definitions.first().keeps(sourcePath)
    }

    data class Merged(val candidates: List<Candidate>, val diagnostics: List<CandidateDiagnostic>)
}
