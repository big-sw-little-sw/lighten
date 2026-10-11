package io.github.bigswlittlesw.lighten

import org.junit.jupiter.api.Assertions.assertNull
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.isSymbolicLink

/**
 * Every path under [root] but those in [except], with its kind, its link text and its file contents, sorted. Tests
 * compare it before and after to show that nothing on disk changed, link texts included. It never follows a link.
 */
internal fun diskTree(root: Path, except: Set<Path> = setOf()): List<String> = Files.walk(root).use { paths ->
    paths.filter { it !in except }.map { path ->
        val name = root.relativize(path).toString()
        when {
            path.isSymbolicLink() -> "$name -> ${Files.readSymbolicLink(path)}"
            path.isRegularFile(LinkOption.NOFOLLOW_LINKS) -> "$name = ${Files.readString(path)}"
            path.isDirectory(LinkOption.NOFOLLOW_LINKS) -> "$name/"
            else -> "$name ?"
        }
    }.sorted().toList()
}.also { assertNull(it.firstOrNull { line -> line.endsWith("?") }) }
