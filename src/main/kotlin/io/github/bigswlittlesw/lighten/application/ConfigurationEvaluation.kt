package io.github.bigswlittlesw.lighten.application

import io.github.bigswlittlesw.lighten.config.ConfigurationException
import io.github.bigswlittlesw.lighten.config.ConfigurationLoader
import io.github.bigswlittlesw.lighten.config.LightenConfiguration
import io.github.bigswlittlesw.lighten.config.LightenFile
import io.github.bigswlittlesw.lighten.config.LoadedFile
import io.github.bigswlittlesw.lighten.config.InvalidConfigurationException
import io.github.bigswlittlesw.lighten.fs.PathInspector
import io.github.bigswlittlesw.lighten.fs.PathObservation
import io.github.bigswlittlesw.lighten.fs.PathText
import io.github.bigswlittlesw.lighten.reconcile.ReconciliationAction
import io.github.bigswlittlesw.lighten.reconcile.ReconciliationPlan
import io.github.bigswlittlesw.lighten.reconcile.ReconciliationPlanner
import io.github.bigswlittlesw.lighten.reconcile.RelocationState
import io.github.bigswlittlesw.lighten.reconcile.inspectRelocations
import java.nio.file.Files
import java.nio.file.Path

/**
 * Loads the configuration and inspects the disk once. A one-time choice makes a new plan from the kept observations,
 * without reading the disk again. The one-time choices live only in one [Loaded], so each [load] starts without any.
 * The inspection reads each path in turn, so it is not one atomic snapshot of the filesystem.
 *
 * `inspect` and `plan` are functions so tests can inject a bug into either.
 */
class ConfigurationEvaluation(
    private val loader: ConfigurationLoader = ConfigurationLoader(),
    private val inspect: (Path) -> PathObservation = PathInspector()::inspect,
    private val plan: (List<RelocationState>) -> ReconciliationPlan = ReconciliationPlanner()::plan,
) {
    sealed interface Evaluation {
        val configPath: Path
    }

    data class Missing(override val configPath: Path, val message: PathText) : Evaluation

    /**
     * The default path holds no regular file, for example nothing or a directory. Lighten then has nothing to manage.
     */
    data class Unconfigured(override val configPath: Path) : Evaluation

    /** `line` is the line at fault, or 0 when no one line is (see [InvalidConfigurationException]). */
    data class Invalid(override val configPath: Path, val message: PathText, val line: Int) : Evaluation

    /**
     * `observations` and `savedPlan` use the saved rules. `plan` uses the saved rules with the one-time choices in
     * `draft`.
     * `choiceAvoidsDirectory` holds the normalized sources whose [PlanRelocationItem.choiceAvoidsDirectory] is true.
     * `file` is the file as read, which [ruleFile] edits; null under a command-line override, whose paths are not
     * the file's.
     */
    @ConsistentCopyVisibility
    data class Loaded private constructor(
        override val configPath: Path, val savedConfiguration: LightenConfiguration,
        val observations: List<RelocationState>, val savedPlan: ReconciliationPlan,
        val draft: Map<Path, DecisionChoice>, val availableChoices: Map<Path, List<DecisionChoice>>,
        val plan: ReconciliationPlan, val choiceAvoidsDirectory: Set<Path>, internal val file: LoadedFile?,
    ) : Evaluation {
        companion object {
            /** Copies the collections, including each list of choices. */
            internal fun of(
                configPath: Path, savedConfiguration: LightenConfiguration,
                observations: List<RelocationState>, savedPlan: ReconciliationPlan,
                draft: Map<Path, DecisionChoice>, availableChoices: Map<Path, List<DecisionChoice>>,
                plan: ReconciliationPlan, choiceAvoidsDirectory: Set<Path>, file: LoadedFile?,
            ): Loaded = Loaded(
                configPath, savedConfiguration, observations.toList(), savedPlan,
                draft.toMap(),
                availableChoices.mapValues { it.value.toList() },
                plan, choiceAvoidsDirectory.toSet(), file,
            )
        }

        /**
         * One row per configured relocation under the effective draft plan, most urgent first. Within an urgency
         * group rows keep the configuration file's order, as Configuration's list does; `sortedBy` is stable.
         */
        val items: List<PlanRelocationItem> = observations.mapIndexed { i, state ->
            val relocationPlan = plan.relocations[i]
            val relocation = relocationPlan.relocation
            val source = relocation.sourcePath
            // From the saved rules: the plan's relocation already has the one-time choice applied.
            val decision = relocationDecision(state.source.state, state.target.state, state.relocation, draft[source])
                .takeIf { choicesFor(source).isNotEmpty() }
            PlanRelocationItem(
                relocation, state.source, state.target, relocationPlan,
                state.sourceState(),
                decision, source in choiceAvoidsDirectory,
            )
        }.sortedBy { it.badge().priority }

        /** Every configured source has a list of choices, which can be empty. */
        fun choicesFor(sourcePath: Path): List<DecisionChoice> = availableChoices.getValue(normalize(sourcePath))

        /**
         * The file as read, with the one-time choice for `sourcePath` as that relocation's rule, or null when there
         * is nothing to save: no choice, a choice the saved rule already makes, or no file. Every choice is a rule
         * value ([DecisionChoice.applyTo]), so these are the only cases.
         */
        internal fun ruleFile(sourcePath: Path): LightenFile? {
            val read = file ?: return null
            val source = normalize(sourcePath)
            val choice = draft[source] ?: return null
            // A source with a choice is configured once.
            val i = fileIndex(source) ?: return null
            val saved = savedConfiguration.relocations[i]
            val rule = choice.applyTo(saved)
            if (rule == saved) return null
            return read.file.copy(
                relocations = read.file.relocations.mapIndexed { j, fields ->
                    if (j != i) fields else fields.copy(
                        whenSourceAndTargetDirectoriesExist = rule.whenSourceAndTargetDirectoriesExist,
                        whenOnlyTargetExists = rule.whenOnlyTargetExists,
                        whenAdoptingTarget = rule.whenAdoptingTarget,
                    )
                },
            )
        }

        /** The sources the configuration ignores, each once, in the file's order. Lighten plans nothing for them. */
        val ignored: List<Path> get() = savedConfiguration.ignoredSourcePaths.distinct()

        /**
         * The file as read, with the relocation for `sourcePath` moved to the ignored paths, its source as written; or
         * null when there is no file or no such relocation.
         */
        internal fun ignoredFile(sourcePath: Path): LightenFile? {
            val read = file ?: return null
            val i = fileIndex(normalize(sourcePath)) ?: return null
            return read.file.copy(
                relocations = read.file.relocations.filterIndexed { j, _ -> j != i },
                ignoredSourcePaths = read.file.ignoredSourcePaths + read.file.relocations[i].sourcePath,
            )
        }

        /** The file as read, without `path` among the ignored paths; or null when there is no file or it isn't ignored. */
        internal fun unignoredFile(path: Path): LightenFile? {
            val read = file ?: return null
            val source = normalize(path)
            // The loader resolves the file's ignored paths in order, one each.
            val kept = read.file.ignoredSourcePaths.filterIndexed { j, _ -> savedConfiguration.ignoredSourcePaths[j] != source }
            return if (kept.size == read.file.ignoredSourcePaths.size) null else read.file.copy(ignoredSourcePaths = kept)
        }

        /** The index of the file's relocation for the normalized `source`: the loader converts them in order, one each. */
        private fun fileIndex(source: Path): Int? =
            savedConfiguration.relocations.indexOfFirst { it.sourcePath == source }.takeIf { it >= 0 }
    }

    /**
     * A [ConfigurationException] becomes [Missing] or [Invalid]. Any other exception is a bug in inspection or
     * planning and propagates, so it is never shown as an invalid configuration.
     */
    fun load(configPath: Path): Evaluation {
        if (isUnconfiguredDefault(configPath)) {
            return Unconfigured(configPath)
        }
        return try {
            loadRequired(configPath)
        } catch (exception: ConfigurationException) {
            val message = exception.text
            if (Files.notExists(configPath)) Missing(configPath, message)
            else Invalid(configPath, message, (exception as? InvalidConfigurationException)?.line ?: 0)
        }
    }

    /**
     * Throws the loader's exceptions unchanged, so the CLI can report them. `override` changes only this load: it is
     * never kept as a one-time choice.
     */
    fun loadRequired(configPath: Path, override: ConfigurationLoader.PathOverride? = null): Loaded {
        val read = loader.read(configPath)
        val configuration = loader.resolve(configPath, read, override)
        val observations = inspectRelocations(configuration.relocations, inspect)
        val savedPlan = plan(observations)
        // A one-time choice is kept by source path, so a source configured twice gets no choices. The planner still
        // reports the duplicate. `groupBy` keeps sources in the order first seen.
        val choices = observations
            .groupBy({ state -> state.relocation.sourcePath }) { state ->
                relocationDecision(state.source.state, state.target.state, state.relocation, null)?.offered.orEmpty()
            }
            .mapValues { (_, choices) -> choices.singleOrNull() ?: listOf() }
        return Loaded.of(
            configPath, configuration, observations, savedPlan, mapOf(), choices, savedPlan,
            choiceAvoidsDirectory(observations, savedPlan, choices),
            // An override replaces the file's paths, so there is no file to save a rule to.
            read.takeIf { override == null },
        )
    }

    fun choose(current: Loaded, sourcePath: Path, choice: DecisionChoice): Loaded {
        val source = normalize(sourcePath)
        val available = requireNotNull(current.availableChoices[source]) { "Unknown relocation source: $source" }
        require(choice in available) { "Unavailable choice $choice for $source" }
        return withDraft(current, current.draft + (source to choice))
    }

    private fun withDraft(current: Loaded, draft: Map<Path, DecisionChoice>): Loaded {
        val effective = current.observations.map { state ->
            val choice = draft[state.relocation.sourcePath]
            if (choice == null) state else state.copy(relocation = choice.applyTo(state.relocation))
        }
        val effectivePlan = plan(effective)
        return Loaded.of(
            current.configPath, current.savedConfiguration, current.observations,
            current.savedPlan, draft, current.availableChoices, effectivePlan,
            choiceAvoidsDirectory(current.observations, effectivePlan, current.availableChoices), current.file,
        )
    }

    /**
     * The sources whose relocation [effectivePlan] blocks only because of a directory in the way, and which one of
     * their offered choices plans without a block. A relocation counts as blocked only by a directory when planning it
     * with no directory in the way unblocks it. Each one is planned among the others, so an overlap, which no choice
     * avoids, still blocks it.
     */
    private fun choiceAvoidsDirectory(
        observations: List<RelocationState>, effectivePlan: ReconciliationPlan,
        choices: Map<Path, List<DecisionChoice>>,
    ): Set<Path> {
        val effective = observations.zip(effectivePlan.relocations) { saved, relocationPlan ->
            saved.copy(relocation = relocationPlan.relocation)
        }
        fun blocked(i: Int, state: RelocationState) = plan(effective.mapIndexed { j, other -> if (j == i) state else other })
            .relocations[i].actions.any { it is ReconciliationAction.Blocked }
        return observations.indices.filter { i ->
            val saved = observations[i]
            effectivePlan.relocations[i].actions.any { it is ReconciliationAction.Blocked } &&
                !blocked(i, effective[i].copy(notDirectories = mapOf())) &&
                choices.getValue(saved.relocation.sourcePath).any { choice ->
                    !blocked(i, saved.copy(relocation = choice.applyTo(saved.relocation)))
                }
        }.map { i -> observations[i].relocation.sourcePath }.toSet()
    }
}

/**
 * True when the default configuration path holds no regular file. Evaluation and the JSON output then treat nothing
 * as configured and report empty results. A path the user names must hold a file.
 */
fun isUnconfiguredDefault(configPath: Path): Boolean =
    normalize(configPath) == normalize(ConfigurationLoader.DEFAULT_PATH) && !Files.isRegularFile(configPath)

private fun normalize(path: Path): Path = path.toAbsolutePath().normalize()
