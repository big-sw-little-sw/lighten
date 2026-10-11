package io.github.bigswlittlesw.lighten.application

import io.github.bigswlittlesw.lighten.application.BrowseDraft.Status
import io.github.bigswlittlesw.lighten.config.CandidateCatalog
import io.github.bigswlittlesw.lighten.config.CandidateDefinition
import io.github.bigswlittlesw.lighten.config.CandidateParser
import io.github.bigswlittlesw.lighten.discovery.CandidateDiscovery
import io.github.bigswlittlesw.lighten.discovery.CandidateMetadata
import io.github.bigswlittlesw.lighten.discovery.CandidateObservation
import io.github.bigswlittlesw.lighten.discovery.CandidateObservation.Link
import io.github.bigswlittlesw.lighten.discovery.Workers
import io.github.bigswlittlesw.lighten.pollUntil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class BrowseDraftTest {
    @TempDir lateinit var temporary: Path

    @Test fun joinsTheDraftsRelocationsWithSuggestionsBySource() {
        val root = Files.createDirectory(temporary.resolve("home"))
        Files.createDirectories(root.resolve(".m2"))
        Suggestions(worker()).use { suggestions ->
            val result = settled(suggestions, root)
            // A row being typed has no source yet; a row outside every list is still an entry.
            val sources = listOf(root.resolve(".m2"), null, temporary.resolve("outside"))
            val draft = BrowseDraft(root, sources.map { BrowseDraft.Paths(it, null) }, result)
            val entries = draft.entries()
            assertEquals(listOf(0, 1, 2), entries.take(3).map { it.row })
            assertEquals(2, checkNotNull(entries[0].discovery).catalog.definitions.size)
            assertNull(entries[1].discovery)
            assertNull(entries[2].discovery)
            // Each suggestion appears once: the one in the draft is not listed again. Both lists name .m2.
            assertEquals(result.candidates.size - 1, entries.size - 3)
            assertTrue(entries.drop(3).none { it.sourcePath == root.resolve(".m2") || it.row != null })
            assertEquals(Status.InDraft(0), draft.status(entries[0]), "already in the draft")
        }
    }

    @Test fun onlyDirectoryOrMissingSuggestionsCanBeAdded() {
        val root = Files.createDirectory(temporary.resolve("home"))
        val path = root.resolve("absent-cache")
        Suggestions(worker()).use { suggestions ->
            val result = settled(suggestions, root)
            val candidate = result.candidates.single { it.catalog.sourcePath == path }
            assertEquals(CandidateObservation.Kind.MISSING, candidate.observation.kind)
            for (kind in CandidateObservation.Kind.entries) {
                val observation = CandidateObservation(path, kind, null, result.generation, Instant.now(), listOf())
                val seen = CandidateDiscovery.Result(result.generation, result.request, result.sources,
                    listOf(CandidateDiscovery.Candidate(candidate.catalog, observation, candidate.ancestors)), result.rootFailure)
                val draft = BrowseDraft(root, listOf(), seen)
                val eligible = kind == CandidateObservation.Kind.DIRECTORY || kind == CandidateObservation.Kind.MISSING
                assertEquals(eligible, draft.status(draft.entries().single()) is Status.CanAdd, kind.toString())
            }
        }
    }

    /**
     * Only a link to a real directory outside the source root can be taken over, with where it points as the target;
     * the problem names why not. A link is never added as a directory.
     */
    @Test fun aLinkCanBeTakenOverOnlyWhenItPointsToADirectoryOutside() {
        val root = Path.of("/home/u")
        for (target in Link.Target.entries) {
            val link = Link(root.resolve("cache"), Path.of("/data/cache"), Path.of("/data/cache"), target)
            val draft = BrowseDraft(root, listOf(), result(root, linkRow(root.resolve("cache"), link)))
            val expected = if (target == Link.Target.DIRECTORY) Status.CanAdd(link.path, link)
            else Status.LinkProblem(link, BrowseDraft.Problem.Target(target))
            assertEquals(expected, draft.status(draft.entries().single()), target.toString())
        }
    }

    /**
     * A take-over is refused when it would overlap a relocation in the draft, naming that relocation: one inside the
     * link, one around it, or one with the same target. Overlaps between relocations already in the draft do not
     * refuse it, as they do not refuse an addition in Configuration.
     */
    @Test fun aTakeOverThatOverlapsTheDraftNamesTheRelocation() {
        val root = Path.of("/home/u")
        val link = Link(root.resolve(".cache"), Path.of("/data/cache"), Path.of("/data/cache"), Link.Target.DIRECTORY)
        val child = linkRow(root.resolve(".cache/pip"), link, CandidateObservation.Kind.BLOCKED_BY_LINK)
        fun status(vararg relocations: BrowseDraft.Paths, path: Path = root.resolve(".cache/pip"), seen: CandidateDiscovery.Result = result(root, child)): Status {
            val draft = BrowseDraft(root, relocations.toList(), seen)
            return draft.status(draft.entries().single { it.sourcePath == path })
        }
        fun overlap(other: Path?) = Status.LinkProblem(link, BrowseDraft.Problem.Overlap(other))
        val inside = root.resolve(".cache/pip")
        assertEquals(Status.CanAdd(link.path, link), status(BrowseDraft.Paths(root.resolve("other"), Path.of("/data/other"))))
        assertEquals(overlap(root), status(BrowseDraft.Paths(root, Path.of("/elsewhere/u"))))
        // A child of the linked parent in the configuration: its own row is in the draft, and a sibling's take-over
        // would contain it.
        assertEquals(Status.InDraft(0), status(BrowseDraft.Paths(inside, Path.of("/local/pip"))))
        val sibling = linkRow(root.resolve(".cache/uv"), link, CandidateObservation.Kind.BLOCKED_BY_LINK)
        assertEquals(
            overlap(inside),
            status(BrowseDraft.Paths(inside, Path.of("/local/pip")), path = root.resolve(".cache/uv"), seen = result(root, child, sibling)),
        )
        // Another relocation's target is where the link points.
        assertEquals(overlap(root.resolve("owner")), status(BrowseDraft.Paths(root.resolve("owner"), Path.of("/data/cache"))))
        // Two relocations in the draft that overlap each other leave the take-over alone.
        assertEquals(
            Status.CanAdd(link.path, link),
            status(BrowseDraft.Paths(root.resolve("a"), Path.of("/x/a")), BrowseDraft.Paths(root.resolve("a/b"), Path.of("/y/b"))),
        )
        // A link that points to a directory holding the link overlaps itself.
        val around = Link(root.resolve(".cache"), Path.of("/home"), Path.of("/home"), Link.Target.DIRECTORY)
        val aroundDraft = BrowseDraft(root, listOf(), result(root, linkRow(root.resolve(".cache"), around)))
        assertEquals(
            Status.LinkProblem(around, BrowseDraft.Problem.Overlap(null)), aroundDraft.status(aroundDraft.entries().single()),
        )
    }

    /**
     * Once the linked parent is in the draft, the directories under it move with it, and the parent's own row knows its
     * link. Taken out again, it is kept and can be taken over, not added as a plain directory.
     */
    @Test fun aLinkedParentInTheDraftCoversItsChildren() {
        val root = Path.of("/home/u")
        val parent = root.resolve(".cache")
        val link = Link(parent, Path.of("/data/cache"), Path.of("/data/cache"), Link.Target.DIRECTORY)
        val seen = result(root, linkRow(parent.resolve("pip"), link, CandidateObservation.Kind.BLOCKED_BY_LINK))
        val draft = BrowseDraft(root, listOf(BrowseDraft.Paths(parent, Path.of("/data/cache"))), seen)
        val (row, child) = draft.entries()
        assertEquals(Status.MovesWith(0, parent), draft.status(child))
        assertEquals(link, draft.link(row))
        val kept = BrowseDraft(root, listOf(), seen, kept = setOf(parent))
        assertEquals(Status.CanAdd(parent, link), kept.status(kept.entries().single { it.sourcePath == parent }))
        val ignored = BrowseDraft(root, listOf(), seen, ignored = setOf(parent))
        assertEquals(Status.Ignored, ignored.status(ignored.entries().single { it.sourcePath == parent }))
        assertEquals(
            Status.LinkProblem(link, BrowseDraft.Problem.Ignored),
            ignored.status(ignored.entries().single { it.sourcePath == parent.resolve("pip") }),
        )
    }

    private fun linkRow(
        path: Path, link: Link, kind: CandidateObservation.Kind = CandidateObservation.Kind.LINK,
    ): CandidateDiscovery.Candidate {
        val definition = CandidateDefinition(
            path, CandidateCatalog.BUNDLED, 1, "directories[0]", path.fileName.toString(), null, null, null, null,
        )
        return CandidateDiscovery.Candidate(
            CandidateCatalog.Candidate(path, listOf(definition)),
            CandidateObservation(path, kind, link, 1, Instant.now(), listOf()), listOf(),
        )
    }

    private fun result(root: Path, vararg candidates: CandidateDiscovery.Candidate) =
        CandidateDiscovery.Result(1, CandidateDiscovery.Request.of(root, null), listOf(), candidates.toList(), null)

    /** A check for other roots forgets the last result, and a result from an earlier check is never taken. */
    @Test fun keepsOnlyAResultForTheCurrentCheck() {
        val first = Files.createDirectory(temporary.resolve("first"))
        val second = Files.createDirectory(temporary.resolve("second"))
        Suggestions(worker()).use { suggestions ->
            settled(suggestions, first, null)
            suggestions.check(second, null)
            assertEquals(CandidateDiscovery.Request.of(second, null), suggestions.request)
            val result = suggestions.result()
            assertTrue(result == null || result.request == CandidateDiscovery.Request.of(second, null))
            assertEquals(second, settled(suggestions, second, null).request?.root)
        }
    }

    private fun shared(): Path {
        val path = temporary.resolve("shared.json")
        if (!Files.exists(path)) Files.copy(Path.of("src/test/resources/suggestion-lists/shared.json"), path)
        return path
    }

    private fun worker(): CandidateDiscovery {
        val bundled = Files.readAllBytes(Path.of("src/test/resources/suggestion-lists/bundled.json"))
        return CandidateDiscovery(Workers(), System::nanoTime, Files::readAllBytes,
            { root -> CandidateParser().parse(CandidateCatalog.BUNDLED, root, bundled) }, CandidateMetadata())
    }

    /** Checks `root` with the fixture lists and waits until every suggestion has been looked at. */
    private fun settled(suggestions: Suggestions, root: Path, list: Path? = shared()): CandidateDiscovery.Result {
        suggestions.check(root, list)
        var result: CandidateDiscovery.Result? = null
        pollUntil("Discovery fixture did not finish") {
            result = suggestions.result()?.takeIf { latest ->
                latest.sources.none { it.status == CandidateDiscovery.SourceStatus.PENDING } &&
                    latest.candidates.none { it.observation.kind == CandidateObservation.Kind.PENDING }
            }
            result != null
        }
        return checkNotNull(result)
    }
}
