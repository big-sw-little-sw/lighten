package io.github.bigswlittlesw.lighten.fs

import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/**
 * Observes a path without following a symlink at it. For a symlink, it also records where the link points, whether
 * that exists and, when it does, its real path.
 */
class PathInspector {
    fun inspect(path: Path): PathObservation {
        if (!Files.isSymbolicLink(path)) return inspectNonLink(path)
        val text = try {
            Files.readSymbolicLink(path)
        } catch (exception: IOException) {
            return PathObservation(PathState.INACCESSIBLE)
        }
        val target = path.toAbsolutePath().parent.resolve(text).normalize()
        // The system follows every link on the way, and resolves `..` after it, which the text alone can't show.
        val real = try {
            path.toRealPath()
        } catch (exception: NoSuchFileException) {
            return PathObservation(PathState.SYMLINK, target, SymlinkTargetAvailability.ABSENT, symlinkText = text)
        } catch (exception: IOException) {
            return PathObservation(PathState.SYMLINK, target, SymlinkTargetAvailability.INACCESSIBLE, symlinkText = text)
        }
        return PathObservation(
            PathState.SYMLINK, target, SymlinkTargetAvailability.EXISTS, symlinkRealPath = real, symlinkText = text,
        )
    }

    private fun inspectNonLink(path: Path): PathObservation = try {
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        when {
            attributes.isDirectory -> PathObservation(PathState.DIRECTORY, emptyDirectory = isEmptyDirectory(path))
            attributes.isRegularFile -> PathObservation(PathState.FILE)
            else -> PathObservation(PathState.OTHER)
        }
    } catch (exception: NoSuchFileException) {
        PathObservation(PathState.ABSENT)
    } catch (exception: IOException) {
        PathObservation(PathState.INACCESSIBLE)
    } catch (exception: UncheckedIOException) {
        // Reading a directory's entries reports an I/O failure this way; it is not a bug.
        PathObservation(PathState.INACCESSIBLE)
    }

    private fun isEmptyDirectory(path: Path): Boolean =
        Files.list(path).use { entries -> entries.findAny().isEmpty }
}
