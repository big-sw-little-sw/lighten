package io.github.bigswlittlesw.lighten.discovery

import io.github.bigswlittlesw.lighten.discovery.CandidateObservation.Kind
import io.github.bigswlittlesw.lighten.discovery.CandidateObservation.Link
import io.github.bigswlittlesw.lighten.discovery.CandidateObservation.Reason
import io.github.bigswlittlesw.lighten.diskTree
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.atomic.AtomicInteger

class CandidateMetadataTest {
    @TempDir lateinit var temporary: Path

    @Test fun fixtureStatesHaveNoSizeAndNeverInspectContents() {
        val root = temporary.toRealPath()
        for (path in listOf(".m2/repository", ".cache/uv", ".cache/example", ".local/share/uv/tools")) {
            Files.createDirectories(root.resolve(path))
        }
        Files.write(root.resolve(".m2/repository/secret"), ByteArray(4096))
        Files.write(root.resolve("not-a-directory"), ByteArray(16))
        val allowed = HashSet<Path>(listOf(root))
        for (path in listOf(".m2", ".cache/uv", ".cache/example", ".local/share/uv/tools",
                "not-a-directory", "absent-cache")) {
            var part: Path = root.resolve(path)
            while (part.startsWith(root)) { allowed.add(part); part = part.parent }
        }
        val reader = guarded(root, allowed)
        val anchor = reader.anchor(root)
        for (path in listOf(".m2", ".cache/uv", ".cache/example", ".local/share/uv", ".local/share/uv/tools")) {
            assertState(reader.inspect(anchor, root.resolve(path), 7), Kind.DIRECTORY)
        }
        assertState(reader.inspect(anchor, root.resolve("not-a-directory"), 7), Kind.REGULAR_FILE)
        assertState(reader.inspect(anchor, root.resolve("not-a-directory/child"), 7), Kind.BLOCKED_BY_NON_DIRECTORY)
        assertState(reader.inspect(anchor, root.resolve("absent-cache"), 7), Kind.MISSING)
        assertEquals(4096L, Files.size(root.resolve(".m2/repository/secret")))
    }

    /**
     * A link is never walked into: discovery reads only what is at its real path, and nothing below that. The root
     * alias is resolved, and so is each link, to find whether it leads inside the root.
     */
    @Test fun rootAliasAllowedAndLinksAreReadButNeverWalkedInto() {
        val physical = Files.createDirectory(temporary.resolve("root")).toRealPath()
        val outside = Files.createDirectory(temporary.resolve("outside")).toRealPath()
        Files.createDirectories(outside.resolve("cache"))
        Files.write(outside.resolve("cache/secret"), ByteArray(100))
        val alias = Files.createSymbolicLink(temporary.resolve("alias"), physical)
        Files.createDirectory(physical.resolve("inside"))
        Files.createSymbolicLink(physical.resolve("link"), outside)
        Files.createSymbolicLink(physical.resolve("broken"), Path.of("missing"))
        Files.createSymbolicLink(physical.resolve("inside-parent"), Path.of("inside"))
        val allowed = setOf(physical, physical.resolve("inside"), physical.resolve("link"),
                physical.resolve("broken"), physical.resolve("inside-parent"),
                // Where the links lead: their real paths.
                outside)
        val reader = guarded(
            alias, allowed,
            resolvable = setOf(alias, alias.resolve("link"), alias.resolve("broken"), alias.resolve("inside-parent")),
        )
        val anchor = reader.anchor(alias)
        val directory = reader.inspect(anchor, alias.resolve("inside"), 1)
        assertState(directory, Kind.DIRECTORY)
        assertReason(directory, Reason.ALIAS_UNCERTAINTY)
        for ((name, target) in listOf("link" to Link.Target.DIRECTORY, "broken" to Link.Target.BROKEN)) {
            val result = reader.inspect(anchor, alias.resolve(name), 1)
            assertState(result, Kind.LINK)
            assertEquals(Link(alias.resolve(name), Files.readSymbolicLink(physical.resolve(name)),
                if (name == "link") outside else alias.resolve("missing"), target), result.link)
            assertEquals(alias.resolve(name), result.path)
        }
        val blocked = reader.inspect(anchor, alias.resolve("link/cache"), 1)
        assertState(blocked, Kind.BLOCKED_BY_LINK)
        assertEquals(Link(alias.resolve("link"), outside, outside, Link.Target.DIRECTORY), blocked.link)
        val insideParent = reader.inspect(anchor, alias.resolve("inside-parent/child"), 1)
        assertState(insideParent, Kind.BLOCKED_BY_LINK)
        assertEquals(Link.Target.INSIDE_ROOT, insideParent.link?.target)
        assertEquals(outside, Files.readSymbolicLink(physical.resolve("link")))
        assertEquals(100L, Files.size(outside.resolve("cache/secret")))
    }

    /**
     * What is at a link's real path decides whether Browse can take it over: only a directory outside the root.
     * `pointsTo` is that real path, through every other link on the way, as the planner judges a source link.
     */
    @Test fun eachLinkSaysWhatIsAtItsRealPath() {
        val base = temporary.toRealPath()
        val root = Files.createDirectories(base.resolve("home"))
        val storage = Files.createDirectories(base.resolve("storage"))
        for (name in listOf("abs", "rel", "end", "parent-target/pip")) Files.createDirectories(storage.resolve(name))
        Files.writeString(storage.resolve("file"), "x")
        Files.createDirectories(root.resolve("real"))
        Files.createSymbolicLink(base.resolve("alias"), root)
        fun link(name: String, text: String) = Files.createSymbolicLink(root.resolve(name), Path.of(text))
        link("abs", storage.resolve("abs").toString())
        link("rel", "../storage/rel")
        // A chain of absolute hops, a chain of relative hops, and a chain that ends in the home.
        link("chain", storage.resolve("hop").toString())
        Files.createSymbolicLink(storage.resolve("hop"), storage.resolve("end"))
        link("relative-chain", "../storage/relative-hop")
        Files.createSymbolicLink(storage.resolve("relative-hop"), Path.of("end"))
        link("chain-home", "../storage/hop-home")
        Files.createSymbolicLink(storage.resolve("hop-home"), root.resolve("real"))
        link("loop-a", "loop-b"); link("loop-b", "loop-a")
        link("to-file", storage.resolve("file").toString())
        link("chain-to-file", "../storage/hop-file")
        Files.createSymbolicLink(storage.resolve("hop-file"), Path.of("file"))
        link("broken", storage.resolve("gone").toString())
        link("chain-broken", "../storage/hop-gone")
        Files.createSymbolicLink(storage.resolve("hop-gone"), Path.of("gone"))
        link("inside", root.resolve("real").toString())
        // The same place as `real`, spelled through a link outside the root.
        link("inside-real", base.resolve("alias/real").toString())
        link("to-root", "..")
        // A linked parent with a link inside it, absolute and relative, and nested linked parents.
        link(".cache", storage.resolve("parent-target").toString())
        Files.createSymbolicLink(storage.resolve("parent-target/abs-child"), storage.resolve("abs"))
        Files.createSymbolicLink(storage.resolve("parent-target/rel-child"), Path.of("../rel"))
        link(".local", storage.resolve("parent-target").toString())
        val before = diskTree(base)
        val reader = CandidateMetadata()
        val anchor = reader.anchor(root)
        fun observed(path: String) = reader.inspect(anchor, root.resolve(path), 1)
        fun link(path: String) = checkNotNull(observed(path).link) { path }
        fun target(path: String) = link(path).target
        assertEquals(Link(root.resolve("abs"), storage.resolve("abs"), storage.resolve("abs"), Link.Target.DIRECTORY), observed("abs").link)
        assertEquals(Link(root.resolve("rel"), Path.of("../storage/rel"), storage.resolve("rel"), Link.Target.DIRECTORY), observed("rel").link)
        assertEquals(Link(root.resolve("chain"), storage.resolve("hop"), storage.resolve("end"), Link.Target.DIRECTORY), observed("chain").link)
        assertEquals(
            Link(root.resolve("relative-chain"), Path.of("../storage/relative-hop"), storage.resolve("end"), Link.Target.DIRECTORY),
            observed("relative-chain").link,
        )
        assertEquals(Link.Target.INSIDE_ROOT, target("chain-home"))
        assertEquals(root.resolve("real"), link("chain-home").pointsTo)
        assertEquals(Link.Target.BROKEN, target("loop-a"))
        assertEquals(Link.Target.NOT_DIRECTORY, target("to-file"))
        assertEquals(Link.Target.NOT_DIRECTORY, target("chain-to-file"))
        assertEquals(Link.Target.BROKEN, target("broken"))
        assertEquals(storage.resolve("gone"), link("broken").pointsTo, "a broken link shows its text against its parent")
        assertEquals(Link.Target.BROKEN, target("chain-broken"))
        assertEquals(storage.resolve("hop-gone"), link("chain-broken").pointsTo)
        assertEquals(Link.Target.INSIDE_ROOT, target("inside"))
        assertEquals(Link.Target.INSIDE_ROOT, target("inside-real"))
        assertEquals(Link.Target.DIRECTORY, target("to-root"), "the root's parent is outside it; overlap refuses it later")
        for (child in listOf(".cache/pip", ".cache/abs-child", ".cache/rel-child")) {
            val result = observed(child)
            assertState(result, Kind.BLOCKED_BY_LINK)
            assertEquals(Link(root.resolve(".cache"), storage.resolve("parent-target"), storage.resolve("parent-target"), Link.Target.DIRECTORY), result.link)
        }
        // The first link from the root names the parent; `.local/share` below it is never read.
        Files.createSymbolicLink(storage.resolve("parent-target/share"), storage.resolve("rel"))
        assertEquals(root.resolve(".local"), observed(".local/share/uv").link?.path)
        Files.delete(storage.resolve("parent-target/share"))
        assertEquals(Kind.DIRECTORY, observed("real").kind)
        assertEquals(null, observed("real").link)
        assertEquals(before, diskTree(base))
    }

    /**
     * The shape dotfiles make: links in the home, absolute and relative, lead through another link in the home to
     * storage. Each one, and a linked parent spelled the same way, leads to a directory outside the home.
     */
    @Test fun aLinkThroughALinkInTheHomeLeadsToStorage() {
        val base = temporary.toRealPath()
        val root = Files.createDirectories(base.resolve("home"))
        val disk = Files.createDirectories(base.resolve("disk"))
        for (name in listOf("cache/JetBrains", "cargo", "m2/repository")) Files.createDirectories(disk.resolve(name))
        Files.createDirectories(root.resolve(".cache"))
        Files.createSymbolicLink(root.resolve(".local-heavy"), disk)
        Files.createSymbolicLink(root.resolve(".cache/JetBrains"), Path.of("../.local-heavy/cache/JetBrains"))
        Files.createSymbolicLink(root.resolve(".cargo"), Path.of(".local-heavy/cargo"))
        Files.createSymbolicLink(root.resolve(".m2"), root.resolve(".local-heavy/m2"))
        val before = diskTree(base)
        val reader = CandidateMetadata()
        val anchor = reader.anchor(root)
        fun observed(path: String) = reader.inspect(anchor, root.resolve(path), 1)
        assertEquals(
            Link(root.resolve(".cache/JetBrains"), Path.of("../.local-heavy/cache/JetBrains"), disk.resolve("cache/JetBrains"), Link.Target.DIRECTORY),
            observed(".cache/JetBrains").link,
        )
        assertEquals(Link(root.resolve(".cargo"), Path.of(".local-heavy/cargo"), disk.resolve("cargo"), Link.Target.DIRECTORY), observed(".cargo").link)
        val inParent = observed(".m2/repository")
        assertState(inParent, Kind.BLOCKED_BY_LINK)
        assertEquals(Link(root.resolve(".m2"), root.resolve(".local-heavy/m2"), disk.resolve("m2"), Link.Target.DIRECTORY), inParent.link)
        // The anchor link itself leads outside the home too.
        assertEquals(disk, observed(".local-heavy").link?.pointsTo)
        assertEquals(before, diskTree(base))
    }

    /** Under a root reached through a link, a relative link's `..` leads where the system finds it, as written or not. */
    @Test fun aRelativeLinkThatLeavesAnAliasedRootLeadsWhereTheSystemFindsIt() {
        val base = temporary.toRealPath()
        val physical = Files.createDirectories(base.resolve("a/b/home"))
        Files.createDirectories(base.resolve("other"))
        Files.createDirectories(base.resolve("a/b/other"))
        val alias = Files.createSymbolicLink(base.resolve("h"), physical)
        Files.createSymbolicLink(physical.resolve("up"), Path.of("../other"))
        Files.createSymbolicLink(physical.resolve("plain"), base.resolve("other"))
        val reader = CandidateMetadata()
        val anchor = reader.anchor(alias)
        val up = checkNotNull(reader.inspect(anchor, alias.resolve("up"), 1).link)
        // As written, `h/up` → `../other` is `base/other`; the system finds `base/a/b/other`.
        assertEquals(Link(alias.resolve("up"), Path.of("../other"), base.resolve("a/b/other"), Link.Target.DIRECTORY), up)
        assertEquals(base.resolve("other"), reader.inspect(anchor, alias.resolve("plain"), 1).link?.pointsTo)
    }

    @Test fun aLinkWhoseTargetCannotBeReadIsStillALink() {
        val root = temporary.toRealPath()
        Files.createSymbolicLink(root.resolve("link"), Path.of("/denied/cache"))
        val reader = CandidateMetadata(object : CandidateMetadata.Access() {
            override fun realPath(path: Path): Path {
                if (path.endsWith("link")) throw AccessDeniedException(path.toString())
                return super.realPath(path)
            }
        })
        val result = reader.inspect(reader.anchor(root), root.resolve("link"), 1)
        assertState(result, Kind.LINK)
        assertEquals(Link(root.resolve("link"), Path.of("/denied/cache"), Path.of("/denied/cache"), Link.Target.UNREADABLE), result.link)
    }

    @Test fun permissionsAndGenericErrorsHaveTypedUnknownEvidence() {
        val reader = CandidateMetadata(object : CandidateMetadata.Access() {
            override fun attributes(path: Path): BasicFileAttributes {
                if (path.endsWith("denied")) throw AccessDeniedException(path.toString())
                if (path.endsWith("error")) throw IOException("Controlled I/O failure")
                return super.attributes(path)
            }
        })
        val anchor = reader.anchor(temporary)
        val denied = reader.inspect(anchor, temporary.resolve("denied/child"), 1)
        assertState(denied, Kind.INACCESSIBLE)
        assertReason(denied, Reason.ACCESS_DENIED)
        val error = reader.inspect(anchor, temporary.resolve("error"), 1)
        assertState(error, Kind.INACCESSIBLE)
        assertReason(error, Reason.IO_ERROR)
    }

    @Test fun intermediateReplacementAbortsBeforeReadingBelowLink() {
        Files.createDirectory(temporary.resolve("parent"))
        val outside = Files.createDirectory(temporary.resolve("outside"))
        val reads = AtomicInteger()
        val reader = CandidateMetadata(object : CandidateMetadata.Access() {
            override fun attributes(path: Path): BasicFileAttributes {
                assertFalse(path.endsWith("child"), "Traversed changed intermediate component")
                if (path.endsWith("parent") && reads.incrementAndGet() == 2) {
                    Files.move(path, path.resolveSibling("original"))
                    Files.createSymbolicLink(path, outside)
                }
                return super.attributes(path)
            }
        })
        val result = reader.inspect(reader.anchor(temporary), temporary.resolve("parent/child"), 1)
        assertState(result, Kind.UNKNOWN)
        assertReason(result, Reason.CHANGED)
    }

    @Test fun rootAliasReplacementInvalidatesRecordedAnchor() {
        val root = Files.createDirectory(temporary.resolve("root"))
        val other = Files.createDirectory(temporary.resolve("other"))
        val alias = Files.createSymbolicLink(temporary.resolve("alias"), root)
        val reader = CandidateMetadata()
        val anchor = reader.anchor(alias)
        Files.delete(alias)
        Files.createSymbolicLink(alias, other)
        val result = reader.inspect(anchor, alias.resolve("cache"), 1)
        assertState(result, Kind.UNKNOWN)
        assertReason(result, Reason.CHANGED)
    }

    @Test fun filesystemSeamExposesNoEnumerationOrContentOperations() {
        val names = HashSet<String>()
        for (method in CandidateMetadata.Access::class.java.declaredMethods) names.add(method.name)
        assertEquals(setOf("attributes", "realPath", "readLink"), names)
    }

    /** Fails on any probe outside `allowed`, and on resolving any path but the root and those in `resolvable`. */
    private fun guarded(lexicalRoot: Path, allowed: Set<Path>, resolvable: Set<Path> = setOf(lexicalRoot)): CandidateMetadata {
        return CandidateMetadata(object : CandidateMetadata.Access() {
            override fun realPath(path: Path): Path {
                assertTrue(path in resolvable, "Only the chosen root and link targets may be resolved physically: $path")
                return super.realPath(path)
            }
            override fun attributes(path: Path): BasicFileAttributes {
                assertTrue(allowed.contains(path), "Unexpected descendant/target probe: $path")
                return Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            }
        })
    }

    companion object {
        private fun assertState(result: CandidateObservation, kind: Kind) {
            assertEquals(kind, result.kind, result.toString())
        }
        private fun assertReason(result: CandidateObservation, reason: Reason) {
            assertTrue(result.diagnostics.any { d -> d.reason == reason }, result.toString())
        }
    }
}
