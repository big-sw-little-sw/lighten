package io.github.bigswlittlesw.lighten.discovery

import io.github.bigswlittlesw.lighten.discovery.CandidateObservation.Diagnostic
import io.github.bigswlittlesw.lighten.discovery.CandidateObservation.Kind
import io.github.bigswlittlesw.lighten.discovery.CandidateObservation.Link
import io.github.bigswlittlesw.lighten.discovery.CandidateObservation.Reason
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant

/**
 * Reads only the real path of the source root, the attributes of each path without following links, and the text
 * of a link. For a link it also reads its real path and what is there, so Browse can say whether a relocation can take
 * the link over as it is. It never lists a directory. It checks the paths under the root again to find a path
 * replaced while it reads. This can miss a change: Java's path-based calls cannot lock a path against a concurrent
 * rename.
 */
internal class CandidateMetadata(private val access: Access = Access()) {
    fun anchor(root: Path): Anchor {
        val physical = access.realPath(root)
        val attributes = access.attributes(physical)
        if (!attributes.isDirectory) throw IOException("the source root is not a directory")
        return Anchor(root, physical, attributes)
    }

    fun inspect(anchor: Anchor, candidate: Path, generation: Long): CandidateObservation {
        require(candidate.startsWith(anchor.lexical) && candidate != anchor.lexical) {
            "Candidate must be below the chosen root"
        }
        val guards = mutableListOf(Guard(anchor.physical, anchor.attributes))
        val diagnostics = mutableListOf<Diagnostic>()
        try {
            checkAnchor(anchor)
            if (anchor.lexical != anchor.physical || anchor.attributes.fileKey() == null) {
                diagnostics.add(
                    Diagnostic(
                        candidate, Reason.ALIAS_UNCERTAINTY,
                        "Root alias or unavailable file identity; lexical identities are not physical deduplication",
                    ),
                )
            }
            val relative = anchor.lexical.relativize(candidate)
            var current = anchor.physical
            for (i in 0 until relative.nameCount) {
                checkGuards(guards)
                current = current.resolve(relative.getName(i))
                val attributes = try {
                    access.attributes(current)
                } catch (e: NoSuchFileException) {
                    checkGuards(guards)
                    checkAnchor(anchor)
                    diagnostics.add(Diagnostic(current, Reason.MISSING, readFailure(e)))
                    return observation(candidate, Kind.MISSING, null, generation, diagnostics)
                }
                if (attributes.fileKey() == null && diagnostics.none { it.reason == Reason.ALIAS_UNCERTAINTY }) {
                    diagnostics.add(
                        Diagnostic(
                            current, Reason.ALIAS_UNCERTAINTY,
                            "Stable file identity unavailable; replacements may not be detectable",
                        ),
                    )
                }
                guards.add(Guard(current, attributes))
                checkGuards(guards)
                val leaf = i == relative.nameCount - 1
                val text = if (attributes.isSymbolicLink) access.readLink(current) else null
                val kind = when {
                    attributes.isSymbolicLink -> if (leaf) Kind.LINK else Kind.BLOCKED_BY_LINK
                    !leaf && !attributes.isDirectory -> {
                        diagnostics.add(
                            Diagnostic(current, Reason.NOT_DIRECTORY, "Intermediate component is not a directory"),
                        )
                        Kind.BLOCKED_BY_NON_DIRECTORY
                    }
                    !leaf -> continue
                    attributes.isDirectory -> Kind.DIRECTORY
                    attributes.isRegularFile -> Kind.REGULAR_FILE
                    else -> Kind.OTHER
                }
                checkGuards(guards)
                checkAnchor(anchor)
                // Where the link points is outside the walk, so the guards do not cover it.
                val link = text?.let { linkAt(anchor, anchor.lexical.resolve(relative.subpath(0, i + 1)), it) }
                return observation(candidate, kind, link, generation, diagnostics)
            }
            error("unreachable: a candidate strictly below the root has at least one name")
        } catch (e: IOException) {
            return failed(candidate, e, generation, diagnostics)
        } catch (e: SecurityException) {
            return failed(candidate, e, generation, diagnostics)
        }
    }

    private fun failed(
        candidate: Path, failure: Exception, generation: Long, diagnostics: List<Diagnostic>,
    ): CandidateObservation {
        val reason = when (failure) {
            is Changed -> Reason.CHANGED
            is AccessDeniedException, is SecurityException -> Reason.ACCESS_DENIED
            else -> Reason.IO_ERROR
        }
        return observation(
            candidate, if (failure is Changed) Kind.UNKNOWN else Kind.INACCESSIBLE, null, generation,
            diagnostics + Diagnostic(candidate, reason, readFailure(failure)),
        )
    }

    /**
     * The link at `path` with `text`, and what is at its real path. Only a link whose real path is a directory outside
     * the root can become a relocation as it is: the planner judges a source link by its real path, and a target
     * inside the root frees no space. The link is the user's, so a failure here describes the link and does not fail
     * the observation.
     */
    private fun linkAt(anchor: Anchor, path: Path, text: Path): Link {
        val written = path.parent.resolve(text).normalize()
        val real = try {
            access.realPath(path)
        } catch (_: AccessDeniedException) {
            return Link(path, text, written, Link.Target.UNREADABLE)
        } catch (_: FileSystemException) {
            // The system can't follow the link: nothing is there, links loop, or a file is in the way.
            return Link(path, text, written, Link.Target.BROKEN)
        } catch (_: IOException) {
            return Link(path, text, written, Link.Target.UNREADABLE)
        } catch (_: SecurityException) {
            return Link(path, text, written, Link.Target.UNREADABLE)
        }
        val target = try {
            when {
                !access.attributes(real).isDirectory -> Link.Target.NOT_DIRECTORY
                real.startsWith(anchor.physical) -> Link.Target.INSIDE_ROOT
                else -> Link.Target.DIRECTORY
            }
        } catch (_: IOException) {
            Link.Target.UNREADABLE
        } catch (_: SecurityException) {
            Link.Target.UNREADABLE
        }
        return Link(path, text, real, target)
    }

    private fun checkAnchor(anchor: Anchor) {
        if (access.realPath(anchor.lexical) != anchor.physical
            || !same(anchor.attributes, access.attributes(anchor.physical))
        ) {
            throw Changed()
        }
    }

    private fun checkGuards(guards: List<Guard>) {
        for (guard in guards) {
            if (!same(guard.attributes, access.attributes(guard.path))) throw Changed()
        }
    }

    data class Anchor(val lexical: Path, val physical: Path, val attributes: BasicFileAttributes)

    private data class Guard(val path: Path, val attributes: BasicFileAttributes)

    // The message leaves out the path: the diagnostic names it.
    private class Changed : IOException("it changed while checking")

    /**
     * Has no call that lists a directory or reads a file, so discovery cannot do either.
     * Open so tests can replace a path or make a call fail between two reads.
     */
    open class Access {
        open fun realPath(path: Path): Path = path.toRealPath()

        open fun attributes(path: Path): BasicFileAttributes =
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)

        open fun readLink(path: Path): Path = Files.readSymbolicLink(path)
    }
}

private fun same(before: BasicFileAttributes, after: BasicFileAttributes): Boolean =
    before.isDirectory == after.isDirectory && before.isRegularFile == after.isRegularFile
            && before.isSymbolicLink == after.isSymbolicLink
            && before.fileKey() == after.fileKey()
            && before.creationTime() == after.creationTime()
            && (!before.isSymbolicLink || before.lastModifiedTime() == after.lastModifiedTime())

private fun observation(
    path: Path, kind: Kind, link: Link?, generation: Long, diagnostics: List<Diagnostic>,
): CandidateObservation = CandidateObservation(path, kind, link, generation, Instant.now(), diagnostics.toList())
