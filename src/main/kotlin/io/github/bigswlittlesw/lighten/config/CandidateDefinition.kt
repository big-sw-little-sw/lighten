package io.github.bigswlittlesw.lighten.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.nio.file.Path

/**
 * One suggestion as one list gives it: the list it came from, the path as written, and the reason and caution as
 * written. `recordIndex` is one-based. Callers must escape control characters before they show this text. Reasons
 * and cautions are plain text, never terminal markup or commands.
 * `location` uses zero-based indices, for example `apps[1].directories[0]`.
 *
 * `category` is the app's, as this record's list gives it; only a directory with an app can have one.
 *
 * `caution` warns about moving this directory, for example a tool command that later replaces the link.
 *
 * With [eachSubdirectory], the record suggests each subdirectory of `sourcePath` except those on its keep list, and
 * never `sourcePath` itself. The list cannot know the subdirectories: discovery lists them on this machine.
 */
data class CandidateDefinition(
    val sourcePath: Path, val source: CandidateSource, val recordIndex: Int,
    val location: String, val originalPath: String,
    val app: String?, val category: String?, val advice: Advice?, val reason: String?,
    val caution: String? = null, val eachSubdirectory: EachSubdirectory? = null,
) {
    init {
        require(
            sourcePath.isAbsolute && sourcePath == sourcePath.normalize()
                    && recordIndex >= 1 && !location.isJavaBlank(),
        ) { "Definition requires normalized absolute identity and location" }
        validateCandidatePath(originalPath)
        require(
            (app == null || !app.isJavaBlank() && app == app.javaStrip())
                && (category == null || app != null && !category.isJavaBlank() && category == category.javaStrip())
                && (reason == null || !reason.isJavaBlank())
                && (caution == null || !caution.isJavaBlank()),
        ) { "Optional text must be nonblank; app and category must be trimmed; a category needs an app" }
    }

    /** Whether this record names `path`: its own path, or with [eachSubdirectory] a subdirectory of it. */
    fun names(path: Path): Boolean = path == sourcePath || eachSubdirectory != null && path.parent == sourcePath

    /** Whether this record's keep list names `path`, a subdirectory of its path. */
    fun keeps(path: Path): Boolean =
        eachSubdirectory != null && path.parent == sourcePath && path.fileName.toString() in eachSubdirectory.keep

    /** `keep` names the subdirectories that stay in the home, each as one path segment. */
    @ConsistentCopyVisibility
    data class EachSubdirectory private constructor(val keep: List<String>) {
        init {
            keep.forEach(::validateKeepName)
        }

        companion object {
            fun of(keep: List<String>): EachSubdirectory = EachSubdirectory(keep.toList())
        }
    }

    @Serializable
    @SerialName("advice")
    enum class Advice {
        @SerialName("consider") CONSIDER,
        @SerialName("usually-unnecessary") USUALLY_UNNECESSARY,
    }
}
