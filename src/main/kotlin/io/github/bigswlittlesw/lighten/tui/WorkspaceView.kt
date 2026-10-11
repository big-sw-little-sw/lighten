package io.github.bigswlittlesw.lighten.tui

import dev.tamboui.style.Color
import dev.tamboui.style.Style
import dev.tamboui.text.CharWidth
import dev.tamboui.toolkit.Toolkit
import dev.tamboui.toolkit.element.Element
import dev.tamboui.toolkit.elements.ListElement
import dev.tamboui.widgets.common.ScrollBarPolicy
import io.github.bigswlittlesw.lighten.application.ApplyModel
import io.github.bigswlittlesw.lighten.application.ConfigurationEvaluation
import io.github.bigswlittlesw.lighten.application.DecisionChoice
import io.github.bigswlittlesw.lighten.application.LightenSession
import io.github.bigswlittlesw.lighten.application.PlanBadge
import io.github.bigswlittlesw.lighten.application.PlanRelocationItem
import io.github.bigswlittlesw.lighten.fs.PathObservation
import io.github.bigswlittlesw.lighten.fs.PathState
import io.github.bigswlittlesw.lighten.fs.RelocationSourceState
import io.github.bigswlittlesw.lighten.fs.SymlinkTargetAvailability
import io.github.bigswlittlesw.lighten.fs.displayPath
import io.github.bigswlittlesw.lighten.reconcile.ReconciliationAction
import io.github.bigswlittlesw.lighten.reconcile.RelocationOutcome
import io.github.bigswlittlesw.lighten.tui.DetailViewport.Anchored
import io.github.bigswlittlesw.lighten.tui.DetailViewport.Line
import java.nio.file.Path

/**
 * A row of the Workspace list: a relocation, then, last, the heading of the ignored sources and, while it is open,
 * each of them. `source` is what the selection follows across a re-check; the heading has none.
 */
internal sealed interface WorkspaceRow {
    val source: Path?

    data class Planned(val item: PlanRelocationItem) : WorkspaceRow {
        override val source: Path get() = item.relocation.sourcePath
    }

    data class IgnoredGroup(val count: Int, val shown: Boolean) : WorkspaceRow {
        override val source: Path? get() = null
    }

    data class Ignored(override val source: Path) : WorkspaceRow
}

/** Renders the workspace screen. The object names the screen; it holds no state. */
internal object WorkspaceView {
    fun visibleItems(model: ConfigurationEvaluation.Loaded, showInSync: Boolean): List<PlanRelocationItem> {
        if (showInSync) return model.items
        val active = model.items.filter { item -> item.badge() != PlanBadge.IN_SYNC }
        return if (active.isEmpty()) model.items else active
    }

    /** The list's rows: the visible relocations, then the ignored group, below in sync, collapsed unless `showIgnored`. */
    fun rows(model: ConfigurationEvaluation.Loaded, showInSync: Boolean, showIgnored: Boolean): List<WorkspaceRow> {
        val ignored = model.ignored
        val group = if (ignored.isEmpty()) listOf()
        else listOf(WorkspaceRow.IgnoredGroup(ignored.size, showIgnored)) +
            (if (showIgnored) ignored.map(WorkspaceRow::Ignored) else listOf())
        return visibleItems(model, showInSync).map(WorkspaceRow::Planned) + group
    }

    /**
     * The in-sync rows `c` hides or shows: none when every row is in sync, because then they always show. With none,
     * `c` is not listed and does nothing.
     */
    fun toggledInSync(model: ConfigurationEvaluation.Loaded): Int {
        val inSync = model.items.count { item -> item.badge() == PlanBadge.IN_SYNC }
        return if (inSync == model.items.size) 0 else inSync
    }

    /** The relocation list. One instance lives across frames: TamboUI keeps its selection and scroll offset. */
    fun list(): ListElement<Any> = ListElement<Any>().id(WORKSPACE_LIST)
        .scrollbar(ScrollBarPolicy.AS_NEEDED).scrollbarThumbColor(palette.focus).scrollbarTrackColor(palette.dim)
        .highlightSymbol("").highlightStyle(Style.EMPTY).autoScroll()

    /**
     * `focused` is the focused element's id; `interactive` is false while a dialog is open over the screen. A `notice`,
     * such as the next step after a save, shows once below the panes.
     */
    fun render(
        session: LightenSession, list: ListElement<Any>, showInSync: Boolean, showIgnored: Boolean, focused: String?,
        interactive: Boolean, choice: Int, viewport: DetailViewport, notice: Line? = null,
    ): Element {
        val retained = session.applyModel() is ApplyModel.Result
        val header = Toolkit.row(
            Toolkit.text("⌂ LIGHTEN  ").fg(palette.brand).bold(),
            Toolkit.text("[1: Workspace]").fg(palette.focus).bold(),
            Toolkit.text(
                if (retained) "  [2: Results]" else if (session.isPlanReady()) "  [2: Review]" else "  [Review unavailable]",
            ).fg(palette.dim),
        )
        val model = session.evaluation()
        if (model !is ConfigurationEvaluation.Loaded) {
            val lines = if (model is ConfigurationEvaluation.Invalid) {
                val (heading, problem, fix, startOver) = unreadable(model)
                listOf(
                    Line(heading, palette.warn, true), Line(problem, palette.warn, false), Line(""), Line(fix), Line(startOver),
                )
            } else listOf(
                Line("Config: " + displayPath(session.configPath)),
                Line((model as? ConfigurationEvaluation.Missing)?.message?.shown() ?: NO_CONFIGURATION, palette.warn, false),
                Line(FIRST_RUN_HINT),
            )
            return Toolkit.column(
                header,
                // The only pane, so it has focus unless a dialog is open.
                viewport.render(
                    "Configuration", lines + listOfNotNull(notice), interactive, 0, WORKSPACE_DETAILS, interactive,
                ),
                viewport.help(screenHelp(session, list, showInSync, showIgnored, focused), interactive),
            )
        }
        val configured = model
        val listed = rows(configured, showInSync, showIgnored)
        val selected = selection(list, listed)
        val row = listed.getOrNull(selected)
        val rows = listed.mapIndexed { i, shown ->
            val pointer = Toolkit.text(if (i == selected) "❯ " else "  ").fg(palette.focus).length(2)
            fun badged(label: String, color: Color, path: Path) = Toolkit.row(
                pointer, Toolkit.text("[$label] ").fg(color).length(label.length + 3),
                Toolkit.text(displayPath(path)).ellipsisMiddle().fill(),
            )
            when (shown) {
                is WorkspaceRow.Planned -> badged(badgeLabel(shown.item.badge()), color(shown.item.badge()), shown.source)
                is WorkspaceRow.IgnoredGroup ->
                    Toolkit.row(pointer, Toolkit.text(ignoredHeading(shown.count, shown.shown)).fg(palette.dim).fill())
                is WorkspaceRow.Ignored -> badged(IGNORED_LABEL, palette.dim, shown.source)
            }
        }
        // The in-sync count and its `c` key go in the list title, not in a row, so the list's selection never lands
        // on them.
        val inSync = toggledInSync(configured)
        list.elements(*rows.toTypedArray()).focusable(interactive).fill()
        // The list is framed by a panel, which can show focus with a thick border; ListElement offers only rounded.
        val listPane = framed(Toolkit.panel(relocationsTitle(inSync, showInSync), list), focused == WORKSPACE_LIST)
        val detailsFocused = focused == WORKSPACE_DETAILS
        val details = when (row) {
            null -> Anchored(listOf(Line(NO_RELOCATIONS), Line(HELP_HINT)), 0)
            is WorkspaceRow.Planned -> details(configured, row.item, choice, detailsFocused, retained)
            is WorkspaceRow.IgnoredGroup -> Anchored(ignoredGroupDetails(row.count, row.shown).map(::Line), 0)
            is WorkspaceRow.Ignored -> Anchored(
                listOf(Line(IGNORED_BY_YOU, palette.text, true)) + ignoredDetails(row.source, retained).map(::Line) +
                    listOf(Line(""), Line(PATHS, palette.text, true), Line(sourceLine(row.source))),
                0,
            )
        }
        val lines = details.lines + configured.plan.diagnostics.map { Line(it.message.shown(), palette.warn, false) }
        val summary = summary(configured.items)
        val content = buildList {
            add(header)
            add(wrappedText("Config: " + displayPath(session.configPath), palette.dim))
            add(summaryElement(summary.counts))
            if (summary.risks.isNotEmpty()) add(wrappedText(summary.risks, palette.warn))
            add(
                Toolkit.row(
                    listPane.percent(45), viewport.render("Details", lines, detailsFocused, details.anchor, WORKSPACE_DETAILS, interactive),
                ).fill(),
            )
            // The notice after a save already says the next step.
            if (notice != null) add(wrappedText(notice.text, notice.color))
            else if (!session.isPlanReady() && !retained) add(
                wrappedText(
                    if (configured.items.any { it.isBlocked() }) FIX_TO_REVIEW else CHOOSE_TO_REVIEW,
                    palette.warn,
                ),
            )
            add(viewport.help(screenHelp(session, list, showInSync, showIgnored, focused), interactive))
        }
        return Toolkit.column(*content.toTypedArray()).fill()
    }

    /**
     * What `x` acts on for the selected row: a relocation to ignore or an ignored source to manage again; null when it
     * has nothing to save (the group heading, no file to save to, or results kept, which a check again clears).
     */
    fun ignoreTarget(session: LightenSession, row: WorkspaceRow?): WorkspaceRow? {
        val model = session.evaluation() as? ConfigurationEvaluation.Loaded ?: return null
        if (session.applyModel() is ApplyModel.Result) return null
        return when (row) {
            is WorkspaceRow.Planned -> row.takeIf { model.ignoredFile(it.source) != null }
            is WorkspaceRow.Ignored -> row.takeIf { model.unignoredFile(it.source) != null }
            is WorkspaceRow.IgnoredGroup, null -> null
        }
    }

    /**
     * Whether `a` opens Review: only for a plan that is ready and changes something. Otherwise `2` alone is listed,
     * to see results or a plan with nothing to apply.
     */
    fun offersApply(session: LightenSession): Boolean {
        val model = session.evaluation()
        return session.isPlanReady() && model is ConfigurationEvaluation.Loaded && model.plan.hasChanges()
    }

    /** The selected row of `list`, as [rows] lists it. */
    fun selectedRow(model: ConfigurationEvaluation.Loaded, list: ListElement<Any>, showInSync: Boolean, showIgnored: Boolean): WorkspaceRow? =
        rows(model, showInSync, showIgnored).let { it.getOrNull(selection(list, it)) }

    /** Workspace's purpose and keys in its current state, for its help lines and the Help screen. */
    fun screenHelp(
        session: LightenSession, list: ListElement<Any>, showInSync: Boolean, showIgnored: Boolean, focused: String?,
    ): ScreenHelp {
        val model = session.evaluation()
        if (model !is ConfigurationEvaluation.Loaded) {
            val missing = model is ConfigurationEvaluation.Missing || model is ConfigurationEvaluation.Unconfigured
            // Help repeats the whole explanation, one paragraph per line.
            val purpose = if (model is ConfigurationEvaluation.Invalid) unreadable(model).joinToString("\n\n")
            else PURPOSE_NO_CONFIGURATION
            return ScreenHelp(
                // Without a configuration that loads, the next step is to configure.
                WORKSPACE_NAME, purpose, Step.CONFIGURE,
                listOf(SCROLL_KEY, SCROLL_ENDS_KEYS, SCROLL_DETAILS_KEYS),
                listOfNotNull(KeyHint("i", "Create configuration", description = "Create a configuration file; nothing is written until you save")
                    .takeIf { missing }, CHECK_AGAIN_KEY, HELP_KEY, QUIT_KEY),
            )
        }
        val retained = session.applyModel() is ApplyModel.Result
        val row = selectedRow(model, list, showInSync, showIgnored)
        val item = (row as? WorkspaceRow.Planned)?.item
        // On the navigation line beside the choice keys, as the commands line has no room for it at 80 columns; Help
        // lists it under Do.
        val always = KeyHint(
            "s", "Always do this",
            description = "Save the choice as this relocation's rule in the configuration file; asks first", acts = true,
        ).takeIf { item != null && model.ruleFile(item.relocation.sourcePath) != null }
        // Beside `s`, for the same reason.
        val ignore = when (ignoreTarget(session, row)) {
            is WorkspaceRow.Planned -> KeyHint("x", "Ignore", description = IGNORE_DESCRIPTION, acts = true)
            is WorkspaceRow.Ignored -> KeyHint("x", "Stop ignoring", description = STOP_IGNORING_DESCRIPTION, acts = true)
            is WorkspaceRow.IgnoredGroup, null -> null
        }
        val navigation = if (focused == WORKSPACE_DETAILS) {
            val choices = !retained && item?.decision != null
            (if (choices) listOfNotNull(
                KeyHint("↑/↓", "Choose", description = "Move between the choices"),
                KeyHint("Space/Enter", "Select", description = "Pick the highlighted choice in place of the others, for the next apply only"),
                // With the choice keys the line has no room for `x` at 80 columns, so it is Help-only there.
                always, ignore?.copy(inHelpArea = false), HOME_END_KEYS,
            )
            else listOfNotNull(SCROLL_KEY, ignore, SCROLL_ENDS_KEYS)) +
                listOf(
                    // Tab is Help-only so the line with `s` fits 80 columns while Details scroll.
                    SCROLL_DETAILS_KEYS, KeyHint("Esc", "Back", description = "Back to the relocation list"),
                    KeyHint("Tab/←", "Back", inHelpArea = false, description = "Back to the relocation list"),
                )
        } else listOfNotNull(
            KeyHint("↑/↓", "Select", description = "Select a relocation"),
            KeyHint("Tab/→", "Details", description = "Move to Details for the selected relocation"),
            KeyHint("Enter", "Details", inHelpArea = false, description = "Move to Details for the selected relocation"),
            always, ignore, PAGE_KEYS, HOME_END_KEYS, SCROLL_DETAILS_KEYS,
        )
        val review = when {
            retained -> listOf(KeyHint("2", "Results", description = "Show what the last apply did"))
            !session.isPlanReady() -> listOf()
            offersApply(session) -> listOf(
                KeyHint("a", "Review & apply", description = "Review the plan; nothing changes until you press y there"),
                KeyHint("2", "Review", description = "Open Review, as a does"),
            )
            else -> listOf(KeyHint("2", "Review", description = "Open Review; there is nothing to apply"))
        }
        val inSync = toggledInSync(model)
        // The list title shows `c`, so the help lines leave it out.
        val toggle = KeyHint(
            "c", (if (showInSync) "Hide " else "Show ") + "$inSync in sync", inHelpArea = false,
            description = (if (showInSync) "Hide" else "Show") + " the relocations already in sync",
        )
        // The group's heading row shows `i`, so the help lines leave it out too.
        val ignored = model.ignored.size
        val toggleIgnored = KeyHint(
            "i", (if (showIgnored) "Hide " else "Show ") + "$ignored ignored", inHelpArea = false,
            description = (if (showIgnored) "Hide" else "Show") + " the sources you ignored",
        )
        val edit = KeyHint(
            "e", "Edit", description = "Open Configuration to change the configuration file; nothing is saved until you press s there",
        )
        return ScreenHelp(
            if (focused == WORKSPACE_DETAILS) place(WORKSPACE_NAME, DETAILS_NAME) else WORKSPACE_NAME,
            if (model.items.isEmpty()) PURPOSE_NO_RELOCATIONS else PURPOSE_WORKSPACE, Step.WORKSPACE,
            navigation,
            review + listOfNotNull(
                toggle.takeIf { inSync > 0 }, toggleIgnored.takeIf { ignored > 0 }, CHECK_AGAIN_KEY, edit, HELP_KEY, QUIT_KEY,
            ),
        )
    }

    private fun unreadable(model: ConfigurationEvaluation.Invalid): List<String> =
        unreadable(model.configPath, model.message.shown(), model.line > 0)

    private fun selection(list: ListElement<Any>, rows: List<WorkspaceRow>): Int =
        list.selected().coerceIn(0, maxOf(0, rows.size - 1))

    /** What the Workspace says after Configuration saves and it has checked again: the next step. */
    fun savedNotice(model: ConfigurationEvaluation.Evaluation): String {
        if (model !is ConfigurationEvaluation.Loaded) return SAVED
        val states = model.items.map(::state)
        return savedNextStep(
            states.count { it == State.CHANGE }, states.count { it == State.CHOOSE }, states.count { it == State.BLOCKED },
        )
    }

    private enum class State { BLOCKED, CHOOSE, CHANGE, UNCHANGED, IN_SYNC }

    /** Each relocation counts once, in the first of these that holds. */
    private fun state(item: PlanRelocationItem): State = when {
        item.isBlocked() -> State.BLOCKED
        item.hasConflict() -> State.CHOOSE
        item.plan.actions.any { it.mutatesFilesystem } -> State.CHANGE
        item.plan.outcome == RelocationOutcome.UNCHANGED -> State.UNCHANGED
        else -> State.IN_SYNC
    }

    fun summary(items: List<PlanRelocationItem>): Summary {
        val states = items.map(::state)
        val actionable = states.count { it == State.CHANGE }
        val conflict = states.count { it == State.CHOOSE }
        val blocked = states.count { it == State.BLOCKED }
        val unchanged = states.count { it == State.UNCHANGED }
        val synced = states.count { it == State.IN_SYNC }
        val warnings = items.count { it.hasWarnings() }
        val deleting = items.count { it.deletesData() }
        return Summary(
            listOf(
                listOf(
                    SummaryCell(relocationCount(items.size), palette.text),
                    SummaryCell(toChange(actionable), palette.change),
                    SummaryCell(needChoice(conflict), palette.warn),
                    SummaryCell(blockedCount(blocked), palette.error),
                ),
                listOf(SummaryCell(inSyncCount(synced), palette.ok), SummaryCell(leftAsIsCount(unchanged), palette.dim)),
            ),
            if (warnings == 0 && deleting == 0) "" else risks(warnings, deleting),
        )
    }

    /** Count rows render as cells joined by ` · `. `risks` is empty when no item has warnings or deletes data. */
    data class Summary(val counts: List<List<SummaryCell>>, val risks: String)

    data class SummaryCell(val text: String, val color: Color)

    private fun summaryElement(counts: List<List<SummaryCell>>): Element {
        val rows = counts.map { row ->
            val cells = row.mapIndexed { i, cell ->
                val label = (if (i == 0) "" else " · ") + cell.text
                Toolkit.text(label).fg(cell.color).length(CharWidth.of(label))
            }
            Toolkit.row(*cells.toTypedArray())
        }
        return Toolkit.column(*rows.toTypedArray())
    }

    private fun details(
        configured: ConfigurationEvaluation.Loaded, item: PlanRelocationItem, choice: Int, focused: Boolean,
        retained: Boolean,
    ): Anchored {
        var anchor = 0
        val source = item.relocation.sourcePath
        // Evaluation inspects the archive destination for every relocation, so it is known even when nothing archives.
        val archive = configured.observations.firstOrNull { it.relocation.sourcePath == source }?.archiveDestination?.path
        val decision = item.decision
        // Archiving names its destination once: under Paths when it is in force, else in its choice.
        val archiving = decision?.inForce == DecisionChoice.ADOPT_AND_ARCHIVE_SOURCE
        val lines = buildList {
            val shown = displayPath(source)
            add(
                Line(
                    if (item.sourceObservation.state == PathState.DIRECTORY && item.targetObservation.state == PathState.DIRECTORY)
                        "Now: both $shown and its target are directories."
                    else "Now: $shown is " + observation(item.sourceObservation) + "; its target is " +
                        observation(item.targetObservation) + ".",
                    palette.text, true,
                ),
            )
            when (item.sourceState) {
                // A blocked broken link's problem line says what is missing.
                RelocationSourceState.BROKEN_SYMLINK -> if (!item.isBlocked()) {
                    add(Line("The source link is broken: what it points to is missing.", palette.warn, false))
                }
                RelocationSourceState.CORRECT_SYMLINK -> add(Line("The source link already points to the target.", palette.ok, false))
                // A link to somewhere else is blocked; its problem line names both paths.
                RelocationSourceState.WRONG_SYMLINK, RelocationSourceState.ABSENT, RelocationSourceState.FILE,
                RelocationSourceState.DIRECTORY, RelocationSourceState.INACCESSIBLE, RelocationSourceState.OTHER -> {}
            }
            decision?.let { add(Line(decisionLine(it))) }
            add(Line("Will do: " + consequence(item), palette.text, true))
            item.plan.actions.filterIsInstance<ReconciliationAction.Blocked>()
                .mapTo(this) { blocked -> Line(problem(blocked.reason.shown()), palette.error, false) }
            if (item.choiceAvoidsDirectory && !retained) add(Line(CHOOSE_AROUND_DIRECTORY))
            if (retained) add(Line(RESULTS_KEPT, palette.warn, false))
            item.plan.diagnostics.mapTo(this) { Line(it.message.shown(), palette.warn, false) }
            if (item.deletesData()) add(Line(DELETES_DATA, palette.warn, true))
            if (!retained && decision != null) decision.offered.forEachIndexed { i, option ->
                add(Line(""))
                if (i == choice) anchor = size
                val chosen = decision.inForce == option
                add(
                    Line(
                        (if (i == choice && focused) "❯ " else "  ") + (if (chosen) "● " else "○ ") + choiceLabel(option),
                        // Bold marks the chosen line, so it stands out without color; `❯` alone marks focus.
                        if (chosen) palette.ok else palette.text, chosen,
                    ),
                )
                // An offered archive names its destination here; a planned one shows it once, under Paths.
                add(Line(choiceDescription(option, archive.takeUnless { archiving })))
            }
            add(Line(""))
            add(Line(PATHS, palette.text, true))
            add(Line(sourceLine(source)))
            add(Line(targetLine(item.relocation.targetPath)))
            item.sourceObservation.linkDestination?.takeIf { path -> path != item.relocation.targetPath }
                ?.let { path -> add(Line("Current link destination: " + displayPath(path))) }
            if (archiving && archive != null) add(Line(archiveLine(archive)))
            leftBehind(item)?.let { path -> add(Line("Left behind: " + displayPath(path))) }
        }
        return Anchored(lines, anchor)
    }

    /** The original source an interrupted replacement left beside the link, when the plan deletes it. */
    private fun leftBehind(item: PlanRelocationItem): Path? =
        item.plan.actions.filterIsInstance<ReconciliationAction.DeleteDirectory>().map { it.path }
            .firstOrNull { path -> path != item.relocation.sourcePath && path != item.relocation.targetPath }

    private fun consequence(item: PlanRelocationItem): String {
        if (item.isBlocked()) return "nothing until you fix the problem below, then check again."
        if (item.hasConflict()) return "nothing until you choose."
        if (item.plan.outcome == RelocationOutcome.UNCHANGED) return "nothing; source and target are left as they are."
        if (item.plan.actions.none { it.mutatesFilesystem }) return "nothing; already in sync."
        return when (item.badge()) {
            PlanBadge.DISCARD -> if (leftBehind(item) != null) LEFT_BEHIND_DELETED
                else "delete the contents of source and target, then create an empty target and link the source to it."
            PlanBadge.BACKUP -> "move the source to the archive, keep the target's contents and link the source to it."
            PlanBadge.ADOPT -> "keep the target's contents, delete the source and replace it with a link."
            PlanBadge.MIGRATE -> "copy the source to the target, check the copy, then replace the source with a link."
            PlanBadge.LINK -> when {
                item.plan.actions.any { it is ReconciliationAction.CreateDirectory } ->
                    "create an empty target directory and link the source to it."
                item.plan.actions.any { it is ReconciliationAction.ReplaceSymlink } ->
                    "fix the source link so it points to the target."
                else -> "keep the target's contents and link the source to it."
            }
            PlanBadge.CONFLICT, PlanBadge.BLOCKED, PlanBadge.INACCESSIBLE, PlanBadge.WARNING, PlanBadge.SKIPPED,
            PlanBadge.IN_SYNC -> "change source and target; see the exact steps in Review."
        }
    }

    private fun observation(observation: PathObservation): String = when (observation.state) {
        PathState.ABSENT -> "missing"
        PathState.DIRECTORY -> if (observation.emptyDirectory) "an empty directory" else "a directory"
        PathState.FILE -> "a file"
        PathState.OTHER -> "a special file"
        PathState.INACCESSIBLE -> "unreadable"
        PathState.SYMLINK -> when (observation.symlinkTargetAvailability) {
            SymlinkTargetAvailability.EXISTS, SymlinkTargetAvailability.NOT_A_SYMLINK -> "a link"
            SymlinkTargetAvailability.ABSENT -> "a broken link"
            SymlinkTargetAvailability.INACCESSIBLE -> "a link to something unreadable"
        }
    }

    private fun color(badge: PlanBadge): Color = when (badge) {
        PlanBadge.IN_SYNC -> palette.ok
        PlanBadge.MIGRATE, PlanBadge.ADOPT, PlanBadge.LINK, PlanBadge.BACKUP, PlanBadge.DISCARD -> palette.change
        PlanBadge.CONFLICT, PlanBadge.WARNING -> palette.warn
        PlanBadge.BLOCKED, PlanBadge.INACCESSIBLE -> palette.error
        PlanBadge.SKIPPED -> palette.dim
    }
}
