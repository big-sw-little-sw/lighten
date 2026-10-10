package io.github.bigswlittlesw.lighten.config

import io.github.bigswlittlesw.lighten.fs.PathText
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Rejects a configuration with nothing in it, or whose relocations are unsafe together ([relocationProblem]). A
 * configuration that only ignores paths is valid, because ignoring the last relocation leaves one like that.
 */
fun validateConfiguration(relocations: List<Relocation>, ignoredSourcePaths: List<Path> = listOf()) {
    require(relocations.isNotEmpty() || ignoredSourcePaths.isNotEmpty()) { "Choose at least one relocation" }
    relocationProblem(relocations)?.let { throw ConfigurationException(it.message) }
}

/** A broken relocation rule, reported against the [source] path of the relocation it concerns. */
internal data class RelocationProblem(val source: Path, val message: PathText)

/**
 * The first problem that makes `relocations` unsafe together, or null.
 *
 * First each relocation is checked alone: its source and target must not overlap. Then each pair is checked in
 * order: they must not share a target, and none of their paths may overlap. A pair is reported against its earlier
 * relocation. A shared target is also an overlap, but it gets a clearer message.
 */
internal fun relocationProblem(relocations: List<Relocation>): RelocationProblem? {
    for (relocation in relocations) {
        val source = relocation.sourcePath
        if (intersects(source, relocation.targetPath)) {
            return RelocationProblem(source, PathText("source and target paths overlap: ", source))
        }
    }
    for ((index, left) in relocations.withIndex()) {
        for (right in relocations.drop(index + 1)) {
            if (left.targetPath == right.targetPath) {
                return RelocationProblem(left.sourcePath, PathText("duplicate target path: ", left.targetPath))
            }
            if (intersects(left.sourcePath, right.sourcePath) || intersects(left.sourcePath, right.targetPath)
                || intersects(left.targetPath, right.sourcePath) || intersects(left.targetPath, right.targetPath)
            ) {
                return RelocationProblem(
                    left.sourcePath, PathText("relocation paths overlap: ", left.sourcePath, " and ", right.sourcePath),
                )
            }
        }
    }
    return null
}

/**
 * The first problem that adding `new` to `relocations` makes, as [relocationProblem] reports it: `new` alone, or `new`
 * with one of them, reported against that one. A problem among `relocations` themselves is left out, so it never
 * refuses an unrelated addition; saving reports it.
 */
internal fun additionProblem(relocations: List<Relocation>, new: Relocation): RelocationProblem? =
    relocationProblem(listOf(new)) ?: relocations.filter { relocationProblem(listOf(it)) == null }
        .firstNotNullOfOrNull { relocationProblem(listOf(it, new)) }

/** Whether one path contains the other, compared absolute and normalized. */
internal fun intersects(left: Path, right: Path): Boolean {
    val first = normalized(left)
    val second = normalized(right)
    return first.startsWith(second) || second.startsWith(first)
}

private fun normalized(path: Path): Path = path.toAbsolutePath().normalize()

/**
 * [path], absolute and normalized, with its longest existing ancestor replaced by that ancestor's real path, so that
 * different spellings of one place compare equal. The path itself is never followed: a source may be the link that
 * Lighten created. Components that do not exist yet, and an ancestor that cannot be resolved, stay as written.
 */
internal fun realSpelling(path: Path): Path {
    val absolute = normalized(path)
    val existing = generateSequence(absolute.parent) { it.parent }.firstOrNull { Files.exists(it) } ?: return absolute
    return try {
        existing.toRealPath().resolve(existing.relativize(absolute))
    } catch (_: IOException) {
        absolute
    }
}
