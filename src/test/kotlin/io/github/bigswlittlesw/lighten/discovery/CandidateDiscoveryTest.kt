package io.github.bigswlittlesw.lighten.discovery

import io.github.bigswlittlesw.lighten.config.CandidateCatalog
import io.github.bigswlittlesw.lighten.config.CandidateDiagnostic
import io.github.bigswlittlesw.lighten.config.CandidateParser
import io.github.bigswlittlesw.lighten.config.CandidateSource
import io.github.bigswlittlesw.lighten.discovery.CandidateDiscovery.Candidate
import io.github.bigswlittlesw.lighten.discovery.CandidateDiscovery.Result
import io.github.bigswlittlesw.lighten.discovery.CandidateDiscovery.SourceOutcome
import io.github.bigswlittlesw.lighten.discovery.CandidateDiscovery.SourceProblem
import io.github.bigswlittlesw.lighten.discovery.CandidateDiscovery.SourceStatus
import io.github.bigswlittlesw.lighten.discovery.CandidateObservation.Kind
import io.github.bigswlittlesw.lighten.discovery.CandidateObservation.Reason
import io.github.bigswlittlesw.lighten.HANG_LIMIT
import io.github.bigswlittlesw.lighten.diskTree
import io.github.bigswlittlesw.lighten.pollUntil
import io.github.bigswlittlesw.lighten.withoutHanging
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Predicate

class CandidateDiscoveryTest {
    @TempDir lateinit var temporary: Path

    @Test fun independentlyLoadsRealSharedFileAndRetainsProvenanceAndOverlap() {
        val shared = temporary.resolve("shared.json")
        // The built-in list has no nested entries, so your list adds one under its `.cache/uv`.
        Files.writeString(shared, Files.readString(Path.of("src/test/resources/suggestion-lists/shared.json"))
            .replace("{\"path\": \"team-cache\"", "{\"path\": \".cache/uv/archive-v0\"}, {\"path\": \"team-cache\""))
        Files.createDirectories(temporary.resolve(".cache/uv/archive-v0"))
        CandidateDiscovery().use { discovery ->
            discovery.refresh(temporary, shared)
            val result = awaitResult(discovery, ::finished)
            assertTrue(result.sources.all { s -> s.status == SourceStatus.CURRENT })
            val child = row(result, temporary.resolve(".cache/uv/archive-v0"))
            assertEquals(listOf(temporary.resolve(".cache/uv")), child.ancestors)
            val maven = row(result, temporary.resolve(".m2"))
            assertEquals(2, maven.catalog.definitions.size)
            // Your list's definition comes first, so its app and advice win.
            assertEquals(shared.toString(), maven.catalog.definitions.first().source.location)
            // The Lists line shows when your list's file last changed.
            assertEquals(
                Files.getLastModifiedTime(shared).toInstant(),
                result.sources.single { it.source.kind == CandidateSource.Kind.SHARED }.modified,
            )
            assertNull(result.sources.single { it.source.kind == CandidateSource.Kind.BUNDLED }.modified)
        }
    }

    @Test fun missingWrongKindMalformedOversizeAndLinkedSharedInputs() {
        val shared = temporary.resolve("shared.json")
        CandidateDiscovery().use { discovery ->
            discovery.refresh(temporary, shared)
            val result = awaitResult(discovery, ::finished)
            assertProblem(result, SourceProblem.Kind.MISSING)
            assertFalse(result.candidates.isEmpty())
            discovery.refresh(temporary, temporary)
            assertProblem(awaitResult(discovery, ::finished), SourceProblem.Kind.NOT_REGULAR)
            Files.writeString(shared, "{\"directories\": [")
            discovery.refresh(temporary, shared)
            val malformed = awaitResult(discovery, ::finished)
            assertEquals(CandidateDiagnostic.Kind.SYNTAX, shared(malformed).diagnostics.first().kind)
            Files.write(shared, ByteArray(CandidateParser.MAX_BYTES + 1))
            discovery.refresh(temporary, shared)
            assertEquals(CandidateDiagnostic.Kind.LIMIT, shared(awaitResult(discovery,
                    ::finished)).diagnostics.first().kind)
            Files.writeString(shared, "{\"directories\": [{\"path\": \"team-cache\"}]}")
            val link = Files.createSymbolicLink(temporary.resolve("shared-link"), shared)
            discovery.refresh(temporary, link)
            val linked = awaitResult(discovery, ::finished)
            assertEquals(SourceStatus.CURRENT, shared(linked).status)
            assertEquals(link.toString(), checkNotNull(shared(linked).catalog).source.location)
            discovery.refresh(temporary, null)
            val cleared = awaitResult(discovery, ::finished)
            assertEquals(1, cleared.sources.size)
            assertFalse(cleared.candidates.any { c -> c.catalog.sourcePath.endsWith("team-cache") })
        }
    }

    @Test fun failedSourceReadDropsItsCandidatesForTheSameRequest() {
        val input = AtomicReference(bytes("{\"directories\": [{\"path\": \"team-cache\"}]}"))
        Files.createDirectory(temporary.resolve("team-cache"))
        val location = temporary.resolve("shared")
        discovery(Workers(), AtomicLong(), { ignored -> input.get() }, "cache", CandidateMetadata()).use { discovery ->
            discovery.refresh(temporary, location)
            row(awaitResult(discovery, ::finished), temporary.resolve("team-cache"))
            input.set(bytes("{\"directories\": ["))
            discovery.refresh(temporary, location)
            val failed = awaitResult(discovery, ::finished)
            assertEquals(SourceStatus.FAILED, shared(failed).status)
            assertNull(shared(failed).catalog)
            assertFalse(failed.candidates.any { c -> c.catalog.sourcePath.endsWith("team-cache") })
        }
    }

    @Test fun unreadableAndMidReadFailureRejectSourceWithoutHidingBundled() {
        for (denied in listOf(true, false)) {
            discovery(Workers(), AtomicLong(), { path ->
                if (denied) throw AccessDeniedException(path.toString())
                // A read that fails after some bytes arrived: none of those bytes reach the parser.
                throw IOException("Read failed after partial bytes")
            }, "cache", CandidateMetadata()).use { discovery ->
                discovery.refresh(temporary, temporary.resolve("shared"))
                val result = awaitResult(discovery, ::finished)
                assertProblem(result, if (denied) SourceProblem.Kind.UNREADABLE else SourceProblem.Kind.IO_ERROR)
                assertEquals(1, result.candidates.size)
            }
        }
    }

    @Test fun sharedReadIsSingleFlightAcrossDeadlineRefreshCloseAndReopen() {
        val gate = Gate()
        val workers = Workers()
        val clock = AtomicLong()
        val calls = AtomicInteger()
        val worker = AtomicReference<Thread>()
        val discovery = discovery(workers, clock, { path ->
            calls.incrementAndGet()
            worker.set(Thread.currentThread())
            gate.block()
            bytes("{\"directories\": [{\"path\": \"late\"}]}")
        }, "cache", CandidateMetadata())
        try {
            withoutHanging { discovery.refresh(temporary, temporary.resolve("shared")) }
            assertTrue(gate.entered.await(HANG_LIMIT.toSeconds(), TimeUnit.SECONDS))
            // The run does not wait for the read of the user's list.
            awaitResult(discovery) { r -> !r.candidates.isEmpty()
                    && r.candidates.first().observation.kind == Kind.MISSING }
            worker.get().interrupt()
            clock.set(5_000_000_000L)
            assertProblem(discovery.snapshot(), SourceProblem.Kind.DEADLINE)
            for (i in 0 until 20) {
                withoutHanging { discovery.refresh(temporary, temporary.resolve("shared")) }
                assertProblem(discovery.snapshot(), SourceProblem.Kind.PREVIOUS_PENDING)
            }
            assertEquals(1, calls.get())
            assertTrue(workers.sharedRead.get())
            withoutHanging(discovery::close)
            assertTrue(discovery.snapshot().candidates.isEmpty())
            // Reopening a session with the same process workers cannot replace a stuck read.
            discovery(workers, clock, { path -> fail<Unit>("Extra read"); ByteArray(0) },
                    "cache", CandidateMetadata()).use { reopened ->
                reopened.refresh(temporary, temporary.resolve("shared"))
                assertProblem(reopened.snapshot(), SourceProblem.Kind.PREVIOUS_PENDING)
            }
        } finally {
            discovery.close()
            gate.release.countDown()
            await { !workers.sharedRead.get() }
        }
        assertTrue(discovery.snapshot().sources.isEmpty())
        assertThrows<IllegalStateException> { discovery.refresh(temporary, null) }
    }

    @Test fun lateCompletionBeyondDeadlineRejectedEvenWithoutEarlierPoll() {
        val gate = Gate()
        val clock = AtomicLong()
        val workers = Workers()
        try {
            discovery(workers, clock, { path ->
                gate.block()
                bytes("{\"directories\": [{\"path\": \"late\"}]}")
            }, "cache", CandidateMetadata()).use { discovery ->
                discovery.refresh(temporary, temporary.resolve("shared"))
                assertTrue(gate.entered.await(HANG_LIMIT.toSeconds(), TimeUnit.SECONDS))
                clock.set(5_000_000_001L)
                gate.release.countDown()
                await { !workers.sharedRead.get() }
                val result = discovery.snapshot()
                assertProblem(result, SourceProblem.Kind.DEADLINE)
                assertNull(shared(result).catalog)
                assertFalse(result.candidates.any { c -> c.catalog.sourcePath.endsWith("late") })
            }
        } finally { gate.release.countDown() }
    }

    @Test fun rootAndLocationChangeAndCancellationIgnoreObsoleteSourceResults() {
        for (cancel in listOf(false, true)) {
            val gate = Gate()
            val workers = Workers()
            val other = Files.createTempDirectory(temporary, "other")
            try {
                discovery(workers, AtomicLong(), { path ->
                    gate.block()
                    bytes("{\"directories\": [{\"path\": \"obsolete\"}]}")
                }, "cache", CandidateMetadata()).use { discovery ->
                    val first = discovery.refresh(temporary, temporary.resolve("shared"))
                    assertTrue(gate.entered.await(HANG_LIMIT.toSeconds(), TimeUnit.SECONDS))
                    if (cancel) discovery.cancel()
                    else discovery.refresh(other, other.resolve("new-location"))
                    gate.release.countDown()
                    await { !workers.sharedRead.get() }
                    val result = discovery.snapshot()
                    assertTrue(result.generation > first)
                    assertFalse(result.candidates.any { c -> c.catalog.sourcePath.endsWith("obsolete") })
                    if (cancel) {
                        assertNull(result.request)
                        assertTrue(result.sources.isEmpty())
                    } else {
                        assertEquals(other, checkNotNull(result.request).root)
                        assertProblem(result, SourceProblem.Kind.PREVIOUS_PENDING)
                    }
                }
            } finally { gate.release.countDown() }
        }
    }

    @Test fun failedCandidateRefreshReportsOnlyTheNewFailure() {
        Files.createDirectory(temporary.resolve("cache"))
        Files.write(temporary.resolve("cache/a"), ByteArray(17))
        val fail = AtomicInteger()
        val metadata = CandidateMetadata(object : CandidateMetadata.Access() {
            override fun attributes(path: Path): BasicFileAttributes {
                if (path.endsWith("cache") && fail.get() == 1) throw AccessDeniedException(path.toString())
                return super.attributes(path)
            }
        })
        discovery(Workers(), AtomicLong(), { path -> bytes("{\"directories\": []}") }, "cache", metadata).use { discovery ->
            discovery.refresh(temporary, null)
            awaitResult(discovery, ::finished)
            fail.set(1)
            val second = discovery.refresh(temporary, null)
            val result = awaitResult(discovery) { r -> !r.candidates.isEmpty() && r.candidates.first()
                    .observation.diagnostics.any { d -> d.reason == Reason.ACCESS_DENIED } }
            val observation = result.candidates.first().observation
            assertEquals(Kind.INACCESSIBLE, observation.kind)
            assertEquals(second, observation.generation)
        }
    }

    @Test fun inspectionsStayBoundedAcrossTimeoutRefreshCloseAndReopen() {
        val gate = Gate()
        val inspected = ConcurrentHashMap.newKeySet<Path>()
        val inFlight = AtomicInteger()
        val most = AtomicInteger()
        val workers = Workers(2)
        val clock = AtomicLong()
        for (i in 0 until 6) Files.createDirectory(temporary.resolve("cache$i"))
        val metadata = CandidateMetadata(object : CandidateMetadata.Access() {
            override fun attributes(path: Path): BasicFileAttributes {
                if (!path.fileName.toString().startsWith("cache")) return super.attributes(path)
                inspected.add(path)
                most.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
                try {
                    gate.block()
                    return super.attributes(path)
                } finally { inFlight.decrementAndGet() }
            }
        })
        val discovery = discovery(workers, clock, { path -> bytes("{\"directories\": []}") },
                "cache0,cache1,cache2,cache3,cache4,cache5", metadata)
        try {
            discovery.refresh(temporary, null)
            await { inspected.size == 2 }
            val waiting = discovery.snapshot()
            // Queued and running rows read the same.
            assertTrue(waiting.candidates.all { c -> c.observation.kind == Kind.PENDING && c.observation.diagnostics.isEmpty() })
            clock.set(METADATA_NANOS)
            val timed = discovery.snapshot()
            assertEquals(2, timed.candidates.count { c -> c.observation.kind == Kind.UNKNOWN
                    && c.observation.diagnostics.any { d -> d.reason == Reason.DEADLINE } })
            assertEquals(4, timed.candidates.count { c -> c.observation.kind == Kind.PENDING })
            // Each refresh replaces the run, so every row is pending again; none adds inspections while the
            // earlier ones are stuck.
            for (i in 0 until 20) {
                withoutHanging { discovery.refresh(temporary, null) }
                val replaced = discovery.snapshot()
                assertNull(replaced.rootFailure)
                assertTrue(replaced.candidates.all { c -> c.observation.kind == Kind.PENDING })
            }
            withoutHanging(discovery::close)
            discovery(workers, clock, { p -> bytes("{\"directories\": []}") },
                    "cache0", CandidateMetadata()).use { reopened ->
                reopened.refresh(temporary, null)
                assertEquals(Kind.PENDING, reopened.snapshot().candidates.first().observation.kind)
            }
            assertEquals(2, inspected.size)
        } finally {
            discovery.close()
            gate.release.countDown()
            drain(workers)
        }
        // The closed generation's queued paths never started.
        assertEquals(2, inspected.size)
        assertEquals(2, most.get())
        assertTrue(discovery.snapshot().candidates.isEmpty())
    }

    @Test fun inspectionsRunConcurrentlyInCatalogOrderWithoutSnapshotDrivingWork() {
        val names = (0 until 8).map { "cache$it" }
        for (name in names) Files.createDirectory(temporary.resolve(name))
        val inFlight = AtomicInteger()
        val most = AtomicInteger()
        val full = CountDownLatch(3)
        val metadata = CandidateMetadata(object : CandidateMetadata.Access() {
            override fun attributes(path: Path): BasicFileAttributes {
                if (!path.fileName.toString().startsWith("cache")) return super.attributes(path)
                most.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
                full.countDown()
                // Hold the first inspections until three run at once.
                full.await(HANG_LIMIT.toSeconds(), TimeUnit.SECONDS)
                try { return super.attributes(path) } finally { inFlight.decrementAndGet() }
            }
        })
        val workers = Workers(3)
        discovery(workers, AtomicLong(), { path -> bytes("{\"directories\": []}") },
                names.joinToString(","), metadata).use { discovery ->
            val generation = discovery.refresh(temporary, null)
            drain(workers)
            val result = discovery.snapshot()
            assertTrue(finished(result), "Work depended on snapshot polling")
            assertEquals(names, result.candidates.map { c -> c.catalog.sourcePath.fileName.toString() })
            assertTrue(result.candidates.all { c -> c.observation.kind == Kind.DIRECTORY
                    && c.observation.generation == generation })
        }
        assertEquals(3, most.get())
    }

    @Test fun snapshotRowsComeFromOneGenerationWhileInspectionsComplete() {
        val names = (0 until 30).map { "cache$it" }
        for (name in names) Files.createDirectory(temporary.resolve(name))
        discovery(Workers(4), AtomicLong(), { path -> bytes("{\"directories\": []}") },
                names.joinToString(","), CandidateMetadata()).use { discovery ->
            discovery.refresh(temporary, null)
            val generation = discovery.refresh(temporary, null)
            awaitResult(discovery) { r ->
                assertEquals(generation, r.generation)
                assertTrue(r.candidates.all { c -> c.observation.generation == generation }, r.toString())
                finished(r)
            }
        }
    }

    @Test fun obsoleteInspectionCannotAttachToNewRootAndNewRunFollowsIt() {
        val gate = Gate()
        val firstRoot = Files.createDirectory(temporary.resolve("first"))
        val physicalFirstRoot = firstRoot.toRealPath()
        val secondRoot = Files.createDirectory(temporary.resolve("second"))
        Files.createDirectory(firstRoot.resolve("cache"))
        val metadata = CandidateMetadata(object : CandidateMetadata.Access() {
            override fun attributes(path: Path): BasicFileAttributes {
                if (path.equals(physicalFirstRoot.resolve("cache"))) gate.block()
                return super.attributes(path)
            }
        })
        try {
            discovery(Workers(), AtomicLong(), { path -> bytes("{\"directories\": []}") }, "cache", metadata).use { discovery ->
                discovery.refresh(firstRoot, null)
                assertTrue(gate.entered.await(HANG_LIMIT.toSeconds(), TimeUnit.SECONDS))
                val generation = discovery.refresh(secondRoot, null)
                // The new run waits for the stuck one; its row is queued, not failed.
                val waiting = discovery.snapshot().candidates.first().observation
                assertEquals(Kind.PENDING, waiting.kind)
                assertTrue(waiting.diagnostics.isEmpty())
                gate.release.countDown()
                val result = awaitResult(discovery, ::finished)
                val observation = result.candidates.first().observation
                assertEquals(generation, observation.generation)
                assertEquals(secondRoot.resolve("cache"), observation.path)
                assertEquals(Kind.MISSING, observation.kind)
            }
        } finally { gate.release.countDown() }
    }

    @Test fun rootProbeDeadlineDoesNotBlockSharedParsingOrClose() {
        val gate = Gate()
        val clock = AtomicLong()
        val metadata = CandidateMetadata(object : CandidateMetadata.Access() {
            override fun realPath(path: Path): Path {
                gate.block()
                return super.realPath(path)
            }
        })
        try {
            discovery(Workers(), clock, { p -> bytes("{\"directories\": [{\"path\": \"team\"}]}") }, "cache", metadata).use { discovery ->
                discovery.refresh(temporary, temporary.resolve("shared"))
                assertTrue(gate.entered.await(HANG_LIMIT.toSeconds(), TimeUnit.SECONDS))
                awaitResult(discovery) { r -> shared(r).status == SourceStatus.CURRENT }
                clock.set(METADATA_NANOS)
                val result = discovery.snapshot()
                assertEquals(Reason.DEADLINE, checkNotNull(result.rootFailure).reason)
                assertTrue(result.candidates.all { c -> c.observation.kind == Kind.UNKNOWN })
                withoutHanging(discovery::close)
            }
        } finally { gate.release.countDown() }
    }

    @Test fun lateRootCompletionIsRejectedAndNothingIsInspected() {
        val gate = Gate()
        val clock = AtomicLong()
        val workers = Workers()
        val inspected = AtomicInteger()
        Files.createDirectory(temporary.resolve("cache"))
        val metadata = CandidateMetadata(object : CandidateMetadata.Access() {
            override fun realPath(path: Path): Path {
                gate.block()
                return super.realPath(path)
            }
            override fun attributes(path: Path): BasicFileAttributes {
                if (path.endsWith("cache")) inspected.incrementAndGet()
                return super.attributes(path)
            }
        })
        try {
            discovery(workers, clock, { p -> ByteArray(0) }, "cache", metadata).use { discovery ->
                discovery.refresh(temporary, null)
                assertTrue(gate.entered.await(HANG_LIMIT.toSeconds(), TimeUnit.SECONDS))
                clock.set(METADATA_NANOS)
                gate.release.countDown()
                drain(workers)
                val result = discovery.snapshot()
                assertEquals(Reason.DEADLINE, checkNotNull(result.rootFailure).reason)
                assertEquals(Kind.UNKNOWN, result.candidates.first().observation.kind)
                assertEquals(0, inspected.get())
            }
        } finally { gate.release.countDown() }
    }

    @Test fun blockedWorkerDoesNotKeepJvmAlive() {
        val process = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), ExitProbe::class.java.name, temporary.toString())
                .redirectErrorStream(true).start()
        try {
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Discovery worker kept JVM alive")
            assertEquals(0, process.exitValue(), String(process.inputStream.readAllBytes(), StandardCharsets.UTF_8))
        } finally { if (process.isAlive) process.destroyForcibly() }
    }

    class ExitProbe {
        companion object {
            // JVM entry point for the subprocess this test launches.
            @JvmStatic fun main(args: Array<String>) {
                val gate = Gate()
                val metadataGate = Gate()
                val metadata = CandidateMetadata(object : CandidateMetadata.Access() {
                    override fun realPath(path: Path): Path {
                        metadataGate.block()
                        return super.realPath(path)
                    }
                })
                discovery(Workers(), AtomicLong(), { path ->
                    gate.block()
                    bytes("{\"directories\": []}")
                }, "cache", metadata).use { discovery ->
                    val root = Path.of(args[0])
                    discovery.refresh(root, root.resolve("shared"))
                    if (!gate.entered.await(HANG_LIMIT.toSeconds(), TimeUnit.SECONDS)) throw AssertionError("Reader did not start")
                    if (!metadataGate.entered.await(HANG_LIMIT.toSeconds(), TimeUnit.SECONDS)) throw AssertionError("Metadata did not start")
                }
            }
        }
    }

    @Test fun sharedOnlyPathsFormSecondBatchAndRunNeverWaitsForSharedRead() {
        val gate = Gate()
        Files.createDirectory(temporary.resolve("cache"))
        Files.createDirectory(temporary.resolve("team"))
        try {
            discovery(Workers(), AtomicLong(), { path ->
                gate.block()
                bytes("{\"directories\": [{\"path\": \"team\"}, {\"path\": \"cache\"}]}")
            }, "cache", CandidateMetadata()).use { discovery ->
                val generation = discovery.refresh(temporary, temporary.resolve("shared"))
                assertTrue(gate.entered.await(HANG_LIMIT.toSeconds(), TimeUnit.SECONDS))
                val early = awaitResult(discovery) { r -> r.candidates.first().observation.kind == Kind.DIRECTORY }
                assertEquals(SourceStatus.PENDING, shared(early).status)
                gate.release.countDown()
                val result = awaitResult(discovery, ::finished)
                for (name in listOf("team", "cache")) {
                    val observation = row(result, temporary.resolve(name)).observation
                    assertEquals(Kind.DIRECTORY, observation.kind)
                    assertEquals(generation, observation.generation)
                }
            }
        } finally { gate.release.countDown() }
    }

    @Test fun sharedResultsProceedWhenBundledSourceFails() {
        Files.createDirectory(temporary.resolve("team"))
        CandidateDiscovery(Workers(), System::nanoTime,
                { p -> bytes("{\"directories\": [{\"path\": \"team\"}]}") }, { root -> CandidateCatalog.Snapshot.of(
                CandidateCatalog.BUNDLED, root, listOf(), listOf(CandidateDiagnostic(
                CandidateCatalog.BUNDLED, CandidateDiagnostic.Kind.RESOURCE, 0, 0, 0, "", "",
                "Controlled packaging failure"))) },
                CandidateMetadata()).use { discovery ->
            discovery.refresh(temporary, temporary.resolve("shared"))
            // The bundled list is parsed during the refresh, so its outcome is known at once.
            assertEquals(SourceStatus.FAILED, discovery.snapshot().sources.first().status)
            val result = awaitResult(discovery, ::finished)
            assertEquals(SourceStatus.FAILED, result.sources.first().status)
            assertEquals(SourceStatus.CURRENT, shared(result).status)
            assertEquals(Kind.DIRECTORY, result.candidates.first().observation.kind)
        }
    }

    @Test fun lateMetadataCompletionAfterRefreshIsUnknown() {
        val gate = Gate()
        val clock = AtomicLong()
        val workers = Workers()
        val block = AtomicInteger()
        Files.createDirectory(temporary.resolve("cache"))
        val metadata = CandidateMetadata(object : CandidateMetadata.Access() {
            override fun attributes(path: Path): BasicFileAttributes {
                if (path.endsWith("cache") && block.get() == 1) gate.block()
                return super.attributes(path)
            }
        })
        try {
            discovery(workers, clock, { p -> bytes("{\"directories\": []}") }, "cache", metadata).use { discovery ->
                discovery.refresh(temporary, null)
                awaitResult(discovery, ::finished)
                block.set(1)
                val second = discovery.refresh(temporary, null)
                assertTrue(gate.entered.await(HANG_LIMIT.toSeconds(), TimeUnit.SECONDS))
                clock.set(METADATA_NANOS)
                // No snapshot until the late completion has been recorded.
                gate.release.countDown()
                drain(workers)
                val observation = discovery.snapshot().candidates.first().observation
                assertEquals(Kind.UNKNOWN, observation.kind)
                assertEquals(second, observation.generation)
                assertTrue(observation.diagnostics.any { d -> d.reason == Reason.DEADLINE })
            }
        } finally { gate.release.countDown() }
    }

    /**
     * An each-subdirectory suggestion lists its parent once per check, then inspects each subdirectory found. A kept
     * subdirectory is still a row, and the parent is no row's ancestor. A subdirectory made later is a new row on the
     * next check. Discovery never changes the disk.
     */
    @Test fun eachSubdirectoryListsItsParentOnceAndFindsNewSubdirectories() {
        val home = temporary.toRealPath()
        val gradle = Files.createDirectories(home.resolve(".gradle"))
        for (name in listOf("caches", "init.d", ".tmp")) Files.createDirectory(gradle.resolve(name))
        Files.writeString(gradle.resolve("gradle.properties"), "")
        val listings = AtomicInteger()
        val metadata = CandidateMetadata(object : CandidateMetadata.Access() {
            override fun subdirectories(directory: Path): List<Path> {
                listings.incrementAndGet()
                return super.subdirectories(directory)
            }
        })
        val workers = Workers()
        CandidateDiscovery(workers, AtomicLong()::get, { p -> bytes("{}") }, { root ->
            CandidateParser().parse(CandidateCatalog.BUNDLED, root, bytes(GRADLE_LIST))
        }, metadata).use { discovery ->
            val before = diskTree(home)
            discovery.refresh(home, null)
            val result = awaitResult(discovery, ::finished)
            assertEquals(1, listings.get())
            assertEquals(
                listOf(".cache/uv", ".gradle", ".gradle/.tmp", ".gradle/caches", ".gradle/init.d"),
                result.candidates.map { home.relativize(it.catalog.sourcePath).toString() },
            )
            val parent = row(result, gradle)
            assertTrue(parent.catalog.listsSubdirectories)
            assertEquals(listOf(".tmp", "caches", "init.d").map(gradle::resolve), parent.observation.subdirectories)
            val caches = row(result, gradle.resolve("caches"))
            assertEquals(Kind.DIRECTORY, caches.observation.kind)
            assertFalse(caches.catalog.onKeepList)
            assertEquals(listOf<Path>(), caches.ancestors)
            assertTrue(row(result, gradle.resolve("init.d")).catalog.onKeepList)
            assertEquals(before, diskTree(home))
            Files.createDirectory(gradle.resolve("native"))
            discovery.refresh(home, null)
            val again = awaitResult(discovery) { finished(it) && it.candidates.any { c -> c.catalog.sourcePath.endsWith("native") } }
            assertEquals(Kind.DIRECTORY, row(again, gradle.resolve("native")).observation.kind)
            assertEquals(2, listings.get())
        }
    }

    /**
     * Listing a parent is part of its inspection and has the same time limit. A listing that takes too long leaves the
     * parent unknown with no subdirectories, and its late result is refused.
     */
    @Test fun aParentListingThatTakesTooLongListsNothing() {
        val gate = Gate()
        val clock = AtomicLong()
        val workers = Workers()
        val home = temporary.toRealPath()
        Files.createDirectories(home.resolve(".gradle/caches"))
        val metadata = CandidateMetadata(object : CandidateMetadata.Access() {
            override fun subdirectories(directory: Path): List<Path> {
                gate.block()
                return super.subdirectories(directory)
            }
        })
        try {
            CandidateDiscovery(workers, clock::get, { p -> bytes("{}") }, { root ->
                CandidateParser().parse(CandidateCatalog.BUNDLED, root, bytes(GRADLE_LIST))
            }, metadata).use { discovery ->
                discovery.refresh(home, null)
                assertTrue(gate.entered.await(HANG_LIMIT.toSeconds(), TimeUnit.SECONDS))
                clock.set(METADATA_NANOS)
                val timed = discovery.snapshot()
                val parent = row(timed, home.resolve(".gradle"))
                assertEquals(Kind.UNKNOWN, parent.observation.kind)
                assertTrue(parent.observation.diagnostics.any { d -> d.reason == Reason.DEADLINE })
                assertNull(parent.observation.subdirectories)
                gate.release.countDown()
                drain(workers)
                val late = discovery.snapshot()
                assertEquals(Kind.UNKNOWN, row(late, home.resolve(".gradle")).observation.kind)
                assertTrue(late.candidates.none { it.catalog.sourcePath.startsWith(home.resolve(".gradle/caches")) })
            }
        } finally { gate.release.countDown() }
    }

    @Test fun sharedTimeoutDropsItsCandidatesUntilAReadSucceeds() {
        val gate = Gate()
        val workers = Workers()
        val clock = AtomicLong()
        val contents = AtomicReference(bytes("{\"directories\": [{\"path\": \"team\"}]}"))
        val block = AtomicInteger()
        try {
            discovery(workers, clock, { p ->
                if (block.get() == 1) gate.block()
                contents.get()
            }, "cache", CandidateMetadata()).use { discovery ->
                val location = temporary.resolve("shared")
                discovery.refresh(temporary, location)
                row(awaitResult(discovery, ::finished), temporary.resolve("team"))
                block.set(1)
                discovery.refresh(temporary, location)
                assertTrue(gate.entered.await(HANG_LIMIT.toSeconds(), TimeUnit.SECONDS))
                clock.set(5_000_000_000L)
                val timed = discovery.snapshot()
                assertEquals(SourceStatus.FAILED, shared(timed).status)
                assertProblem(timed, SourceProblem.Kind.DEADLINE)
                assertTrue(timed.candidates.none { c -> c.catalog.sourcePath.endsWith("team") })
                gate.release.countDown()
                await { !workers.sharedRead.get() }
                block.set(0)
                discovery.refresh(temporary, location)
                val refreshed = awaitResult(discovery, ::finished)
                assertEquals(SourceStatus.CURRENT, shared(refreshed).status)
                row(refreshed, temporary.resolve("team"))
            }
        } finally { gate.release.countDown() }
    }

    companion object {
        private const val GRADLE_LIST = """{"directories": [{"path": ".cache/uv"},
            {"path": ".gradle", "each-subdirectory": true, "keep": ["init.d"]}]}"""

        private fun discovery(workers: Workers, clock: AtomicLong, reader: (Path) -> ByteArray,
                              paths: String, metadata: CandidateMetadata): CandidateDiscovery {
            val json = paths.split(",").joinToString(", ", "{\"directories\": [", "]}") { path -> "{\"path\": \"$path\"}" }
            return CandidateDiscovery(workers, clock::get, reader,
                    { root -> CandidateParser().parse(CandidateCatalog.BUNDLED, root, bytes(json)) }, metadata)
        }
        private fun bytes(value: String): ByteArray = value.toByteArray(StandardCharsets.UTF_8)
        private fun shared(result: Result): SourceOutcome {
            return result.sources.first { s -> s.source.kind == CandidateSource.Kind.SHARED }
        }
        private fun row(result: Result, path: Path): Candidate {
            return result.candidates.first { c -> c.catalog.sourcePath.equals(path) }
        }
        private fun assertProblem(result: Result, kind: SourceProblem.Kind) {
            assertEquals(kind, shared(result).problems.first().kind, result.toString())
        }
        private fun finished(result: Result): Boolean {
            return !result.sources.isEmpty() && result.sources.none { s -> s.status == SourceStatus.PENDING }
                    && result.candidates.none { c -> c.observation.kind == Kind.PENDING }
        }
        private fun awaitResult(discovery: CandidateDiscovery, condition: Predicate<Result>): Result {
            val result = AtomicReference<Result>()
            await { result.set(discovery.snapshot()); condition.test(result.get()) }
            return result.get()
        }
        private fun await(condition: () -> Boolean) = pollUntil("Controlled work did not complete", condition)

        /** Returns once every run chained so far has returned, and with it every inspection it started. */
        private fun drain(workers: Workers) {
            val drained = CountDownLatch(1)
            workers.chain { drained.countDown() }
            assertTrue(drained.await(HANG_LIMIT.toSeconds(), TimeUnit.SECONDS), "Discovery runs did not finish")
        }
    }

    private class Gate {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        fun block() {
            entered.countDown()
            var finished = false
            while (!finished) {
                try { release.await(); finished = true }
                catch (ignored: InterruptedException) { /* Models I/O that interruption cannot cancel. */ }
            }
        }
    }
}
