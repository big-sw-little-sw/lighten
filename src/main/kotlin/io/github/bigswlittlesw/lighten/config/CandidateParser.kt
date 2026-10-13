package io.github.bigswlittlesw.lighten.config

import io.github.bigswlittlesw.lighten.config.CandidateDiagnostic.Kind
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.StandardCharsets
import java.nio.file.Path

/**
 * Parses a suggestion list from bytes already read, as strict UTF-8 JSON. It accepts the whole list or rejects
 * all of it. The caller reads the file and sets any time limit.
 *
 * Someone else can write the user's list, so the parser limits its size before decoding. After decoding it limits
 * the number of records and app groups and the length of each string. Nesting needs no separate limit: the fixed
 * file shape rejects any value nested deeper than a record as a wrong type, before reading into it.
 */
class CandidateParser {
    fun parse(source: CandidateSource, root: Path, contents: ByteArray): CandidateCatalog.Snapshot {
        @Suppress("NAME_SHADOWING")
        val root = normalizedSourceRoot(root)
        if (contents.size > MAX_BYTES) {
            return failure(source, root, Kind.LIMIT, "Input exceeds 1 MiB UTF-8 limit")
        }
        val text = try {
            StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(contents)).toString()
        } catch (e: CharacterCodingException) {
            return failure(source, root, Kind.ENCODING, "Input is not valid UTF-8")
        }
        return try {
            val file = decodeJson(CandidateListFile.serializer(), text)
            CandidateCatalog.Snapshot.of(source, root, Reader(source, root).definitions(file), listOf())
        } catch (e: JsonInputException) {
            // The message names the dotted path when kotlinx.serialization knows it.
            rejected(root, Invalid(CandidateDiagnostic(source, Kind.SYNTAX, 0, e.line, e.column, e.path, "", e.message)))
        } catch (e: Invalid) {
            rejected(root, e)
        }
    }

    internal fun failure(source: CandidateSource, root: Path, kind: Kind, message: String): CandidateCatalog.Snapshot =
        rejected(root, Invalid(CandidateDiagnostic(source, kind, 0, 0, 0, "", "", message)))

    private fun rejected(root: Path, e: Invalid): CandidateCatalog.Snapshot =
        CandidateCatalog.Snapshot.of(e.diagnostic.source, root, listOf(), listOf(e.diagnostic))

    /** Rejects the whole source with one diagnostic. */
    private class Invalid(val diagnostic: CandidateDiagnostic) : RuntimeException(diagnostic.message)

    /**
     * Applies Lighten's own rules to the decoded file: limits, safe paths and nonblank text. Its diagnostics
     * have no line or column: kotlinx.serialization keeps no positions after decoding. So they name the record by
     * `location` and `key` instead.
     */
    private class Reader(val source: CandidateSource, val root: Path) {
        fun definitions(file: CandidateListFile): List<CandidateDefinition> {
            if (file.apps == null && file.directories == null) {
                throw invalid(Kind.SCHEMA, 0, "", "", "At least one of apps or directories is required")
            }
            val apps = file.apps.orEmpty()
            if (apps.size > MAX_APPS) throw invalid(Kind.LIMIT, 0, "", "apps", "Too many app groups")
            return buildList {
                apps.forEachIndexed { i, app ->
                    val location = "apps[$i]"
                    val name = bounded(app.name, 0, location, "name")
                    if (name.isJavaBlank() || name != name.javaStrip()) {
                        throw invalid(
                            Kind.SCHEMA, 0, location, "name",
                            "App label must not be blank or have leading or trailing whitespace",
                        )
                    }
                    val category = app.category?.let { bounded(it, 0, location, "category") }
                    if (category != null && (category.isJavaBlank() || category != category.javaStrip())) {
                        throw invalid(
                            Kind.SCHEMA, 0, location, "category",
                            "Category must not be blank or have leading or trailing whitespace",
                        )
                    }
                    addAll(records(app.directories, name, category, "$location.directories", size))
                }
                file.directories?.let { directories -> addAll(records(directories, null, null, "directories", size)) }
            }
        }

        /** [preceding] is the number of records already read, so indices and the limit run across the catalog. */
        private fun records(
            directories: List<DirectoryFile>, app: String?, category: String?, location: String, preceding: Int,
        ): List<CandidateDefinition> {
            if (directories.size > MAX_RECORDS - preceding) {
                throw invalid(Kind.LIMIT, 0, location, "directories", "Too many records across catalog")
            }
            return directories.mapIndexed { i, directory ->
                val index = preceding + i + 1
                val record = "$location[$i]"
                val path = bounded(directory.path, index, record, "path")
                val resolved = try {
                    resolveCandidatePath(root, path)
                } catch (e: IllegalArgumentException) {
                    // Both resolve's own failures and the InvalidPathException from Path.of have a message.
                    throw invalid(Kind.UNSAFE_PATH, index, record, "path", e.message!!)
                }
                val reason = note(directory.reason, index, record, "reason", "Reason")
                val caution = note(directory.caution, index, record, "caution", "Caution")
                CandidateDefinition(
                    resolved, source, index, record, path, app, category, directory.advice, reason, caution,
                    eachSubdirectory(directory, index, record),
                )
            }
        }

        private fun eachSubdirectory(directory: DirectoryFile, index: Int, location: String): CandidateDefinition.EachSubdirectory? {
            val keep = directory.keep
            if (!directory.eachSubdirectory) {
                if (keep != null) throw invalid(Kind.SCHEMA, index, location, "keep", "keep needs \"each-subdirectory\": true")
                return null
            }
            if (keep.orEmpty().size > MAX_RECORDS) throw invalid(Kind.LIMIT, index, location, "keep", "Too many keep names")
            keep.orEmpty().forEach { name ->
                bounded(name, index, location, "keep")
                try {
                    validateKeepName(name)
                } catch (e: IllegalArgumentException) {
                    // validateKeepName's failures all have a message.
                    throw invalid(Kind.UNSAFE_PATH, index, location, "keep", e.message!!)
                }
            }
            return CandidateDefinition.EachSubdirectory.of(keep.orEmpty())
        }

        /** A record's optional free text, such as its reason: bounded, and nonblank when given. */
        private fun note(value: String?, index: Int, location: String, key: String, label: String): String? {
            val text = value?.let { bounded(it, index, location, key) }
            if (text != null && text.isJavaBlank()) throw invalid(Kind.SCHEMA, index, location, key, "$label must not be blank")
            return text
        }

        private fun bounded(value: String, index: Int, location: String, key: String): String {
            if (value.codePointCount(0, value.length) > MAX_STRING_CHARACTERS) {
                throw invalid(Kind.LIMIT, index, location, key, "String exceeds 4096 characters")
            }
            return value
        }

        private fun invalid(kind: Kind, index: Int, location: String, key: String, message: String): Invalid =
            Invalid(CandidateDiagnostic(source, kind, index, 0, 0, location, key, message))
    }

    companion object {
        const val MAX_BYTES = 1_048_576
        const val MAX_RECORDS = 10_000
        const val MAX_APPS = 10_000
        const val MAX_STRING_CHARACTERS = 4_096
    }
}

private val EXPANSION = Regex("\\$(?:\\{|[A-Za-z_])")

internal fun normalizedSourceRoot(root: Path): Path {
    require(root.isAbsolute) { "Source root must be absolute" }
    return root.normalize()
}

internal fun validateCandidatePath(path: String) {
    val unsafe = path.isJavaBlank() || path.startsWith("/") || path.startsWith("~") || path.contains("\\")
        || path.matches(Regex("^[A-Za-z][A-Za-z0-9+.-]*:.*"))
        || path.indexOf('*') >= 0 || path.indexOf('?') >= 0 || path.indexOf('[') >= 0 || path.indexOf(']') >= 0
        || path.any { it.isISOControl() }
        || EXPANSION.containsMatchIn(path)
    require(!unsafe) { "Path must be a literal portable relative path" }
    require(path.split("/").none { it == ".." }) { "Parent path components are forbidden" }
    val relative = Path.of(path)
    require(!relative.isAbsolute && relative.normalize().toString().isNotEmpty()) {
        "Path must name a strict descendant of the source root"
    }
}

/** A keep name is one directory name: a safe relative path of a single segment, not `.`. */
internal fun validateKeepName(name: String) {
    validateCandidatePath(name)
    require(!name.contains('/') && name != ".") { "A keep name must be one directory name, without /" }
}

internal fun resolveCandidatePath(root: Path, path: String): Path {
    validateCandidatePath(path)
    val resolved = root.resolve(path).normalize()
    require(resolved != root && resolved.startsWith(root)) { "Path must name a strict descendant of the source root" }
    return resolved
}

// The suggestion list format. Every class has a serial name because kotlinx.serialization puts it in its error
// messages.

@Serializable
@SerialName("candidate-list")
internal data class CandidateListFile(val apps: List<AppFile>? = null, val directories: List<DirectoryFile>? = null)

@Serializable
@SerialName("app")
internal data class AppFile(val name: String, val category: String? = null, val directories: List<DirectoryFile>)

/** With `each-subdirectory`, `path` is the parent whose subdirectories are suggested, except those `keep` names. */
@Serializable
@SerialName("directory")
internal data class DirectoryFile(
    val path: String, val advice: CandidateDefinition.Advice? = null, val reason: String? = null,
    val caution: String? = null, @SerialName("each-subdirectory") val eachSubdirectory: Boolean = false,
    val keep: List<String>? = null,
)
