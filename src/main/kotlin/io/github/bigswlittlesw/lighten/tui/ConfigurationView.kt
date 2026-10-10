package io.github.bigswlittlesw.lighten.tui

import dev.tamboui.layout.Rect
import dev.tamboui.style.Style
import dev.tamboui.terminal.Frame
import dev.tamboui.toolkit.Toolkit
import dev.tamboui.toolkit.element.Element
import dev.tamboui.toolkit.element.RenderContext
import dev.tamboui.toolkit.element.Size
import dev.tamboui.toolkit.element.StyledElement
import dev.tamboui.toolkit.elements.ListElement
import dev.tamboui.toolkit.event.KeyEventHandler
import dev.tamboui.toolkit.focus.FocusManager
import dev.tamboui.tui.event.KeyCode
import dev.tamboui.tui.event.KeyEvent
import dev.tamboui.widgets.common.ScrollBarPolicy
import dev.tamboui.widgets.input.TextInputState
import dev.tamboui.widgets.select.Select
import dev.tamboui.widgets.select.SelectState
import io.github.bigswlittlesw.lighten.application.BothExistRule
import io.github.bigswlittlesw.lighten.application.BrowseDraft
import io.github.bigswlittlesw.lighten.application.LightenSession
import io.github.bigswlittlesw.lighten.application.Suggestions
import io.github.bigswlittlesw.lighten.config.CandidateCatalog
import io.github.bigswlittlesw.lighten.config.ConfigurationException
import io.github.bigswlittlesw.lighten.config.ConfigurationLoader
import io.github.bigswlittlesw.lighten.config.ConfigurationPublisher
import io.github.bigswlittlesw.lighten.config.LightenFile
import io.github.bigswlittlesw.lighten.config.Relocation
import io.github.bigswlittlesw.lighten.config.RelocationFile
import io.github.bigswlittlesw.lighten.config.WhenAdoptingTarget
import io.github.bigswlittlesw.lighten.config.WhenOnlyTargetExists
import io.github.bigswlittlesw.lighten.config.WhenSourceAndTargetDirectoriesExist
import io.github.bigswlittlesw.lighten.config.defaultArchiveRoot
import io.github.bigswlittlesw.lighten.config.derivedTarget
import io.github.bigswlittlesw.lighten.config.additionProblem
import io.github.bigswlittlesw.lighten.config.resolvePath
import io.github.bigswlittlesw.lighten.discovery.CandidateDiscovery
import io.github.bigswlittlesw.lighten.fs.PathText
import io.github.bigswlittlesw.lighten.fs.displayPath
import io.github.bigswlittlesw.lighten.tui.DetailViewport.Line
import java.nio.file.Files
import java.nio.file.Path

/**
 * The Configuration screen: one editor that creates the configuration file or changes the one that exists. The draft
 * has the file's own shape, so `~` and `${USER}` stay as written. The loader is the one owner of path rules: the
 * screen resolves its fields with the loader's functions, and saving checks the draft with the loader itself.
 *
 * Focus is TamboUI's: the list and each field have their own id, Tab moves through them in order, and a focused
 * text input takes its own keys first. [key] gets the keys they leave. Everything here runs on the UI thread;
 * discovery workers only publish snapshots.
 */
internal class ConfigurationView private constructor(
    private val session: LightenSession,
    private val focus: FocusManager,
    private val discoveryFactory: () -> CandidateDiscovery,
    /** The app's key handler, which Browse's list passes every key to (see [CandidateBrowser]). */
    keys: KeyEventHandler,
    /** The file as opened. A new file starts from an empty draft, so typing into it counts as a change. */
    private val loaded: LightenFile,
    /** The bytes the file was read from, which a replace compares; null for a new file. */
    private val loadedBytes: ByteArray?,
) : AutoCloseable {
    /** `text` fields are text inputs, the others Selects. A blank text field shows its `placeholder`. */
    private enum class Field(val id: String, val label: String, val text: Boolean, val placeholder: String = "") {
        SOURCE_ROOT("config-source-root", SOURCE_ROOT_LABEL, true),
        TARGET_ROOT("config-target-root", TARGET_ROOT_LABEL, true),
        SUGGESTION_LIST("config-suggestion-list", SUGGESTION_LIST_NAME, true, SUGGESTION_LIST_PLACEHOLDER),
        SOURCE("config-source", SOURCE_LABEL, true),
        TARGET("config-target", TARGET_LABEL, true, TARGET_PLACEHOLDER),
        BOTH_EXIST("config-both-exist", BOTH_EXIST_LABEL, false),
        ONLY_TARGET("config-only-target", ONLY_TARGET_LABEL, false),
        ARCHIVE_ROOT("config-archive-root", ARCHIVE_ROOT_LABEL, true, ARCHIVE_PLACEHOLDER),
    }

    private enum class Question { DISCARD, REPLACE }

    private var draft: LightenFile = loaded
    // Each draft relocation's index in the loaded file, or null when added here. Only adding and removing a row
    // change it, so an edited row still counts as one change.
    private var origins: List<Int?> = loaded.relocations.indices.toList()
    private val list: ListElement<Any> = ListElement<Any>().id(CONFIG_LIST)
        .scrollbar(ScrollBarPolicy.AS_NEEDED).scrollbarThumbColor(palette.focus).scrollbarTrackColor(palette.dim)
        .highlightSymbol("").highlightStyle(Style.EMPTY).autoScroll()
    // The text inputs hold the fields of one list row. [pull] copies their text into the draft before anything reads
    // it.
    private val inputs: Map<Field, TextInputState> = Field.entries.filter { it.text }.associateWith { TextInputState() }
    private var shownRow = -1
    private var focusedLastFrame: String? = null
    private var question: Question? = null
    private var focusBeforeQuestion: String? = null
    private var message = ""
    // Only for its help lines, which therefore never offer a scroll key: in a text field `[` and `]` type.
    private val helpArea = DetailViewport()
    private val detailsArea = DetailViewport()
    private val browser = CandidateBrowser(keys)
    private var browsing = false
    // Sources taken out in this Browse visit; see [BrowseDraft].
    private var kept = setOf<Path>()
    private var suggestions: Suggestions? = null
    // A new file opens Browse once, when the user first leaves the storage locations. Most users fill the file from
    // the built-in suggestions, and a help line alone made them easy to miss.
    private var browseOnFirstLeave = loadedBytes == null
    // That Browse visit shows a note on how to add what it does not list.
    private var firstBrowse = false

    var closed = false
        private set

    /** Whether it closed by saving, so the Workspace checks again and says the next step. */
    var saved = false
        private set

    /** `interactive` is false while a dialog, this view's or the app's, is open over Configuration. */
    fun render(interactive: Boolean): Element {
        val header = Toolkit.row(
            Toolkit.text("⌂ LIGHTEN  ").fg(palette.brand).bold(), Toolkit.text("[$CONFIGURATION_NAME]").fg(palette.focus).bold(),
        )
        if (!browsing) {
            pull()
            openBrowseAfterStorageLocations()
        }
        // Browse keeps its own selection, so the screen is one focusable while it is open.
        if (browsing) {
            val browseHeader = Toolkit.row(
                Toolkit.text("⌂ LIGHTEN  ").fg(palette.brand).bold(),
                Toolkit.text("[" + place(CONFIGURATION_NAME, BROWSE_NAME) + "]").fg(palette.focus).bold(),
            )
            val note = wrappedText(FIRST_BROWSE_NOTE.joinToString("\n"), palette.text).takeIf { firstBrowse }
            return Toolkit.column(*listOfNotNull(browseHeader, note, browser.render(browseDraft(), interactive)).toTypedArray())
                .fill().id(CONFIG_BROWSE).focusable(interactive)
        }
        val row = selectedRow()
        if (row != shownRow) show(row)
        val focused = focus.focusedId()
        val rows = (listOf(STORAGE_LOCATIONS) + draft.relocations.map { name(it) }).mapIndexed { i, name ->
            Toolkit.row(
                Toolkit.text(if (i == row) "❯ " else "  ").fg(palette.focus).length(2),
                Toolkit.text(name).ellipsisMiddle().fill(),
            )
        }
        list.elements(*rows.toTypedArray()).focusable(interactive)
        val listContent = if (draft.relocations.isNotEmpty()) list.fill() else Toolkit.column(
            list.length(rows.size),
            wrappedText("\n" + noRelocationsYet(builtIn.count, builtIn.examples, focused == CONFIG_LIST), palette.dim),
        )
        val listPane = framed(Toolkit.panel(CONFIGURATION_LIST_TITLE, listContent), focused == CONFIG_LIST)
        val content = buildList {
            add(header)
            add(wrappedText(configurationStatus(session.configPath, loadedBytes != null, unsaved()), palette.dim))
            add(Toolkit.row(listPane.percent(35), fieldsPane(row, focused, interactive)).fill())
            if (message.isNotEmpty()) add(wrappedText(message, palette.warn))
            add(helpArea.help(screenHelp(focused), interactive))
        }
        return Toolkit.column(*content.toTypedArray()).fill()
    }

    /**
     * The selected item's fields, one row each with the label in a fixed column beside the value, and under them
     * Details: the focused field's help, then the Resolved section. Details takes the rest of the height and scrolls,
     * so long paths never push a field away, and it always shows a field's whole value, which the field itself
     * scrolls sideways while typing.
     */
    private fun fieldsPane(row: Int, focused: String?, interactive: Boolean): Element {
        val title = if (row == 0) STORAGE_LOCATIONS else name(relocation(row))
        val fields = fields(row)
        val elements = fields.map { field ->
            val isFocused = focused == field.id
            // TamboUI's text input keeps the cursor in view by scrolling to it. Off focus the cursor goes to the
            // start, so a long value shows its beginning; on focus it goes to the end, ready to type.
            inputs[field]?.let { input -> if (isFocused && field.id != focusedLastFrame) input.moveCursorToEnd() else if (!isFocused) input.moveCursorToStart() }
            val label = Toolkit.text((if (isFocused) "❯ " else "  ") + field.label).fg(if (isFocused) palette.focus else palette.text)
                .length(LABEL_WIDTH)
            Toolkit.row(if (isFocused) label.bold() else label, value(field, row, interactive))
        }
        focusedLastFrame = focused
        val help = fields.firstOrNull { it.id == focused }?.let { field ->
            listOfNotNull(
                Line(fieldHelp(field), palette.dim),
                Line(DISCARD_BOTH_WARNING, palette.warn).takeIf {
                    field == Field.BOTH_EXIST &&
                        relocation(row).whenSourceAndTargetDirectoriesExist == WhenSourceAndTargetDirectoriesExist.DISCARD
                },
                // In a text field `s` types, so saving is a step away.
                Line(SAVE_FROM_TEXT_FIELD, palette.dim).takeIf { field.text },
                Line(""),
            )
        }.orEmpty()
        val resolved = resolvedLines(row).map { (label, resolved) ->
            when (resolved) {
                is Resolved.Found -> Line(resolvedLine(label, displayPath(resolved.path)))
                is Resolved.Problem -> Line(resolvedLine(label, resolved.text), palette.warn)
                is Resolved.Empty -> Line(resolvedLine(label, resolved.text), palette.dim)
            }
        }
        return Toolkit.column(
            framed(Toolkit.panel(title, Toolkit.column(*elements.toTypedArray())), fields.any { it.id == focused })
                .length(fields.size + 2),
            detailsArea.render(DETAILS_NAME, help + Line(RESOLVED, palette.text, true) + resolved, focused = false, choiceLine = 0),
        ).fill()
    }

    private fun value(field: Field, row: Int, interactive: Boolean): Element = when (field) {
        Field.BOTH_EXIST -> {
            choice(BothExistRule.entries.map(::bothExistLabel), bothExistRule(relocation(row)).ordinal, field.id, interactive)
        }
        Field.ONLY_TARGET ->
            choice(WhenOnlyTargetExists.entries.map(::onlyTargetLabel), relocation(row).whenOnlyTargetExists.ordinal, field.id, interactive)
        Field.SOURCE_ROOT, Field.TARGET_ROOT, Field.SUGGESTION_LIST, Field.SOURCE, Field.TARGET, Field.ARCHIVE_ROOT ->
            Toolkit.textInput(inputs.getValue(field)).id(field.id).focusable(interactive)
                .placeholder(field.placeholder).placeholderColor(palette.dim).fill()
    }

    private fun fieldHelp(field: Field): String = when (field) {
        Field.SOURCE_ROOT -> SOURCE_ROOT_HELP
        Field.TARGET_ROOT -> TARGET_ROOT_HELP
        Field.SUGGESTION_LIST -> suggestionListHelp(builtIn.count, builtIn.examples)
        Field.SOURCE -> SOURCE_HELP
        Field.TARGET -> TARGET_HELP
        Field.BOTH_EXIST -> BOTH_EXIST_HELP
        Field.ONLY_TARGET -> ONLY_TARGET_HELP
        Field.ARCHIVE_ROOT -> ARCHIVE_ROOT_HELP
    }

    /** The discard or replace question, while one is open. */
    fun dialog(): Element? = when (question) {
        null -> null
        Question.DISCARD ->
            if (loadedBytes == null) confirmDialog(DISCARD_SETUP_TITLE, DISCARD_SETUP_BODY, DISCARD_SETUP_KEYS, ::close, ::answered)
            else confirmDialog(DISCARD_CHANGES_TITLE, discardChangesBody(unsaved()), DISCARD_SETUP_KEYS, ::close, ::answered)
        Question.REPLACE -> confirmDialog(
            replaceConfigurationTitle(session.configPath), REPLACE_CONFIGURATION_BODY, REPLACE_CONFIGURATION_KEYS,
            onYes = {
                answered()
                // A replace is only asked for over a file that was read, so its bytes are there.
                publish { ConfigurationPublisher().replace(session.configPath, draft, checkNotNull(loadedBytes)) }
            },
            onNo = ::answered,
        )
    }

    /**
     * Configuration's place, purpose and keys for the element with id `focused`, for its help lines and the Help
     * screen. In a text field `?` types, so help offers F1 there, and `s` and `q` type too.
     */
    fun screenHelp(focused: String?): ScreenHelp {
        if (browsing) return browser.screenHelp(browseDraft())
        val field = Field.entries.firstOrNull { it.id == focused }
        val nextField = KeyHint("↑/↓", "Field", description = "Move to the next or previous field")
        val tab = KeyHint("Tab", "Next", description = "Move to the next field, then back to the list")
        val toList = KeyHint("Esc", "List", description = "Back to the list")
        val save = KeyHint(
            "s", "Save",
            description = if (loadedBytes == null) "Create the configuration file, then check again"
            else "Replace the configuration file after asking, then check again",
        )
        fun help(navigation: List<KeyHint>, commands: List<KeyHint>) = ScreenHelp(
            field?.let { place(CONFIGURATION_NAME, it.label) } ?: CONFIGURATION_NAME, PURPOSE_CONFIGURATION,
            Step.CONFIGURE, navigation, commands,
        )
        return when {
            field == null -> help(
                listOf(
                    KeyHint("↑/↓", "Select", description = "Select the storage locations or a relocation"),
                    KeyHint("Tab/Enter", "Edit", description = "Edit the selected item's fields"),
                    PAGE_KEYS, HOME_END_KEYS,
                ),
                listOfNotNull(
                    KeyHint("a", "Add", description = "Add a relocation, with the source root filled in"),
                    KeyHint("d", "Remove", description = "Remove the selected relocation; the file changes only when you save")
                        .takeIf { selectedRow() > 0 },
                    KeyHint("b", "Browse", description = "Browse suggestions to add"),
                    save, HELP_KEY, CLOSE_CONFIGURATION_KEY,
                ),
            )
            field.text -> help(
                listOf(
                    nextField, tab, toList,
                    KeyHint("←/→", "Cursor", inHelpArea = false, description = "Move the cursor in the field"),
                    KeyHint("Home/End", "Start/end", inHelpArea = false, description = "Move to the start or end of the field"),
                ),
                listOf(
                    KeyHint("Type", "Edit", description = "Type into the field; letters and ? type too"),
                    KeyHint("Ctrl-U", "Clear", description = "Clear the field"),
                    TEXT_FIELD_HELP_KEY,
                ),
            )
            else -> help(
                listOf(nextField, tab, toList),
                listOf(
                    KeyHint("←/→", "Change", description = "Switch to the previous or next value"),
                    save, HELP_KEY, CLOSE_CONFIGURATION_KEY.copy(keys = "q"),
                ),
            )
        }
    }

    /** The mouse wheel at `x`, `y` scrolls Details, or acts in Browse; the list and fields ignore it. */
    fun wheel(x: Int, y: Int, delta: Int) {
        if (browsing) browser.wheel(x, y, delta, browseDraft())
        else if (detailsArea.contains(x, y)) detailsArea.scroll(delta)
    }

    /** A key the focused element left. Esc goes back one level: from a field to the list, from the list to close. */
    fun key(key: KeyEvent) {
        if (closed) return
        if (browsing) { browseKey(key); return }
        // A text input changed since the last frame takes its own keys, so the draft catches up first.
        pull()
        val field = Field.entries.firstOrNull { it.id == focus.focusedId() }
        when {
            key.isKey(KeyCode.ESCAPE) -> if (field == null) requestClose() else focus.setFocus(CONFIG_LIST)
            key.isQuit() -> requestClose()
            clears(key) -> field?.takeIf { it.text }?.let { inputs.getValue(it).clear() }
            // Letter commands match with or without Ctrl or Alt, so without this Ctrl+D would remove a row.
            key.hasCtrl() || key.hasAlt() -> {}
            field == null -> listKey(key)
            key.isUp() || key.isDown() -> moveField(field, if (key.isUp()) -1 else 1)
            field.text -> {}
            key.isLeft() || key.isRight() -> choose(field, if (key.isLeft()) -1 else 1)
            key.isCharIgnoreCase('s') -> save()
        }
    }

    private fun listKey(key: KeyEvent) {
        when {
            key.isSelect() || key.isRight() -> focus.setFocus(fields(selectedRow()).first().id)
            key.isCharIgnoreCase('a') -> add()
            key.isCharIgnoreCase('d') && selectedRow() > 0 -> remove(selectedRow())
            key.isCharIgnoreCase('b') -> openBrowse()
            key.isCharIgnoreCase('s') -> save()
        }
    }

    private fun moveField(field: Field, delta: Int) {
        val fields = fields(selectedRow())
        focus.setFocus(fields[(fields.indexOf(field) + delta).coerceIn(0, fields.size - 1)].id)
    }

    /** A new relocation, with the source root filled in, and its Source focused. */
    private fun add() {
        edit(draft.relocations + RelocationFile(draft.sourceRoot.trimEnd('/') + "/"), origins + null)
        list.selected(draft.relocations.size)
        focus.setFocus(Field.SOURCE.id)
    }

    private fun remove(row: Int) {
        edit(draft.relocations.filterIndexed { i, _ -> i != row - 1 }, origins.filterIndexed { i, _ -> i != row - 1 })
        list.selected(minOf(row, draft.relocations.size))
    }

    /** Replaces the relocations; the inputs then reload, since a row may have moved. */
    private fun edit(relocations: List<RelocationFile>, origins: List<Int?>) {
        draft = draft.copy(relocations = relocations)
        this.origins = origins
        shownRow = -1
        message = ""
    }

    private fun choose(field: Field, delta: Int) {
        val row = selectedRow()
        val relocation = relocation(row)
        val next = when (field) {
            Field.BOTH_EXIST -> {
                val values = BothExistRule.entries
                val rule = values[(bothExistRule(relocation).ordinal + delta).mod(values.size)]
                // A value that does not keep the target leaves the source's field as the file had it: choosing the
                // loaded value again is then no change.
                val loadedAdopting = origins[row - 1]?.let { loaded.relocations[it].whenAdoptingTarget } ?: WhenAdoptingTarget.PROMPT
                relocation.copy(
                    whenSourceAndTargetDirectoriesExist = rule.both,
                    whenAdoptingTarget = rule.adopting ?: loadedAdopting,
                )
            }
            Field.ONLY_TARGET -> {
                val values = WhenOnlyTargetExists.entries
                relocation.copy(whenOnlyTargetExists = values[(relocation.whenOnlyTargetExists.ordinal + delta).mod(values.size)])
            }
            Field.SOURCE_ROOT, Field.TARGET_ROOT, Field.SUGGESTION_LIST, Field.SOURCE, Field.TARGET, Field.ARCHIVE_ROOT -> relocation
        }
        draft = draft.copy(relocations = draft.relocations.mapIndexed { i, it -> if (i == row - 1) next else it })
        message = ""
    }

    private fun requestClose() {
        if (unsaved() > 0) ask(Question.DISCARD) else close()
    }

    private fun ask(next: Question) {
        question = next
        focusBeforeQuestion = focus.focusedId()
    }

    private fun answered() {
        question = null
        focus.setFocus(focusBeforeQuestion)
    }

    /** Saves a new file at once; replacing an existing one asks first. Either way the draft is checked first. */
    private fun save() {
        pull()
        problem()?.let { (row, text) ->
            list.selected(row)
            message = text
            return
        }
        if (loadedBytes == null) publish { ConfigurationPublisher().saveNew(session.configPath, draft) }
        else ask(Question.REPLACE)
    }

    private fun publish(write: () -> Unit) {
        saveProblem(CHANGED_SINCE_LOADED, write)?.let { problem ->
            message = problem
            return
        }
        saved = true
        close()
    }

    /** The first field that cannot be saved, as the list row it is on and the message that says so. */
    private fun problem(): Pair<Int, String>? = (0..draft.relocations.size).firstNotNullOfOrNull { row ->
        val item = if (row == 0) STORAGE_LOCATIONS else name(relocation(row))
        resolvedLines(row).firstNotNullOfOrNull { (label, resolved) ->
            (resolved as? Resolved.Problem)?.let { problem -> row to notSavedIn(place(item, label), problem.text) }
        }
    }

    override fun close() {
        suggestions?.close()
        suggestions = null
        closed = true
    }

    private fun openBrowse() {
        val root = resolved(draft.sourceRoot, SOURCE_ROOT_LABEL)
        if (root !is Resolved.Found) { message = cannotBrowse((root as? Resolved.Problem)?.text.orEmpty()); return }
        val listed = suggestionList()
        if (listed is Resolved.Problem) { message = cannotBrowse(listed.text); return }
        val current = suggestions ?: Suggestions(discoveryFactory()).also { suggestions = it }
        val path = (listed as? Resolved.Found)?.path
        if (current.request != CandidateDiscovery.Request.of(root.path, path)) current.check(root.path, path)
        browsing = true
        kept = setOf()
        message = ""
        focus.setFocus(CONFIG_BROWSE)
    }

    /**
     * On a new file, opens Browse once, when focus first goes from the storage locations' fields to the list (Esc, or
     * Tab past the last field) with both roots valid and no relocations yet. Moving between those fields does not
     * count, so Source root and a list of your own can be set first; Browse reads both. Only render sees every focus
     * move, as TamboUI moves focus on Tab before any handler.
     */
    private fun openBrowseAfterStorageLocations() {
        if (!browseOnFirstLeave || focus.focusedId() != CONFIG_LIST || draft.relocations.isNotEmpty()) return
        if (fields(0).none { it.id == focusedLastFrame }) return
        val roots = listOf(resolved(draft.sourceRoot, SOURCE_ROOT_LABEL), resolved(draft.targetRoot, TARGET_ROOT_LABEL))
        if (roots.any { it !is Resolved.Found }) return
        // A suggestion list that cannot be read keeps Browse closed and says why, so the next leave tries again.
        openBrowse()
        browseOnFirstLeave = !browsing
        firstBrowse = browsing
    }

    private fun leaveBrowse() {
        browsing = false
        firstBrowse = false
        shownRow = -1
        focus.setFocus(CONFIG_LIST)
    }

    private fun browseKey(key: KeyEvent) {
        if (key.isKey(KeyCode.ESCAPE)) { if (!browser.back()) leaveBrowse(); return }
        if (key.isQuit()) { requestClose(); return }
        // As in the list: Ctrl+U must not show hidden suggestions.
        if (key.hasCtrl() || key.hasAlt()) return
        if (key.isCharIgnoreCase('r')) {
            // Browse opens only with a check under way, so there is a request to repeat.
            val request = checkNotNull(suggestions?.request)
            suggestions?.check(request.root, request.sharedLocation)
            return
        }
        when (val action = browser.key(key, browseDraft())) {
            null -> {}
            is BrowseAction.Add -> browser.added(addRefusal(action)?.message?.shown())
            is BrowseAction.Remove -> removeRows(listOf(action.row))
            is BrowseAction.RemoveAll -> removeRows(action.rows)
            is BrowseAction.AddAll -> {
                // Each add sees the ones before it, so two suggestions in one group that overlap add only the first.
                val refusals = action.additions.map(::addRefusal)
                browser.addedGroup(
                    refusals.count { it == null }, refusals.filterNotNull().map { it.other }, action.unaddable, action.ignored,
                    action.notFound,
                )
            }
            is BrowseAction.TakeOverAll -> {
                val refusals = action.links.map(::addRefusal)
                browser.tookOver(refusals.count { it == null }, refusals.filterNotNull().map { it.other }, action.problems)
            }
            is BrowseAction.Ignore -> ignore(action.source, action.row)
            is BrowseAction.StopIgnoring -> {
                draft = draft.copy(ignoredSourcePaths = draft.ignoredSourcePaths.filter { resolvedPath(it) != action.source })
                // Listed until Browse closes, as a removed row is, so x or Space can follow.
                kept = kept + action.source
            }
            is BrowseAction.Edit -> {
                leaveBrowse()
                list.selected(action.row + 1)
                focus.setFocus(Field.SOURCE.id)
            }
        }
    }

    /** Why Browse could not add a source: the overlap's message, and the relocation it overlaps when that is another. */
    private data class Refusal(val message: PathText, val other: Path?)

    /**
     * Adds the relocation as written in Browse, and returns why not when it would overlap a relocation. A target that
     * the roots would derive anyway is left out, as Configuration leaves out every default.
     */
    private fun addRefusal(addition: BrowseAction.Add): Refusal? {
        val source = addition.source
        val target = addition.target?.takeIf { it != (derived(source) as? Resolved.Found)?.path }
        val row = RelocationFile(displayPath(source), target?.let(::displayPath))
        // Browse decides with the same rule, so a row it offers is not refused here for an unrelated overlap.
        resolvedRelocation(row)?.let { new -> additionProblem(draft.relocations.mapNotNull(::resolvedRelocation), new) }
            ?.let { return Refusal(it.message, it.source.takeIf { other -> other != source }) }
        edit(draft.relocations + row, origins + null)
        return null
    }

    /** A row as the loader would read it, or null while its source or target can't be resolved. */
    private fun resolvedRelocation(row: RelocationFile): Relocation? {
        val resolved = resolve(row)
        val source = (resolved.source as? Resolved.Found)?.path ?: return null
        val target = (resolved.target as? Resolved.Found)?.path ?: return null
        return Relocation(source, target)
    }

    /**
     * Adds `source` to the ignored paths, as the Workspace's `x` does: a relocation at `row` moves there with its source
     * as written, and a suggestion is written as Browse adds one. The ignored entry keeps it listed.
     */
    private fun ignore(source: Path, row: Int?) {
        val written = row?.let { draft.relocations[it].sourcePath } ?: displayPath(source)
        if (row != null) remove(row + 1)
        draft = draft.copy(ignoredSourcePaths = draft.ignoredSourcePaths + written)
    }

    /** Takes the draft rows at `rows` out, keeping their sources listed in Browse until it closes. */
    private fun removeRows(rows: List<Int>) {
        kept = kept + rows.mapNotNull { row -> (resolve(draft.relocations[row]).source as? Resolved.Found)?.path }
        // From the last, so each index still names its row.
        rows.sortedDescending().forEach { row -> remove(row + 1) }
    }

    private fun browseDraft(): BrowseDraft {
        // Browse opens only after a check starts, which sets the request.
        val request = checkNotNull(suggestions?.request)
        val relocations = draft.relocations.map { relocation ->
            val resolved = resolve(relocation)
            BrowseDraft.Paths((resolved.source as? Resolved.Found)?.path, (resolved.target as? Resolved.Found)?.path)
        }
        return BrowseDraft(
            request.root, relocations, suggestions?.result(), kept, draft.ignoredSourcePaths.mapNotNull(::resolvedPath).toSet(),
        )
    }

    /** An ignored path as the loader reads it, or null when it can't; saving then names the problem. */
    private fun resolvedPath(value: String): Path? = (resolved(value, SOURCE_LABEL) as? Resolved.Found)?.path

    /** Takes the shown row's typed text into the draft, field by field, when it differs. */
    private fun pull() {
        if (shownRow < 0 || shownRow > draft.relocations.size) return
        for (field in fields(shownRow).filter { it.text }) {
            val typed = inputs.getValue(field).text()
            if (typed != text(field, shownRow)) {
                draft = withText(field, shownRow, typed)
                message = ""
            }
        }
    }

    private fun show(row: Int) {
        for (field in fields(row).filter { it.text }) {
            inputs.getValue(field).setText(text(field, row))
            inputs.getValue(field).moveCursorToEnd()
        }
        shownRow = row
        detailsArea.reset()
    }

    private fun text(field: Field, row: Int): String = when (field) {
        Field.SOURCE_ROOT -> draft.sourceRoot
        Field.TARGET_ROOT -> draft.targetRoot
        Field.SUGGESTION_LIST -> draft.suggestionList.orEmpty()
        Field.SOURCE -> relocation(row).sourcePath
        Field.TARGET -> relocation(row).targetPath.orEmpty()
        Field.ARCHIVE_ROOT -> relocation(row).archiveRoot.orEmpty()
        Field.BOTH_EXIST, Field.ONLY_TARGET -> ""
    }

    /** A blank optional field is left out of the file, as the loader reads a missing one. */
    private fun withText(field: Field, row: Int, value: String): LightenFile {
        fun edited(change: (RelocationFile) -> RelocationFile) =
            draft.copy(relocations = draft.relocations.mapIndexed { i, it -> if (i == row - 1) change(it) else it })
        return when (field) {
            Field.SOURCE_ROOT -> draft.copy(sourceRoot = value)
            Field.TARGET_ROOT -> draft.copy(targetRoot = value)
            Field.SUGGESTION_LIST -> draft.copy(suggestionList = value.ifEmpty { null })
            Field.SOURCE -> edited { it.copy(sourcePath = value) }
            Field.TARGET -> edited { it.copy(targetPath = value.ifEmpty { null }) }
            Field.ARCHIVE_ROOT -> edited { it.copy(archiveRoot = value.ifEmpty { null }) }
            Field.BOTH_EXIST, Field.ONLY_TARGET -> draft
        }
    }

    private fun selectedRow(): Int = list.selected().coerceIn(0, draft.relocations.size)

    private fun relocation(row: Int): RelocationFile = draft.relocations[row - 1]

    private fun fields(row: Int): List<Field> =
        if (row == 0) listOf(Field.SOURCE_ROOT, Field.TARGET_ROOT, Field.SUGGESTION_LIST)
        else listOf(Field.SOURCE, Field.TARGET, Field.BOTH_EXIST, Field.ONLY_TARGET, Field.ARCHIVE_ROOT)

    /**
     * Draft changes against the file as opened: each storage location that differs, each relocation added, removed
     * or edited, and each path ignored or no longer ignored (in Browse).
     */
    private fun unsaved(): Int {
        val ignored = draft.ignoredSourcePaths.toSet()
        val loadedIgnored = loaded.ignoredSourcePaths.toSet()
        val locations = listOf(
            draft.sourceRoot != loaded.sourceRoot, draft.targetRoot != loaded.targetRoot, draft.suggestionList != loaded.suggestionList,
        ).count { it }
        val kept = origins.withIndex().filter { it.value != null }
        val edited = kept.count { (i, origin) -> draft.relocations[i] != loaded.relocations[checkNotNull(origin)] }
        return locations + origins.count { it == null } + edited + (loaded.relocations.size - kept.size) +
            (ignored - loadedIgnored).size + (loadedIgnored - ignored).size
    }

    /** What the Resolved section shows for `row`: each field's label and its path as the loader reads it. */
    private fun resolvedLines(row: Int): List<Pair<String, Resolved>> {
        if (row == 0) return listOf(
            SOURCE_ROOT_LABEL to resolved(draft.sourceRoot, SOURCE_ROOT_LABEL),
            TARGET_ROOT_LABEL to resolved(draft.targetRoot, TARGET_ROOT_LABEL),
            SUGGESTION_LIST_NAME to suggestionList(),
        )
        val resolved = resolve(relocation(row))
        return listOf(SOURCE_LABEL to resolved.source, TARGET_LABEL to resolved.target, ARCHIVE_ROOT_LABEL to resolved.archive)
    }

    private fun suggestionList(): Resolved {
        val value = draft.suggestionList?.takeUnless { it.isBlank() } ?: return Resolved.Empty(NO_SUGGESTION_LIST)
        return resolved(value, SUGGESTION_LIST_NAME)
    }

    /** A relocation's paths as the loader reads them: a blank Target derives from the roots, a blank Archive root defaults. */
    private fun resolve(relocation: RelocationFile): ResolvedRelocation {
        val source = resolved(relocation.sourcePath, SOURCE_LABEL)
        val path = (source as? Resolved.Found)?.path
        val target = relocation.targetPath?.let { resolved(it, TARGET_LABEL) } ?: derived(path)
        val archive = relocation.archiveRoot?.let { resolved(it, ARCHIVE_ROOT_LABEL) }
            ?: path?.let { Resolved.Found(defaultArchiveRoot(it)) } ?: Resolved.Problem(NEEDS_SOURCE)
        return ResolvedRelocation(source, target, archive)
    }

    private fun derived(source: Path?): Resolved {
        if (source == null) return Resolved.Problem(NEEDS_SOURCE)
        val sourceRoot = (resolved(draft.sourceRoot, SOURCE_ROOT_LABEL) as? Resolved.Found)?.path
        val targetRoot = (resolved(draft.targetRoot, TARGET_ROOT_LABEL) as? Resolved.Found)?.path
        if (sourceRoot == null || targetRoot == null) return Resolved.Problem(NEEDS_ROOTS)
        return derivedTarget(sourceRoot, targetRoot, source)?.let { Resolved.Found(it) } ?: Resolved.Problem(OUTSIDE_SOURCE_ROOT)
    }

    companion object {
        /**
         * Opens the configuration file for editing, or an empty draft when there is none yet.
         *
         * @throws ConfigurationException when the file cannot be read as JSON in the configuration's shape
         */
        fun open(
            session: LightenSession, focus: FocusManager, discoveryFactory: () -> CandidateDiscovery, keys: KeyEventHandler,
        ): ConfigurationView {
            val view = if (Files.isRegularFile(session.configPath)) {
                val file = ConfigurationLoader().read(session.configPath)
                ConfigurationView(session, focus, discoveryFactory, keys, file.file, file.bytes)
            } else ConfigurationView(session, focus, discoveryFactory, keys, LightenFile(targetRoot = ""), null)
            // A new file needs its target root first; an existing one opens on its list.
            focus.setFocus(if (view.loadedBytes == null) Field.TARGET_ROOT.id else CONFIG_LIST)
            return view
        }
    }
}

/**
 * The built-in list as Configuration describes it: how many directories it suggests, counted as Browse counts them,
 * and the first app of each of its first [EXAMPLE_APPS] categories as examples.
 */
private data class BuiltInSuggestions(val count: Int, val examples: List<String>)

private val builtIn: BuiltInSuggestions by lazy {
    // The root only places the paths; the count and the apps are the same under any root, so this needs no home.
    val definitions = CandidateCatalog.bundled(Path.of("/")).definitions
    BuiltInSuggestions(
        definitions.map { it.sourcePath }.distinct().size,
        definitions.filter { it.category != null }.distinctBy { it.category }.mapNotNull { it.app }.take(EXAMPLE_APPS),
    )
}

private const val EXAMPLE_APPS = 4

/** A field's path as the loader reads it, why it cannot, or that it is empty and needs nothing. */
private sealed interface Resolved {
    data class Found(val path: Path) : Resolved
    data class Problem(val text: String) : Resolved
    data class Empty(val text: String) : Resolved
}

private data class ResolvedRelocation(val source: Resolved, val target: Resolved, val archive: Resolved)

private fun resolved(value: String, name: String): Resolved = try {
    Resolved.Found(resolvePath(value, name))
} catch (error: ConfigurationException) {
    Resolved.Problem(error.text.shown())
}

/** A relocation as the list names it: its source as written. */
private fun name(relocation: RelocationFile): String = literal(relocation.sourcePath).ifBlank { NEW_RELOCATION }

private fun bothExistRule(relocation: RelocationFile): BothExistRule =
    BothExistRule.of(relocation.whenSourceAndTargetDirectoriesExist, relocation.whenAdoptingTarget)

/** TamboUI's `Select` for one value among `options`, focusable as `id`: ←/→ change it (see [ConfigurationView.key]). */
private fun choice(options: List<String>, index: Int, id: String, focusable: Boolean): Element {
    // TamboUI has a Select widget but no element for it, so this renders the widget with focus from the context.
    class Choice : StyledElement<Choice>() {
        override fun preferredSize(width: Int, height: Int, context: RenderContext): Size = Size.heightOnly(1)
        override fun renderContent(frame: Frame, area: Rect, context: RenderContext) {
            Select.builder().leftIndicator("‹ ").rightIndicator(" ›").style(context.currentStyle())
                .selectedColor(if (context.isFocused(id)) palette.focus else palette.text).indicatorColor(palette.dim)
                .build().render(area, frame.buffer(), SelectState(options, index))
        }
    }
    return Choice().id(id).focusable(focusable).fill()
}

private fun clears(key: KeyEvent): Boolean = key.isChar('\u0015') || key.hasCtrl() && key.isCharIgnoreCase('u')

/** The label column: the pointer, the longest label (`Suggestion list`) and one space before the value. */
private const val LABEL_WIDTH = 18
