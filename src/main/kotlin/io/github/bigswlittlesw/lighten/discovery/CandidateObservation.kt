package io.github.bigswlittlesw.lighten.discovery

import io.github.bigswlittlesw.lighten.fs.systemReason
import java.nio.file.AccessDeniedException
import java.nio.file.Path
import java.time.Instant

/**
 * What discovery saw at one suggested path, for Browse to show. It does not say whether moving the path is safe or
 * who owns it. Discovery never measures sizes or lists what is inside a directory.
 *
 * `link` is the link at the path ([Kind.LINK]) or at the parent that blocks it ([Kind.BLOCKED_BY_LINK]), when one was
 * read.
 */
data class CandidateObservation(
    val path: Path, val kind: Kind, val link: Link?,
    val generation: Long, val observedAt: Instant, val diagnostics: List<Diagnostic>,
) {
    init {
        require(path.isAbsolute && path == path.normalize()) { "Observation requires normalized absolute identity" }
        require(link == null || kind == Kind.LINK || kind == Kind.BLOCKED_BY_LINK) { "Only a link observation has a link" }
    }

    /**
     * A link under the source root and what is where it points. `path` is spelled under the root as Browse names
     * it. `pointsTo` is the link's `text` resolved against its parent and normalized, without following anything:
     * the planner compares a source link with its target in the same way, so a relocation with this target is in sync.
     */
    data class Link(val path: Path, val text: Path, val pointsTo: Path, val target: Target) {
        enum class Target {
            /** A real directory outside the source root: the only target a relocation can take as it is. */
            DIRECTORY,
            /** A directory inside the source root, or the root itself. */
            INSIDE_ROOT,
            /** Another link, which includes a loop. */
            LINK,
            NOT_DIRECTORY,
            /** Nothing: the link is broken. */
            MISSING,
            /**
             * A directory, but the system finds another one: a relative link whose `..` passes a link above it. The
             * planner reads the link's text as written, so it would plan the wrong target.
             */
            UNCLEAR,
            UNREADABLE,
        }
    }

    enum class Kind {
        PENDING, DIRECTORY, LINK, MISSING, REGULAR_FILE, OTHER, INACCESSIBLE,
        BLOCKED_BY_LINK, BLOCKED_BY_NON_DIRECTORY, UNKNOWN,
    }

    enum class Reason {
        ACCESS_DENIED, IO_ERROR, CHANGED, DEADLINE,
        ALIAS_UNCERTAINTY, NOT_DIRECTORY, MISSING,
    }

    /** Paths and details are not escaped: a screen must escape control characters before it shows them. */
    data class Diagnostic(val path: Path, val reason: Reason, val detail: String)

    internal companion object {
        fun unknown(path: Path, generation: Long, reason: Reason, detail: String): CandidateObservation =
            CandidateObservation(
                path, Kind.UNKNOWN, null, generation,
                Instant.now(), listOf(Diagnostic(path, reason, detail)),
            )
    }
}

/** Why discovery could not read a path, for Browse: a denied read says so in plain words, the rest in the system's. */
internal fun readFailure(exception: Throwable): String =
    if (exception is AccessDeniedException || exception is SecurityException) "can't read: permission denied"
    else systemReason(exception)
