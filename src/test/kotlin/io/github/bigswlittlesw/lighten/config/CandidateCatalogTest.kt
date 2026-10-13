package io.github.bigswlittlesw.lighten.config

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import kotlinx.serialization.json.Json
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

class CandidateCatalogTest {
    private val parser = CandidateParser()

    @TempDir lateinit var temporary: Path

    @Test fun sameSchemaAndLexicalResolutionUnderBothRoots() {
        for (root in listOf(HOME, OTHER)) {
            for (source in listOf(CandidateCatalog.BUNDLED, SHARED)) {
                val snapshot = parser.parse(source, root, Files.readAllBytes(FIXTURES.resolve("bundled.json")))
                assertTrue(snapshot.accepted(), snapshot.diagnostics.toString())
                assertEquals(6, snapshot.definitions.size)
                assertEquals(root.resolve(".m2"), snapshot.definitions.first().sourcePath)
                assertEquals("Maven", snapshot.definitions.first().app)
                assertEquals(CandidateDefinition.Advice.CONSIDER, snapshot.definitions.first().advice)
                assertEquals(CandidateDefinition.Advice.USUALLY_UNNECESSARY, snapshot.definitions.get(4).advice)
                assertNull(snapshot.definitions.get(1).advice)
                assertNull(snapshot.definitions.get(1).reason)
                assertNull(snapshot.definitions.get(5).app)
                for (definition in snapshot.definitions) {
                    assertEquals(source, definition.source)
                    assertEquals(root.resolve(definition.originalPath).normalize(), definition.sourcePath)
                }
            }
        }
        assertEquals(HOME, parse(EMPTY, Path.of("/home/./alex")).root)
        assertThrows<IllegalArgumentException> { parse(EMPTY, Path.of("relative")) }
    }

    @Test fun rejectsEveryUnsafePathAtomicallyWithLocation() {
        val paths = listOf("", "  ", ".", "./", "a/..", "/tmp/cache", "../cache", "a/../cache",
                "a/../../alex-other/cache", "~/cache", "~alex/cache", "\${HOME}/cache", "\$HOME/cache",
                "a/\$NAME/cache", "C:/cache", "C:cache", "c:\\cache", "\\\\server\\share", "//server/share",
                "a\u0000b", "a\nb", "a\tb", "a\u007fb", "a\u0085b", "https://example.com/cache",
                "file:/tmp/cache", "cache/*", "cache/?")
        for (path in paths) {
            val snapshot = parse("""{"directories": [{"path": "safe"}, {"path": ${quoted(path)}}]}""")
            assertFalse(snapshot.accepted(), path)
            assertTrue(snapshot.definitions.isEmpty(), path)
            val diagnostic = snapshot.diagnostics.first()
            assertEquals(SHARED, diagnostic.source)
            assertEquals(2, diagnostic.recordIndex, path)
            assertEquals("path", diagnostic.key, path)
            assertEquals("directories[1]", diagnostic.location, path)
        }
    }

    @Test fun acceptsLiteralSpacesAndNormalizesOnlyLexically() {
        val snapshot = parse("""
                {"directories": [
                  {"path": ".cache//uv"},
                  {"path": ".cache/./uv"},
                  {"path": "cache with spaces"},
                  {"path": ".local/share/uv"},
                  {"path": ".local/share/uv/tools"}
                ]}
                """)
        assertTrue(snapshot.accepted())
        assertEquals(snapshot.definitions.get(0).sourcePath, snapshot.definitions.get(1).sourcePath)
        val merged = CandidateCatalog.merge(listOf(snapshot))
        assertEquals(4, merged.candidates.size)
        assertEquals(2, merged.candidates.first().definitions.size)
        assertEquals(HOME.resolve("cache with spaces"), merged.candidates.get(1).sourcePath)
        assertTrue(merged.candidates.get(3).sourcePath.startsWith(merged.candidates.get(2).sourcePath))
    }

    @Test fun rejectsStrictSchemaViolationsForEitherSourceKind() {
        val inputs = listOf("", "// empty", "[]", "{}", """{"directories": null}""", """{"directories": {}}""",
                """{"directories": [], "unknown": true}""", """{"directories": [{"path": "cache", "advice": ""}]}""",
                """{"directories": []} {"directories": []}""", """{"directories": [null]}""",
                """{"directories": [{"app": "Maven"}]}""", """{"directories": [{"path": null}]}""",
                """{"directories": [{"path": "cache", "app": null}]}""",
                """{"apps": [{"name": " Maven", "directories": []}]}""", """{"apps": [{"name": "Maven ", "directories": []}]}""",
                """{"directories": [{"path": "cache", "advice": "Consider"}]}""",
                """{"directories": [{"path": "cache", "advice": "safe"}]}""",
                """{"directories": [{"path": "cache", "selected": true}]}""",
                """{"directories": [{"path": "cache", "policy": "move"}]}""",
                """{"directories": [{"path": "cache", "reason": " "}]}""", """{"directories": [{"reason": "x"}]}""",
                """{"directories": [{"path": 12}]}""", """{"directories": [{"path": true}]}""",
                """{"directories": [{"path": ["cache"]}]}""", """{"directories": [{"path": "cache", "reason": {"text": "x"}}]}""",
                """{'directories': []}""", """{directories: []}""")
        for (source in listOf(CandidateCatalog.BUNDLED, SHARED)) {
            for (input in inputs) {
                val snapshot = parser.parse(source, HOME, input.toByteArray(StandardCharsets.UTF_8))
                assertFalse(snapshot.accepted(), input)
                assertTrue(snapshot.definitions.isEmpty(), input)
                assertEquals(source, snapshot.diagnostics.first().source)
            }
        }
        assertTrue(parse(EMPTY).accepted())
    }

    /**
     * An each-subdirectory record keeps its parent path and its keep list. Merged with the subdirectories found, it is a
     * candidate for the parent and for each subdirectory, right after it, and the keep list marks the kept ones.
     */
    @Test fun readsAnEachSubdirectoryRecordAndMergesTheSubdirectoriesFound() {
        val snapshot = parse(
            """
            {"apps": [{"name": "Gradle", "directories": [
              {"path": ".gradle", "each-subdirectory": true, "keep": ["init.d", ".develocity"], "reason": "Gradle data"},
              {"path": ".m2", "each-subdirectory": true},
              {"path": ".cache/uv", "each-subdirectory": false}]}]}
            """,
        )
        assertTrue(snapshot.accepted(), snapshot.diagnostics.toString())
        val (gradle, maven, uv) = snapshot.definitions
        assertEquals(HOME.resolve(".gradle"), gradle.sourcePath)
        assertEquals(listOf("init.d", ".develocity"), gradle.eachSubdirectory?.keep)
        assertEquals(listOf<String>(), maven.eachSubdirectory?.keep)
        assertNull(uv.eachSubdirectory)
        val found = listOf("caches", "init.d").map { HOME.resolve(".gradle").resolve(it) }
        val merged = CandidateCatalog.merge(listOf(snapshot), mapOf(HOME.resolve(".gradle") to found)).candidates
        assertEquals(listOf(".gradle", ".gradle/caches", ".gradle/init.d", ".m2", ".cache/uv"), merged.map { HOME.relativize(it.sourcePath).toString() })
        assertEquals(listOf(true, false, false, true, false), merged.map { it.listsSubdirectories })
        assertEquals(listOf(false, false, true, false, false), merged.map { it.onKeepList })
        assertEquals(gradle, merged[1].definitions.single())
        // The built-in list's keep lists answer for any source root, without a disk read.
        assertFalse(CandidateCatalog.builtInKeeps(HOME, HOME.resolve(".gradle/wrapper")))
    }

    /** `keep` needs `each-subdirectory`, and each keep name is one safe directory name. One bad name rejects the list. */
    @Test fun rejectsAKeepListWithoutEachSubdirectoryAndUnsafeKeepNames() {
        val withoutEach = parse("""{"directories": [{"path": ".gradle", "keep": ["init.d"]}]}""")
        assertKind(CandidateDiagnostic.Kind.SCHEMA, withoutEach)
        assertEquals("keep", withoutEach.diagnostics.single().key)
        assertEquals("directories[0]", withoutEach.diagnostics.single().location)
        assertEquals("keep needs \"each-subdirectory\": true", withoutEach.diagnostics.single().message)
        for (name in listOf("init.d/scripts", ".", "..", "", " ", "/init.d", "~", "${'$'}HOME", "*", "a\\b", "init.d/")) {
            val unsafe = parse("""{"directories": [{"path": ".gradle", "each-subdirectory": true, "keep": [${quoted(name)}]}]}""")
            assertKind(CandidateDiagnostic.Kind.UNSAFE_PATH, unsafe)
            assertEquals("keep", unsafe.diagnostics.single().key, name)
        }
        assertKind(
            CandidateDiagnostic.Kind.SYNTAX,
            parse("""{"directories": [{"path": ".gradle", "each-subdirectory": true, "keep": "init.d"}]}"""),
        )
        assertKind(
            CandidateDiagnostic.Kind.SYNTAX,
            parse("""{"directories": [{"path": ".gradle", "each-subdirectory": "yes"}]}"""),
        )
        // The parent must be under the source root, as any path.
        assertKind(
            CandidateDiagnostic.Kind.UNSAFE_PATH,
            parse("""{"directories": [{"path": "/home/alex/.gradle", "each-subdirectory": true}]}"""),
        )
        assertKind(CandidateDiagnostic.Kind.UNSAFE_PATH, parse("""{"directories": [{"path": ".", "each-subdirectory": true}]}"""))
    }

    @Test fun reportsDecodingFailuresWithPositionAndPath() {
        assertEquals(CandidateDiagnostic(SHARED, CandidateDiagnostic.Kind.SYNTAX, 0, 3, 24, "apps[0].directories[0]", "",
                "Encountered an unknown key 'app' at apps[0].directories[0]"), parse("""
                {"apps": [
                  {"name": "App", "directories": [
                    {"path": "cache", "app": "Legacy"}]}]}
                """).diagnostics.single())
        assertEquals(CandidateDiagnostic(SHARED, CandidateDiagnostic.Kind.SYNTAX, 0, 2, 12, "directories[0].path", "",
                "Expected quotation mark '\"', but had '1' instead at directories[0].path"), parse("""
                {"directories": [
                  {"path": 12}]}
                """).diagnostics.single())
        // kotlinx gives missing keys and unknown enum values no offset.
        assertEquals(CandidateDiagnostic(SHARED, CandidateDiagnostic.Kind.SYNTAX, 0, 0, 0, "apps[0].directories[0]", "",
                "Field 'path' is required for type with serial name 'directory', but it was missing at apps[0].directories[0]"),
                parse("""{"apps": [{"name": "App", "directories": [{"reason": "No path"}]}]}""").diagnostics.single())
        assertEquals(CandidateDiagnostic(SHARED, CandidateDiagnostic.Kind.SYNTAX, 0, 0, 0, "directories[0].advice", "",
                "advice does not contain element with name 'safe' at directories[0].advice"),
                parse("""{"directories": [{"path": "cache", "advice": "safe"}]}""").diagnostics.single())
        assertEquals(CandidateDiagnostic(SHARED, CandidateDiagnostic.Kind.SYNTAX, 0, 1, 1, "", "",
                "Expected start of the object '{', but had 'EOF' instead"), parse("").diagnostics.single())
    }

    @Test fun acceptsCommentsAndTrailingCommas() {
        val snapshot = parse("""
                // Team candidates
                {
                  "directories": [
                    /* Maven */ {"path": ".m2", "reason": "Maven // not a comment",},
                    {"path": "datasets"},
                  ],
                }
                """)
        assertTrue(snapshot.accepted(), snapshot.diagnostics.toString())
        assertEquals(listOf(".m2", "datasets"), snapshot.definitions.map { it.originalPath })
        assertEquals("Maven // not a comment", snapshot.definitions.first().reason)
    }

    @Test fun treatsNullOptionalValuesAsAbsentAndRejectsBlankText() {
        val snapshot = parse("""
                {"apps": [{"name": "12", "directories": [
                  {"path": "2026-09-23", "reason": "5", "advice": null},
                  {"path": "true", "reason": null}]}]}
                """)
        assertTrue(snapshot.accepted(), snapshot.diagnostics.toString())
        assertEquals(listOf("2026-09-23", "true"), snapshot.definitions.map(CandidateDefinition::originalPath))
        assertEquals(listOf("12", "12"), snapshot.definitions.map(CandidateDefinition::app))
        assertEquals(listOf("5", null), snapshot.definitions.map(CandidateDefinition::reason))
        assertNull(snapshot.definitions.first().advice)
        assertNull(snapshot.definitions.last().advice)
        assertEquals(CandidateDiagnostic(SHARED, CandidateDiagnostic.Kind.UNSAFE_PATH, 1, 0, 0,
                "directories[0]", "path", "Path must be a literal portable relative path"),
                parse("""{"directories": [{"path": "  "}]}""").diagnostics.single())
        assertEquals(CandidateDiagnostic(SHARED, CandidateDiagnostic.Kind.SCHEMA, 1, 0, 0,
                "directories[0]", "reason", "Reason must not be blank"),
                parse("""{"directories": [{"path": "cache", "reason": "  "}]}""").diagnostics.single())
        assertEquals(CandidateDiagnostic(SHARED, CandidateDiagnostic.Kind.SCHEMA, 0, 0, 0,
                "apps[0]", "name", "App label must not be blank or have leading or trailing whitespace"),
                parse("""{"apps": [{"name": "", "directories": []}]}""").diagnostics.single())
    }

    /** `category` is optional on an app, applies to each of its directories, and is nonblank and trimmed. */
    /** A caution is optional free text, read and checked as a reason is. */
    @Test fun readsACautionLikeAReason() {
        val snapshot = parse("""
                {"apps": [{"name": "Deno", "directories": [
                  {"path": ".cache/deno", "reason": "Deno cache", "caution": "`deno clean` removes the link."},
                  {"path": "other", "caution": null}]}]}
                """)
        assertTrue(snapshot.accepted(), snapshot.diagnostics.toString())
        assertEquals(listOf("`deno clean` removes the link.", null), snapshot.definitions.map(CandidateDefinition::caution))
        assertEquals("Deno cache", snapshot.definitions.first().reason)
        assertEquals(CandidateDiagnostic(SHARED, CandidateDiagnostic.Kind.SCHEMA, 1, 0, 0,
                "directories[0]", "caution", "Caution must not be blank"),
                parse("""{"directories": [{"path": "cache", "caution": "  "}]}""").diagnostics.single())
        val long = parse("""{"directories": [{"path": "cache", "caution": ${quoted("x".repeat(CandidateParser.MAX_STRING_CHARACTERS + 1))}}]}""")
        assertKind(CandidateDiagnostic.Kind.LIMIT, long)
        assertEquals("caution", long.diagnostics.single().key)
        assertFalse(parse("""{"directories": [{"path": "cache", "caution": ["x"]}]}""").accepted())
        assertThrows<IllegalArgumentException> {
            CandidateDefinition(HOME.resolve("cache"), SHARED, 1, "directories[0]", "cache", null, null, null, null, " ")
        }
    }

    @Test fun readsAnAppsOptionalCategory() {
        val snapshot = parse("""
                {"apps": [
                  {"name": "Maven", "category": "JVM", "directories": [{"path": ".m2"}, {"path": ".m2/wrapper"}]},
                  {"name": "Docker", "directories": [{"path": ".docker"}]},
                  {"name": "Bazel", "category": null, "directories": [{"path": ".cache/bazel"}]}],
                 "directories": [{"path": "scratch"}]}
                """)
        assertTrue(snapshot.accepted(), snapshot.diagnostics.toString())
        assertEquals(listOf("JVM", "JVM", null, null, null), snapshot.definitions.map(CandidateDefinition::category))
        for (category in listOf("\"\"", "\" \"", "\" JVM\"", "\"JVM \"")) {
            assertEquals(
                CandidateDiagnostic(SHARED, CandidateDiagnostic.Kind.SCHEMA, 0, 0, 0, "apps[0]", "category",
                        "Category must not be blank or have leading or trailing whitespace"),
                parse("""{"apps": [{"name": "Maven", "category": $category, "directories": [{"path": ".m2"}]}]}""")
                        .diagnostics.single(), category)
        }
        // Only an app has a category.
        assertFalse(parse("""{"directories": [{"path": "cache", "category": "JVM"}]}""").accepted())
        assertKind(CandidateDiagnostic.Kind.LIMIT,
                parse("""{"apps": [{"name": "Maven", "category": "${"x".repeat(4097)}", "directories": []}]}"""))
        assertThrows<IllegalArgumentException> {
            CandidateDefinition(HOME.resolve("cache"), SHARED, 1, "directories[0]", "cache", null, "JVM", null, null)
        }
    }

    @Test fun keepsTheLastValueOfARepeatedKey() {
        val snapshot = parse("""
                {"directories": [{"path": "first"}],
                 "directories": [{"path": "cache", "path": "other", "reason": "a", "reason": "b"}],
                 "apps": [{"name": "App", "name": "Other", "directories": [], "directories": []}]}
                """)
        assertTrue(snapshot.accepted(), snapshot.diagnostics.toString())
        assertEquals(listOf("other"), snapshot.definitions.map(CandidateDefinition::originalPath))
        assertEquals(listOf("b"), snapshot.definitions.map(CandidateDefinition::reason))
    }

    @Test fun roundTripsEveryAdviceValue() {
        val text = """
                {"apps": [{"name": "App", "directories": [
                  {"path": "a", "advice": "consider", "reason": "Kept"},
                  {"path": "b", "advice": "usually-unnecessary"}]}],
                 "directories": [{"path": "c"}]}
                """
        val decoded = decodeJson(CandidateListFile.serializer(), text)
        // The default Json omits defaults, as the configuration output does.
        val encoded = Json.encodeToString(CandidateListFile.serializer(), decoded)
        assertEquals(decoded, decodeJson(CandidateListFile.serializer(), encoded))
        assertEquals(CandidateDefinition.Advice.entries, decoded.apps.orEmpty().flatMap { it.directories }.mapNotNull { it.advice })
        assertFalse(encoded.contains("null"), encoded)
    }

    @Test fun errorMessagesNameNoKotlinTypes() {
        for (input in listOf("{}", """{"apps": [{}]}""", """{"directories": [{}]}""",
                """{"directories": [{"path": "a", "advice": "x"}]}""", """{"directories": [{"path": "a", "x": 1}]}""")) {
            val message = parse(input).diagnostics.single().message
            for (name in listOf("File", "Advice", "Json", "kotlin", "io.github", "\n", "Use '")) {
                assertFalse(message.contains(name), "$input: $message")
            }
        }
    }

    @Test fun enforcesByteRecordAndStringLimitsAtTheirBoundaries() {
        val prefix = "$EMPTY\n//"
        val atByteLimit = prefix + "x".repeat(CandidateParser.MAX_BYTES - prefix.length)
        assertTrue(parse(atByteLimit).accepted())
        assertKind(CandidateDiagnostic.Kind.LIMIT, parse(atByteLimit + "x"))
        val multibyte = prefix + "é".repeat(CandidateParser.MAX_BYTES / 2)
        assertKind(CandidateDiagnostic.Kind.LIMIT, parse(multibyte))

        assertEquals(CandidateParser.MAX_RECORDS, parse(directories(CandidateParser.MAX_RECORDS)).definitions.size)
        assertKind(CandidateDiagnostic.Kind.LIMIT, parse(directories(CandidateParser.MAX_RECORDS + 1)))

        val reason = "😀".repeat(CandidateParser.MAX_STRING_CHARACTERS)
        assertEquals(reason, parse("""{"directories": [{"path": "cache", "reason": ${quoted(reason)}}]}""")
                .definitions.first().reason)
        val long = parse("""{"directories": [{"path": "cache", "reason": ${quoted(reason + "x")}}]}""")
        assertKind(CandidateDiagnostic.Kind.LIMIT, long)
        assertEquals("directories[0]", long.diagnostics.first().location)
        assertEquals("reason", long.diagnostics.first().key)
        assertKind(CandidateDiagnostic.Kind.LIMIT,
                parse("""{"apps": [{"name": ${quoted("n".repeat(4097))}, "directories": []}]}"""))
    }

    @Test fun boundsNestingThroughTheFixedShape() {
        // A record is the deepest object; anything nested deeper is a wrong type, rejected before it is read.
        val deep = parse("""{"directories": """ + "[".repeat(100_000) + "]".repeat(100_000) + "}")
        assertKind(CandidateDiagnostic.Kind.SYNTAX, deep)
        assertEquals("directories[0]", deep.diagnostics.first().location)
        assertKind(CandidateDiagnostic.Kind.SYNTAX,
                parse("""{"directories": [{"path": "cache", "reason": [[["x"]]]}]}"""))
    }

    @Test fun acceptsEmptyAndOptionalSequences() {
        for (input in listOf("""{"apps": []}""", EMPTY, """{"apps": [], "directories": []}""",
                """{"apps": [{"name": "Empty", "directories": []}]}""")) {
            val snapshot = parse(input)
            assertTrue(snapshot.accepted(), snapshot.diagnostics.toString())
            assertTrue(snapshot.definitions.isEmpty())
        }
        val appsOnly = parse("""{"apps": [{"name": "App", "directories": [{"path": "cache"}]}]}""")
        assertTrue(appsOnly.accepted())
        assertEquals("App", appsOnly.definitions.first().app)
        assertNull(appsOnly.definitions.first().advice)
        assertNull(appsOnly.definitions.first().reason)
    }

    @Test fun preservesRepeatedGroupsAndCrossGroupOccurrencesInAppFirstOrder() {
        val snapshot = parse("""
                {"directories": [{"path": ".cache/./uv", "reason": "Ungrouped"}],
                 "apps": [
                  {"name": "uv", "directories": []},
                  {"name": "uv", "directories": [{"path": ".cache/uv", "advice": "consider", "reason": "First"}]},
                  {"name": "uv", "directories": [{"path": ".cache//uv", "advice": "usually-unnecessary", "reason": "Second"}]},
                  {"name": "UV", "directories": [{"path": ".cache/uv"}]}]}
                """)
        assertTrue(snapshot.accepted(), snapshot.diagnostics.toString())
        val candidates = CandidateCatalog.merge(listOf(snapshot)).candidates
        assertEquals(1, candidates.size)
        val definitions = candidates.first().definitions
        assertEquals(snapshot.definitions, definitions)
        assertEquals(listOf(1, 2, 3, 4), definitions.map(CandidateDefinition::recordIndex))
        assertEquals(listOf("apps[1].directories[0]", "apps[2].directories[0]",
                "apps[3].directories[0]", "directories[0]"), definitions.map(CandidateDefinition::location))
        assertEquals(listOf("uv", "uv", "UV", null), definitions.map(CandidateDefinition::app))
        assertEquals(listOf("First", "Second", null, "Ungrouped"), definitions.map(CandidateDefinition::reason))
        assertEquals(listOf(CandidateDefinition.Advice.CONSIDER,
                CandidateDefinition.Advice.USUALLY_UNNECESSARY, null, null), definitions.map(CandidateDefinition::advice))
    }

    @Test fun rejectsInvalidGroupsAndNestedRecordsWithStructuralLocations() {
        val groups = listOf("null", "[]", "{}", """{"name": "App"}""", """{"directories": []}""",
                """{"name": null, "directories": []}""",
                """{"name": "", "directories": []}""", """{"name": " App", "directories": []}""",
                """{"name": "App ", "directories": []}""", """{"name": "App", "directories": null}""",
                """{"name": "App", "directories": {}}""", """{"name": "App", "directories": [], "advice": "consider"}""",
                """{"name": "App", "directories": [], "reason": "Description"}""",
                """{"name": "App", "directories": [], "selected": true}""")
        for (group in groups) {
            val snapshot = parse("""{"apps": [{"name": "Valid", "directories": [{"path": "safe"}]}, $group]}""")
            assertFalse(snapshot.accepted(), group)
            val diagnostic = snapshot.diagnostics.first()
            assertTrue(diagnostic.location.startsWith("apps[1]"), group)
            // Only the decoder knows positions, and not for every error.
            if (diagnostic.kind != CandidateDiagnostic.Kind.SYNTAX) assertEquals(0, diagnostic.line, group)
        }
        assertKind(CandidateDiagnostic.Kind.SCHEMA, parse("""{"apps": null}"""))
        assertKind(CandidateDiagnostic.Kind.SYNTAX, parse("""{"apps": {}}"""))
        // Rejected by the reader: located by path and position.
        for (directory in listOf("""{"path": "cache", "app": "Legacy"}""", """{"path": "cache", "unknown": "value"}""")) {
            val diagnostic = parse("""{"apps": [{"name": "App", "directories": [{"path": "safe"}, $directory]}]}""")
                    .diagnostics.single()
            assertEquals("apps[0].directories[1]", diagnostic.location)
            assertTrue(diagnostic.line > 0 && diagnostic.column > 0)
        }
        // Rejected after decoding: located by record index, path and key.
        val escape = parse("""{"apps": [{"name": "App", "directories": [{"path": "safe"}, {"path": "../escape"}]}]}""")
                .diagnostics.single()
        assertEquals("apps[0].directories[1]", escape.location)
        assertEquals(2, escape.recordIndex)
        assertEquals("path", escape.key)
        // A missing key is rejected by the decoder, by path alone.
        val missing = parse("""{"apps": [{"name": "App", "directories": [{"path": "safe"}, {"reason": "Missing"}]}]}""")
                .diagnostics.single()
        assertEquals(CandidateDiagnostic.Kind.SYNTAX, missing.kind)
        assertEquals("apps[0].directories[1]", missing.location)
    }

    @Test fun boundsGroupsAndAggregateRecords() {
        val app = """{"name": "App", "directories": []}"""
        val emptyGroups = """{"apps": [""" + List(CandidateParser.MAX_APPS) { app }.joinToString(",")
        assertTrue(parse("$emptyGroups]}").accepted())
        assertKind(CandidateDiagnostic.Kind.LIMIT, parse("$emptyGroups, $app]}"))
        val records = List(5_000) { """{"path": "cache"}""" }.joinToString(",")
        val group = """{"name": "App", "directories": [$records]}"""
        val atLimit = """{"apps": [$group, $group]"""
        assertEquals(CandidateParser.MAX_RECORDS, parse("$atLimit}").definitions.size)
        val mixed = parse("""$atLimit, "directories": [{"path": "extra"}]}""")
        assertKind(CandidateDiagnostic.Kind.LIMIT, mixed)
        assertEquals("directories", mixed.diagnostics.first().location)
        val grouped = parse("""{"apps": [$group, $group, {"name": "Extra", "directories": [{"path": "extra"}]}]}""")
        assertKind(CandidateDiagnostic.Kind.LIMIT, grouped)
        assertEquals("apps[2].directories", grouped.diagnostics.first().location)
    }

    @Test fun rejectsMalformedUtf8AndSyntax() {
        assertKind(CandidateDiagnostic.Kind.ENCODING, parser.parse(SHARED, HOME, byteArrayOf(0xc3.toByte(), 0x28)))
        val malformed = parser.parse(SHARED, HOME, Files.readAllBytes(FIXTURES.resolve("malformed.json")))
        assertKind(CandidateDiagnostic.Kind.SYNTAX, malformed)
        assertTrue(malformed.diagnostics.first().line > 0)
    }

    /** Every definition is kept. Within a candidate the user's list comes first, so its app and advice win. */
    @Test fun retainsAllAttributionAndLiteralReasonsWithSharedFirst() {
        val bundled = fixture(CandidateCatalog.BUNDLED, "bundled.json")
        val shared = fixture(SHARED, "shared.json")
        val merged = CandidateCatalog.merge(listOf(bundled, shared))
        assertEquals(11, merged.candidates.size)
        val maven = merged.candidates.first()
        assertEquals(listOf(shared.definitions.first(), bundled.definitions.first()), maven.definitions)
        assertEquals(listOf("Build tools", "Maven"), maven.definitions.map(CandidateDefinition::app))
        assertEquals(listOf(CandidateDefinition.Advice.USUALLY_UNNECESSARY, CandidateDefinition.Advice.CONSIDER),
                maven.definitions.map(CandidateDefinition::advice))
        val uv = merged.candidates.get(1)
        assertEquals(".cache//uv", uv.definitions.first().originalPath)
        assertNull(uv.definitions.get(1).advice)
        assertEquals(2, uv.definitions.first().recordIndex)
        assertEquals("apps[1].directories[0]", uv.definitions.first().location)
        assertEquals(13, merged.candidates.sumOf { c -> c.definitions.size })

        val reason = "  literal \${HOME} <b>reason</b>\n\u001b[31m  "
        val record = """{"path": "cache", "reason": ${quoted(reason)}}"""
        val duplicate = parse("""{"directories": [$record, $record]}""")
        val occurrences = CandidateCatalog.merge(listOf(duplicate)).candidates.first().definitions
        assertEquals(2, occurrences.size)
        assertEquals(reason, occurrences.first().reason)
        assertEquals(reason, occurrences.get(1).reason)
        assertEquals(1, occurrences.first().recordIndex)
        assertEquals(2, occurrences.get(1).recordIndex)
        assertEquals(listOf("directories[0]", "directories[1]"), occurrences.map(CandidateDefinition::location))

        val refreshed = CandidateCatalog.merge(listOf(bundled, fixture(SHARED, "shared-refreshed.json")))
        assertEquals(7, refreshed.candidates.size)
        assertEquals(8, refreshed.candidates.sumOf { c -> c.definitions.size })
        assertEquals(11, merged.candidates.size)
    }

    @Test fun isolatesRejectedSourcesInEitherDirectionAndRejectsMixedRoots() {
        val valid = fixture(CandidateCatalog.BUNDLED, "bundled.json")
        val invalid = fixture(SHARED, "unsafe.json")
        assertKind(CandidateDiagnostic.Kind.UNSAFE_PATH, invalid)
        val merged = CandidateCatalog.merge(listOf(valid, invalid))
        assertEquals(6, merged.candidates.size)
        assertEquals(invalid.diagnostics, merged.diagnostics)
        val badBundled = parser.parse(CandidateCatalog.BUNDLED, HOME, ByteArray(0))
        assertEquals(7, CandidateCatalog.merge(listOf(badBundled, fixture(SHARED, "shared.json"))).candidates.size)
        assertThrows<IllegalArgumentException> {
            CandidateCatalog.merge(listOf(valid, parse(EMPTY, OTHER))) }
    }

    @Test fun snapshotCopiesItsListsAndRejectsDefinitionsWithDiagnostics() {
        val parsed = parse("""{"directories": [{"path": "cache"}]}""")
        val definitions = parsed.definitions.toMutableList()
        val copied = CandidateCatalog.Snapshot.of(SHARED, HOME, definitions, listOf())
        definitions.clear()
        assertEquals(1, copied.definitions.size)
        assertThrows<IllegalArgumentException> { CandidateCatalog.Snapshot.of(SHARED, HOME,
                parsed.definitions, parse("").diagnostics) }
    }

    @Test fun bundledResourcePreservesPathsAndDescriptionsWithExplicitConsiderAdvice() {
        val snapshot = CandidateCatalog.bundled(HOME)
        assertTrue(snapshot.accepted(), snapshot.diagnostics.toString())
        assertEquals(63, snapshot.definitions.size)
        assertEquals(listOf(
                ".cache/JetBrains|Editors|JetBrains|JetBrains caches",
                ".vscode-server|Editors|VS Code|VS Code server",
                ".local/share/zed/languages|Editors|Zed|Language servers downloaded by Zed",
                ".local/share/zed/node|Editors|Zed|Node.js runtime downloaded by Zed",
                ".local/share/zed/extensions|Editors|Zed|Zed extensions",
                ".cache/pip|Python|pip|pip cache",
                ".cache/uv|Python|uv|uv cache",
                ".local/share/uv|Python|uv|uv-managed Python installations, tools and uvx environments",
                ".cache/pypoetry|Python|Poetry|Poetry cache",
                ".cache/pdm|Python|PDM|PDM cache",
                ".cache/virtualenv|Python|virtualenv|virtualenv cache",
                ".local/share/pipx/venvs|Python|pipx|pipx virtual environments",
                ".local/pipx/venvs|Python|pipx|pipx virtual environments in the older location, which pipx still uses when it exists",
                ".cache/rattler|Python|pixi|pixi package cache, shared with other rattler-based tools",
                ".cache/pixi|Python|pixi|pixi package cache, used instead of .cache/rattler when this directory exists",
                ".pixi/envs|Python|pixi|pixi global tool environments",
                ".pyenv/versions|Python|pyenv|Python versions installed by pyenv",
                ".cargo|Rust|Cargo|Cargo registry, Git checkouts, installed binaries and rustup proxies",
                ".rustup|Rust|rustup|Rust toolchains",
                ".conan2/p|C and C++|Conan|Conan package cache",
                ".cache/vcpkg/archives|C and C++|vcpkg|vcpkg binary cache",
                ".xmake/packages|C and C++|xmake|Packages installed by xmake",
                ".hunter|C and C++|Hunter|Hunter package cache",
                ".platformio/packages|C and C++|PlatformIO|PlatformIO toolchains, frameworks and tools",
                ".m2|JVM|Maven|Maven local repository, wrapper distributions and Maven Daemon data",
                ".gradle|JVM|Gradle|Gradle wrapper distributions, daemons, downloaded JDKs and native libraries",
                ".gradle/caches/modules-2|JVM|Gradle|Gradle dependency cache",
                ".gradle/caches/transforms-4|JVM|Gradle|Gradle artifact transforms",
                ".gradle/caches/jars-9|JVM|Gradle|Gradle processed build script classes",
                ".gradle/caches/build-cache-1|JVM|Gradle|Gradle local build cache",
                ".jbang/cache|JVM|JBang|JBang compiled scripts, downloaded content, and cached JDKs",
                ".sdkman/candidates|JVM|SDKMAN|SDKs installed by SDKMAN",
                ".sdkman/tmp|JVM|SDKMAN|SDKMAN downloaded archives",
                ".npm|JavaScript|npm|npm cache",
                ".cache/yarn|JavaScript|Yarn|Yarn cache",
                ".yarn/berry/cache|JavaScript|Yarn|Yarn Berry cache",
                ".cache/pnpm|JavaScript|pnpm|pnpm cache",
                ".local/share/pnpm/store|JavaScript|pnpm|pnpm package store",
                ".pnpm-store|JavaScript|pnpm|legacy pnpm package store",
                ".cache/node-gyp|JavaScript|node-gyp|node-gyp cache",
                ".nvm|JavaScript|nvm|Node.js versions and aliases managed by nvm",
                ".bun/install/cache|JavaScript|Bun|Bun package cache",
                ".volta|JavaScript|Volta|Volta, with the Node.js versions and tools it manages",
                ".local/share/fnm/node-versions|JavaScript|fnm|Node.js versions managed by fnm",
                ".cache/deno|JavaScript|Deno|Deno cache",
                ".cache/node/corepack|JavaScript|Corepack|Package managers downloaded by Corepack",
                ".cache/ms-playwright|JavaScript|Playwright|Browsers downloaded by Playwright",
                ".cache/puppeteer|JavaScript|Puppeteer|Browsers downloaded by Puppeteer",
                ".cache/Cypress|JavaScript|Cypress|Cypress app binaries",
                ".cache/electron|JavaScript|Electron|Electron downloads",
                ".cache/electron-builder|JavaScript|Electron|electron-builder downloads",
                ".cache/go-build|Go|Go|Go build cache",
                "go/pkg|Go|Go|Go module cache and downloaded Go toolchains",
                ".rbenv/versions|Ruby|rbenv|Ruby versions installed by rbenv",
                "Android/Sdk|Android|Android SDK|Android SDK, with the NDK and emulator system images",
                ".android/avd|Android|Android emulator|Android emulator virtual devices",
                ".cache/Google|Android|Android Studio|Android Studio caches, for each installed version",
                ".cache/ccache|Build tools|ccache|ccache compiler cache",
                ".cache/sccache|Build tools|sccache|sccache compiler cache",
                ".cache/bazel|Build tools|Bazel|Bazel build outputs and download caches",
                ".cache/zig|Build tools|Zig|Zig build cache and fetched packages",
                ".local/share/mise/installs|Version managers|mise|Tool versions installed by mise",
                ".asdf/installs|Version managers|asdf|Tool versions installed by asdf"
        ), snapshot.definitions.map { d -> d.originalPath + "|" + d.category + "|" + d.app + "|" + d.reason })
        assertTrue(snapshot.definitions.all { d -> d.advice == CandidateDefinition.Advice.CONSIDER && d.reason != null })
        assertTrue(snapshot.definitions.all { d -> d.app != null })
        // Each caution names the command that undoes the move and what Lighten does next.
        val cautioned = snapshot.definitions.filter { d -> d.caution != null }
        assertEquals(listOf(".sdkman/tmp", ".cache/deno", ".cache/Cypress"), cautioned.map { d -> d.originalPath })
        assertTrue(cautioned.all { d -> d.caution.orEmpty().endsWith("Lighten then asks which directory to keep.") }, cautioned.toString())
        assertEquals(HOME.resolve(".jbang/cache"), snapshot.definitions.single { d -> d.app == "JBang" }.sourcePath)
        // Each tool directory keeps what holds credentials, configuration, or data its tool cannot reach through a link.
        assertEquals(
            listOf(
                ".local/share/uv|credentials", ".cargo|", ".m2|.develocity,.gradle-enterprise",
                ".gradle|caches,init.d,develocity,enterprise", ".nvm|.git,.github,test,.cache",
            ),
            snapshot.definitions.mapNotNull { d -> d.eachSubdirectory?.let { d.originalPath + "|" + it.keep.joinToString(",") } },
        )
        assertTrue(CandidateCatalog.builtInKeeps(HOME, HOME.resolve(".gradle/init.d")))
        assertFalse(CandidateCatalog.builtInKeeps(HOME, HOME.resolve(".gradle/wrapper")))
        for (app in listOf("Gradle", "SDKMAN", "Yarn", "pnpm", "Electron", "uv", "pixi")) {
            val indices = snapshot.definitions.indices.filter { i -> snapshot.definitions.get(i).app == app }
            assertTrue(indices.size > 1, app)
            assertEquals(indices.size, indices.last() - indices.first() + 1, app)
        }
        CandidateCatalog::class.java.getResourceAsStream("/candidates.json").use { input ->
            assertNotNull(input)
            assertArrayEquals(Files.readAllBytes(Path.of("src/main/resources/candidates.json")), input!!.readAllBytes())
        }
    }

    @Test fun loadsBundledContentsFromJarAndReportsMissingOrMalformedPackagedResource() {
        // Isolate application classes in a jar so the normal test classpath cannot supply its resource.
        for (variant in listOf("valid", "missing", "malformed")) {
            val jar = temporary.resolve("$variant.jar")
            val classes = Path.of(CandidateCatalog::class.java.protectionDomain.codeSource.location.toURI())
            JarOutputStream(Files.newOutputStream(jar)).use { output -> Files.walk(classes).use { files ->
                for (file in files.filter(Files::isRegularFile).filter { p -> p.toString().endsWith(".class") }.toList()) {
                    output.putNextEntry(JarEntry(classes.relativize(file).toString().replace('\\', '/')))
                    Files.copy(file, output)
                    output.closeEntry()
                }
                if (variant != "missing") {
                    output.putNextEntry(JarEntry("candidates.json"))
                    output.write(if (variant == "valid") Files.readAllBytes(Path.of("src/main/resources/candidates.json"))
                            else """{"directories": [""".toByteArray(StandardCharsets.UTF_8))
                    output.closeEntry()
                }
            } }
            val dependencies = listOf(kotlin.Unit::class.java, kotlinx.serialization.KSerializer::class.java,
                    kotlinx.serialization.json.Json::class.java).map { type -> type.protectionDomain.codeSource.location }
            URLClassLoader((listOf(jar.toUri().toURL()) + dependencies).toTypedArray(), ClassLoader.getPlatformClassLoader()).use { loader ->
                val catalogClass = loader.loadClass(CandidateCatalog::class.java.name)
                assertEquals("jar", catalogClass.getResource("CandidateCatalog.class").protocol)
                val catalog = catalogClass.getField("INSTANCE").get(null)
                val result = catalogClass.getMethod("bundled", Path::class.java).invoke(catalog, HOME)
                assertEquals(variant == "valid", result.javaClass.getMethod("accepted").invoke(result))
                val definitions = result.javaClass.getMethod("getDefinitions").invoke(result) as List<*>
                assertEquals(if (variant == "valid") 63 else 0, definitions.size)
            }
        }
    }

    private fun fixture(source: CandidateSource, name: String): CandidateCatalog.Snapshot =
        parser.parse(source, HOME, Files.readAllBytes(FIXTURES.resolve(name)))

    /** Raw-string literals are trimmed so that reported lines and columns count from the first character. */
    private fun parse(text: String, root: Path = HOME): CandidateCatalog.Snapshot =
        parser.parse(SHARED, root, text.trimIndent().toByteArray(StandardCharsets.UTF_8))

    companion object {
        private val HOME = Path.of("/home/alex")
        private val OTHER = Path.of("/srv/build/alex")
        private val FIXTURES = Path.of("src/test/resources/suggestion-lists")
        private val SHARED = CandidateSource(CandidateSource.Kind.SHARED, "/net/team/lighten/candidates.json")
        private const val EMPTY = """{"directories": []}"""

        private fun directories(count: Int): String =
            """{"directories": [""" + List(count) { """{"path": "cache"}""" }.joinToString(",") + "]}"

        private fun assertKind(kind: CandidateDiagnostic.Kind, snapshot: CandidateCatalog.Snapshot) {
            assertFalse(snapshot.accepted())
            assertTrue(snapshot.definitions.isEmpty())
            assertEquals(kind, snapshot.diagnostics.first().kind, snapshot.diagnostics.toString())
        }

        /** A JSON string literal. */
        private fun quoted(value: String): String {
            val result = StringBuilder("\"")
            for (character in value) {
                if (character == '\\' || character == '"') result.append('\\').append(character)
                else if (Character.isISOControl(character)) result.append(String.format("\\u%04x", character.code))
                else result.append(character)
            }
            return result.append('"').toString()
        }
    }
}
