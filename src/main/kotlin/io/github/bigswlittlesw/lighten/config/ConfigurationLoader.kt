package io.github.bigswlittlesw.lighten.config

import io.github.bigswlittlesw.lighten.fs.PathText
import io.github.bigswlittlesw.lighten.fs.homeDirectoryOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * Loads one JSON configuration rooted at `lighten`.
 *
 * kotlinx.serialization checks the format; [decodeJson] says what it rejects. This class applies Lighten's own rules:
 *
 * - Every path, `suggestion-list` included, follows [resolvePath].
 * - A missing target is the source's path under `source-root` (default `~`), placed under `target-root`.
 * - A missing archive root is [defaultArchiveRoot].
 * - `staging-root` must be under `target-root`.
 * - A relocation's source must not also be ignored.
 *
 * Overlapping relocations load: the planner blocks the ones involved. Environment variables and system properties
 * never override values.
 */
class ConfigurationLoader {
    /**
     * Replaces the first relocation's source and target paths for one command; the file is unchanged.
     * With no configured relocations it supplies the first one.
     */
    data class PathOverride(val sourcePath: Path, val targetPath: Path)

    /** @throws InvalidConfigurationException when the file's text or values are wrong */
    fun load(path: Path, override: PathOverride? = null): LightenConfiguration = resolve(path, read(path), override)

    /** [load] for a file [read] from [path], so a caller can keep the bytes it was read from. */
    internal fun resolve(path: Path, read: LoadedFile, override: PathOverride? = null): LightenConfiguration =
        try {
            configuration(read.file, override)
        } catch (exception: ConfigurationException) {
            // The value checks are shared with Configuration's draft, which has no file yet.
            throw InvalidConfigurationException(path, exception.text)
        }

    /**
     * The file at [path] in its own shape, with the bytes it was read from, so a later replace can tell whether the
     * file changed since (see [ConfigurationPublisher.replace]). Only the JSON is checked here; [configuration]
     * checks the values.
     */
    internal fun read(path: Path): LoadedFile {
        if (!Files.isRegularFile(path)) {
            throw ConfigurationException(PathText("Configuration file does not exist: ", path))
        }
        val bytes = try {
            Files.readAllBytes(path)
        } catch (exception: IOException) {
            throw ConfigurationException(PathText("Unable to read configuration ", path), exception)
        }
        val text = try {
            // Strict, as Files.readString is: malformed UTF-8 is an error, not replacement characters.
            StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
        } catch (exception: CharacterCodingException) {
            throw ConfigurationException(PathText("Unable to read configuration ", path), exception)
        }
        val file = try {
            decodeJson(ConfigurationFile.serializer(), text)
        } catch (exception: JsonInputException) {
            // The message is complete; the cause would only repeat it.
            throw InvalidConfigurationException(path, PathText(problem(exception)), exception.line)
        }
        return LoadedFile(file.lighten, bytes)
    }

    /** The file's values resolved and checked, as [load] does after reading them. */
    internal fun configuration(lighten: LightenFile, override: PathOverride? = null): LightenConfiguration {
        val sourceRoot = resolvePath(lighten.sourceRoot, "lighten.source-root")
        val targetRoot = resolvePath(lighten.targetRoot, "lighten.target-root")
        val stagingRoot = lighten.stagingRoot?.let { resolvePath(it, "lighten.staging-root") }
        if (stagingRoot != null && !stagingRoot.startsWith(targetRoot)) {
            throw ConfigurationException(PathText("staging-root must be under target-root"))
        }
        // The override replaces the first relocation's paths, or supplies it when none is configured.
        val relocations = lighten.relocations.mapIndexed { i, fields ->
            relocation(fields, "lighten.relocations[$i]", sourceRoot, targetRoot, stagingRoot, if (i == 0) override else null)
        }.ifEmpty {
            listOfNotNull(override?.let {
                Relocation(overridden(it.sourcePath, SOURCE_OPTION), overridden(it.targetPath, TARGET_OPTION), stagingRoot = stagingRoot)
            })
        }
        val ignoredSourcePaths = lighten.ignoredSourcePaths.mapIndexed { i, value ->
            resolvePath(value, "lighten.ignored-source-paths[$i]")
        }
        ignoredRelocationProblem(relocations, ignoredSourcePaths)?.let { throw ConfigurationException(it) }
        val sharedList = lighten.suggestionList?.takeUnless { it.isJavaBlank() }?.let { resolvePath(it, "lighten.suggestion-list") }
        return LightenConfiguration.of(sourceRoot, targetRoot, relocations, ignoredSourcePaths, sharedList)
    }

    private fun relocation(
        fields: RelocationFile, key: String, sourceRoot: Path, targetRoot: Path, stagingRoot: Path?,
        override: PathOverride?,
    ): Relocation {
        val sourcePath = override?.let { overridden(it.sourcePath, SOURCE_OPTION) } ?: resolvePath(fields.sourcePath, "$key.source-path")
        val targetPath = override?.let { overridden(it.targetPath, TARGET_OPTION) }
            ?: fields.targetPath?.let { resolvePath(it, "$key.target-path") }
            ?: derivedTarget(sourceRoot, targetRoot, sourcePath)
            ?: throw ConfigurationException(
                PathText("A source outside source-root ", sourceRoot, " requires an explicit target-path: ", sourcePath),
            )
        return Relocation(
            sourcePath, targetPath, fields.whenSourceAndTargetDirectoriesExist, fields.whenOnlyTargetExists,
            fields.whenAdoptingTarget,
            fields.archiveRoot?.let { resolvePath(it, "$key.archive-root") } ?: defaultArchiveRoot(sourcePath),
            stagingRoot,
        )
    }

    /**
     * A relocation whose source is also ignored, in plain words naming both settings, or null. Lighten plans nothing
     * for an ignored path, so it can't also manage it. Only the same path counts: ignoring a directory inside or around
     * a relocation is not refused.
     */
    private fun ignoredRelocationProblem(relocations: List<Relocation>, ignored: List<Path>): PathText? {
        for ((i, relocation) in relocations.withIndex()) {
            val j = ignored.indexOf(relocation.sourcePath).takeIf { it >= 0 } ?: continue
            return PathText(
                "relocations[$i].source-path and ignored-source-paths[$j] are both ", ignored[j], ". A path can't be " +
                    "both a relocation and ignored: remove it from one of the two lists.",
            )
        }
        return null
    }

    companion object {
        /** `~/.lighten.json`. Reading it needs the home directory: see [homeDirectory]. */
        val DEFAULT_PATH: Path get() = homeDirectory().resolve(".lighten.json")
    }
}

/**
 * A path from the file, named `name` in any error. It must be absolute, or start with `~/` (or be `~`), after
 * `${USER}` is filled in: a relative path would depend on the directory Lighten happens to run in. Configuration
 * resolves its fields with this too, naming them as its screen does.
 */
internal fun resolvePath(value: String, name: String): Path {
    if (value.isJavaBlank()) throw ConfigurationException(PathText("$name must not be blank"))
    return convert(name) { expand(value, name).also { require(it.isAbsolute) { FULL_PATH } }.normalize() }
}

/**
 * Why the file was rejected, in plain words when [JsonProblem] has them, else in kotlinx.serialization's words. Text
 * that is not JSON starts with its line and column. A problem in valid JSON starts with only its line, when known,
 * because the column of a value or key is less exact. Keys are named below `lighten`, as the user sees them in the
 * file.
 */
private fun problem(exception: JsonInputException): String {
    val line = if (exception.line > 0) "Line ${exception.line}: " else ""
    val name = exception.path.removePrefix("lighten.")
    return when (val problem = exception.problem) {
        is JsonProblem.Syntax ->
            "It isn't valid JSON: line ${exception.line}, column ${exception.column} ${problem.words}."
        is JsonProblem.WrongKind ->
            line + name.ifEmpty { "The file" } + " should be ${problem.expected}, but it is ${problem.found}."
        is JsonProblem.MissingKey ->
            "${problem.key} is missing. Add it " + (if (name.isEmpty()) "at the top of the file." else "under \"$name\".")
        is JsonProblem.UnknownKey -> line + name.ifEmpty { "The file" } +
            " has an unknown setting \"${problem.key}\". Check its spelling or remove it."
        is JsonProblem.BadValue ->
            line + name + " can't be \"${problem.value}\". Use one of: " + problem.allowed.joinToString(", ") + "."
        null -> line + exception.message
    }
}

/** The one error for a relative path, wherever a path is set. */
internal const val FULL_PATH = "Use a full path, or one starting with ~/"

/** A missing target: the source's path under the source root, placed under the target root; null outside it. */
internal fun derivedTarget(sourceRoot: Path, targetRoot: Path, sourcePath: Path): Path? =
    if (!sourcePath.startsWith(sourceRoot)) null else targetRoot.resolve(sourceRoot.relativize(sourcePath)).normalize()

/**
 * Reports a value that a conversion rejects (`require`, or `Path.of` with a NUL) as a [ConfigurationException]
 * naming its key, so the CLI prints one line instead of a stack trace.
 */
private fun <T> convert(key: String, conversion: () -> T): T = try {
    conversion()
} catch (exception: IllegalArgumentException) {
    // An InvalidPathException's message repeats the input, which may hold the control character itself.
    throw ConfigurationException(PathText("$key: ${(exception as? InvalidPathException)?.reason ?: exception.message}"))
}

private fun expand(value: String, name: String): Path {
    val substituted = withUser(value, name)
    val expanded = when {
        substituted == "~" -> homeDirectory().toString()
        substituted.startsWith("~/") -> homeDirectory().toString() + substituted.substring(1)
        else -> substituted
    }
    return Path.of(expanded)
}

/**
 * [value] with each `${USER}` replaced by [user], the user name. It is never replaced with empty text: without a name,
 * a path that uses it is refused, naming the setting [name].
 */
internal fun withUser(value: String, name: String, user: String? = userName()): String {
    if (USER_VARIABLE !in value) return value
    if (user == null) {
        throw ConfigurationException(
            PathText("$name uses $USER_VARIABLE, but Lighten can't find your user name: the USER environment variable " +
                "is not set and the system gives none. Write the name instead."),
        )
    }
    return value.replace(USER_VARIABLE, user)
}

/**
 * The `USER` environment variable, or when it is unset or empty, the OS account name. The JVM reads `user.name`
 * from the OS user database, so it works in a native image and where a service or container leaves `USER` unset.
 */
internal fun userName(
    environment: String? = System.getenv("USER"), account: String? = System.getProperty("user.name"),
): String? = environment?.takeUnless { it.isEmpty() } ?: account?.takeUnless { it.isEmpty() }

private const val USER_VARIABLE = "\${USER}"

/** The home directory ([homeDirectoryOrNull]), or a [ConfigurationException] that tells the user to set `HOME`. */
internal fun homeDirectory(): Path = homeDirectoryOrNull() ?: throw ConfigurationException(PathText(NO_HOME))

internal const val NO_HOME =
    "Lighten can't find your home directory. Set the HOME environment variable to its full path, then try again."

/** A command-line override: unlike the file, it may be relative to where the command runs. */
private fun overridden(path: Path, name: String): Path = expand(path.toString(), name).toAbsolutePath().normalize()

private const val SOURCE_OPTION = "--source-path"
private const val TARGET_OPTION = "--target-path"

/** A configuration file as read: its contents, and its bytes for [ConfigurationPublisher.replace]. */
internal class LoadedFile(val file: LightenFile, val bytes: ByteArray)

// The configuration file format, shared by ConfigurationLoader and ConfigurationPublisher. Paths stay as
// written (`~/x`, `${USER}`) and expand only in the loader. Optional values default to null, empty, `~` for
// source-root or `prompt` for a rule, and are omitted on output, so an explicit default is dropped on the next
// write without changing its meaning. Every class has a serial name because kotlinx puts it in its
// error messages.

internal const val DEFAULT_SOURCE_ROOT = "~"

@Serializable
@SerialName("configuration")
internal data class ConfigurationFile(val lighten: LightenFile)

@Serializable
@SerialName("lighten")
internal data class LightenFile(
    /** Targets derive from a source's path under this root. */
    @SerialName("source-root") val sourceRoot: String = DEFAULT_SOURCE_ROOT,
    @SerialName("target-root") val targetRoot: String,
    @SerialName("staging-root") val stagingRoot: String? = null,
    /** A blank one means none. */
    @SerialName("suggestion-list") val suggestionList: String? = null,
    val relocations: List<RelocationFile> = listOf(),
    @SerialName("ignored-source-paths") val ignoredSourcePaths: List<String> = listOf(),
)

@Serializable
@SerialName("relocation")
internal data class RelocationFile(
    @SerialName("source-path") val sourcePath: String,
    @SerialName("target-path") val targetPath: String? = null,
    @SerialName("when-source-and-target-directories-exist")
    val whenSourceAndTargetDirectoriesExist: WhenSourceAndTargetDirectoriesExist = WhenSourceAndTargetDirectoriesExist.PROMPT,
    @SerialName("when-only-target-exists") val whenOnlyTargetExists: WhenOnlyTargetExists = WhenOnlyTargetExists.PROMPT,
    @SerialName("when-adopting-target") val whenAdoptingTarget: WhenAdoptingTarget = WhenAdoptingTarget.PROMPT,
    /** Absent means [defaultArchiveRoot]. */
    @SerialName("archive-root") val archiveRoot: String? = null,
)
