package io.github.bigswlittlesw.lighten.tui

import dev.tamboui.style.Color
import dev.tamboui.style.Style
import dev.tamboui.text.Span
import dev.tamboui.text.Text
import dev.tamboui.text.CharWidth
import dev.tamboui.toolkit.Toolkit
import dev.tamboui.toolkit.element.Element
import dev.tamboui.toolkit.element.StyledElement
import dev.tamboui.toolkit.elements.ListElement
import dev.tamboui.toolkit.event.KeyEventHandler
import dev.tamboui.tui.event.KeyCode
import dev.tamboui.tui.event.KeyEvent
import dev.tamboui.widgets.common.ScrollBarPolicy
import io.github.bigswlittlesw.lighten.application.BrowseDraft
import io.github.bigswlittlesw.lighten.config.CandidateDefinition
import io.github.bigswlittlesw.lighten.config.CandidateSource
import io.github.bigswlittlesw.lighten.discovery.CandidateDiscovery
import io.github.bigswlittlesw.lighten.discovery.CandidateObservation
import io.github.bigswlittlesw.lighten.fs.displayPath
import io.github.bigswlittlesw.lighten.tui.DetailViewport.Line
import java.nio.file.Path

/** What a Browse key asks of the Configuration draft. */
internal sealed interface BrowseAction {
    /** Add `source`. `target` is where a link taken over points; null derives the target from the roots. */
    data class Add(val source: Path, val target: Path? = null) : BrowseAction

    /** Remove the relocation at `row` from the draft. */
    data class Remove(val row: Int) : BrowseAction

    /**
     * Add each of `additions` that does not overlap; `unaddable` counts the group's rows that could not be tried,
     * `ignored` those skipped because they are ignored, and `notFound` those left out because only found directories
     * are shown.
     */
    data class AddAll(val additions: List<Add>, val unaddable: Int, val ignored: Int, val notFound: Int) : BrowseAction

    /** `L`: take over each of `links` that does not overlap; `problems` counts the shown links that can't be taken over. */
    data class TakeOverAll(val links: List<Add>, val problems: Int) : BrowseAction

    /** Ignore `source`, taking the relocation at `row` out of the draft when it is in it. */
    data class Ignore(val source: Path, val row: Int?) : BrowseAction

    /** Take `source` out of the draft's ignored paths. */
    data class StopIgnoring(val source: Path) : BrowseAction

    /** Remove the relocations at `rows` from the draft. */
    data class RemoveAll(val rows: List<Int>) : BrowseAction

    /** Edit the relocation at `row` in the draft. */
    data class Edit(val row: Int) : BrowseAction
}

/**
 * Browse: the suggestions in TamboUI's list under a heading per category and per app, with each directory's details
 * and the suggestion lists' state one key away. Which row is selected does not depend on which directories are in the
 * draft.
 *
 * The selection follows an item, not a position: Browse keeps the selected item and sets the list's index from it on
 * every frame, so checking again, `u`, `f` and a row added or removed never move it to another item. When the item is no
 * longer listed, the row at its place becomes the selected item. Rows keep the order they were first listed in, so
 * a row taken out and kept listed (see [BrowseDraft]) stays where it was.
 *
 * The focused Browse screen offers each key to the list inside it before the app sees it. The list passes every key
 * to `keys`, the app's handler, so its own moves never run and Browse keeps the selection. The app takes every mouse
 * event before any element, so the list's wheel and clicks never run either.
 */
internal class CandidateBrowser(keys: KeyEventHandler) {
    /** A row: a heading, which Space acts on as a whole, or a directory. */
    private sealed interface Item {
        /** A category's heading over its apps; `null` is Other tools, the apps no list gives a category. */
        data class Category(val name: String?) : Item

        /**
         * An app's heading under its category; `null` is Other directories, a top-level heading over the directories
         * no list gives an app.
         */
        data class App(val name: String?) : Item

        data class Directory(val path: Path) : Item
    }

    /** A listed row and the entries it stands for: a heading's directories, or a directory's own entry. */
    private data class Row(val item: Item, val entries: List<BrowseDraft.Entry>)

    private enum class View { LIST, DETAILS, LISTS }

    /**
     * How many of a group's directories are in the configuration, counting only those that are in it or can be.
     * `MANAGED`: none can be added because every one moves with a linked parent in the configuration.
     */
    private enum class GroupState { ALL, SOME, NONE, MANAGED, EMPTY }

    private val viewport = DetailViewport()
    // Rows draw their own pointer and bold, so the list's highlight is off; it keeps only the scroll offset.
    private val list = ListElement<Any>().highlightSymbol("").highlightStyle(Style.EMPTY).autoScroll()
        .scrollbar(ScrollBarPolicy.AS_NEEDED).scrollbarThumbColor(palette.focus).scrollbarTrackColor(palette.dim)
        .onKeyEvent(keys)
    private var focus: Item? = null
    // The selected row's index in the last frame, where the selection goes when its item is no longer listed.
    private var shown = 0
    // Every source path in the order Browse first listed it.
    private val order = linkedSetOf<Path>()
    private var view = View.LIST
    private var reveal = false
    // Only the directories found on this machine are shown (`f`).
    private var foundOnly = false
    private var message = ""

    /** `interactive` is false while a dialog is open over Browse. */
    fun render(draft: BrowseDraft, interactive: Boolean = true): Element {
        val help = viewport.help(screenHelp(draft), interactive)
        val messageLine = message.takeIf { it.isNotEmpty() }?.let { wrappedText(it, palette.warn) }
        return when (view) {
            View.DETAILS -> Toolkit.column(
                viewport.render(
                    DETAILS_NAME, detailLines(focusedEntry(draft), (focus as? Item.Directory)?.path, draft),
                    focused = true, choiceLine = 0,
                ),
                *listOfNotNull(messageLine, help).toTypedArray(),
            ).fill()
            View.LISTS -> Toolkit.column(
                viewport.render(SUGGESTION_LISTS, listDetails(draft), focused = true, choiceLine = 0), help,
            ).fill()
            View.LIST -> Toolkit.column(*listOfNotNull(
                *listLines(draft).map { wrappedText(it.text, it.color) }.toTypedArray(),
                countLine(draft)?.let { wrappedText(it, palette.text) },
                linksToTakeOver(draft).ready.size.takeIf { it > 0 }?.let { wrappedText(linksYouMade(it), palette.text) },
                framed(Toolkit.panel(BROWSE_NAME, suggestions(draft)), focused = true),
                messageLine, help,
            ).toTypedArray()).fill()
        }
    }

    /** The list of suggestions, its selection set from [focus]; or a line saying there are none yet. */
    private fun suggestions(draft: BrowseDraft): Element {
        val rows = rows(draft)
        if (rows.isEmpty()) return wrappedText(if (foundOnly) NONE_FOUND else NO_SUGGESTIONS, palette.dim)
        shown = selectedIndex(rows)
        focus = rows[shown].item
        // Rebuilt every frame, so rows follow the draft and discovery.
        val elements = rows.mapIndexed { i, (item, entries) ->
            val selected = i == shown
            when (item) {
                is Item.Category -> headingRow(item.name ?: OTHER_TOOLS, 0, entries, draft, selected)
                is Item.App -> headingRow(item.name ?: OTHER_DIRECTORIES, if (item.name == null) 0 else 2, entries, draft, selected)
                is Item.Directory -> directoryRow(entries.single(), draft, selected)
            }
        }
        return list.elements(*elements.toTypedArray()).selected(shown).fill()
    }

    /** Browse's purpose and keys in its current state, for its help lines and the Help screen. */
    fun screenHelp(draft: BrowseDraft): ScreenHelp {
        val checkAgain = CHECK_AGAIN_KEY.copy(description = CHECK_LISTS_AGAIN)
        val close = CLOSE_CONFIGURATION_KEY.copy(keys = "q")
        val scroll = KeyHint("[/]", "Scroll", inHelpArea = false)
        fun help(navigation: List<KeyHint?>, commands: List<KeyHint?>) = ScreenHelp(
            place(CONFIGURATION_NAME, BROWSE_NAME), PURPOSE_BROWSE, Step.CONFIGURE,
            navigation.filterNotNull(), commands.filterNotNull(),
        )
        return when (view) {
            View.LISTS -> help(
                listOf(SCROLL_KEY, SCROLL_ENDS_KEYS, scroll, KeyHint("Esc", "Back", description = BACK_TO_SUGGESTIONS)),
                listOf(checkAgain, HELP_KEY, close),
            )
            View.DETAILS -> {
                val entry = focusedEntry(draft)
                help(
                    listOf(SCROLL_KEY, SCROLL_ENDS_KEYS, scroll, KeyHint("Esc", "Back", description = BACK_TO_SUGGESTIONS)),
                    listOf(
                        entry?.let { toggleKey(it, draft) }, entry?.let(::editKey), entry?.let(::ignoreKey), checkAgain,
                        HELP_KEY, close,
                    ),
                )
            }
            View.LIST -> {
                val row = selected(draft)
                val item = row?.item
                val entry = (item as? Item.Directory)?.let { entry(draft, it.path) }
                val hidden = hiddenCount(draft)
                help(
                    listOf(
                        KeyHint("↑/↓", "Move", description = SELECT_SUGGESTION).takeIf { item != null },
                        when (item) {
                            is Item.Directory -> entry?.let { toggleKey(it, draft) }
                            is Item.Category, is Item.App -> groupKey(item, groupState(row.entries, draft))
                            null -> null
                        },
                        entry?.let(::editKey),
                        when (item) {
                            is Item.Directory -> KeyHint("Enter", "Inspect", description = INSPECT_SUGGESTION)
                            is Item.Category, is Item.App, null -> null
                        },
                        KeyHint("Esc", "Back", description = BACK_TO_CONFIGURATION_LIST),
                        // On this line for room: the other one is full at 80 columns. Offered while on even when
                        // nothing is found, so the filter can always be turned off.
                        KeyHint("f", if (foundOnly) "Show all" else "Found only", description = showFoundOnly(foundOnly), acts = true)
                            .takeIf { foundOnly || foundCount(draft) > 0 },
                        HOME_END_KEYS.takeIf { item != null },
                    ),
                    listOf(
                        entry?.let(::ignoreKey),
                        // Off the help line, which is full at 80 columns: the line over the list names the key.
                        KeyHint("L", "Take over all", inHelpArea = false, description = TAKE_OVER_ALL)
                            .takeIf { linksToTakeOver(draft).ready.isNotEmpty() },
                        checkAgain, KeyHint("i", "Lists", description = SEE_LISTS),
                        KeyHint("u", (if (reveal) "Hide " else "Show ") + hidden, description = showHidden(reveal))
                            .takeIf { hidden > 0 },
                        HELP_KEY, close,
                    ),
                )
            }
        }
    }

    /**
     * The mouse wheel at `x`, `y`: over the suggestions it moves the selection a row, as ↑/↓ do; over details it
     * scrolls them.
     */
    fun wheel(x: Int, y: Int, delta: Int, draft: BrowseDraft) {
        if (view != View.LIST) { if (viewport.contains(x, y)) viewport.scroll(delta); return }
        if (list.renderedArea()?.contains(x, y) == true) move(draft) { index, _ -> index + delta }
    }

    /** Handles a key, and returns what the draft should do about it: null when only Browse changes. */
    fun key(key: KeyEvent, draft: BrowseDraft): BrowseAction? {
        if (key.isChar('[') || key.isChar(']')) { viewport.scroll(if (key.isChar(']')) 1 else -1); return null }
        if (view == View.LISTS) { scroll(key); return null }
        val item = if (view == View.DETAILS) focus else selected(draft)?.item
        val entry = (item as? Item.Directory)?.let { entry(draft, it.path) }
        val row = entry?.row
        when {
            key.isChar(' ') && (item is Item.Category || item is Item.App) -> {
                message = ""
                val members = rows(draft).firstOrNull { it.item == item }?.entries.orEmpty()
                // While `f` is on, Space adds none of the group's directories that are not found, and says so. Every
                // row in the configuration is shown, so taking a group out is the same either way.
                val unshown = if (foundOnly) {
                    rows(draft, foundOnly = false).firstOrNull { it.item == item }?.entries.orEmpty().filterNot(::shownWhenFiltered)
                } else listOf()
                val statuses = members.map(draft::status)
                return when (groupState(members, draft)) {
                    GroupState.ALL -> BrowseAction.RemoveAll(statuses.filterIsInstance<BrowseDraft.Status.InDraft>().map { it.row })
                    // Two directories under one linked parent take it over once.
                    GroupState.SOME, GroupState.NONE -> BrowseAction.AddAll(
                        statuses.mapNotNull(::addition).distinct(),
                        unaddable = statuses.count { it is BrowseDraft.Status.LinkProblem || it is BrowseDraft.Status.CannotAdd },
                        ignored = statuses.count { it is BrowseDraft.Status.Ignored },
                        notFound = unshown.count { draft.status(it) is BrowseDraft.Status.CanAdd },
                    )
                    GroupState.MANAGED, GroupState.EMPTY -> null
                }
            }
            key.isChar(' ') && entry != null -> {
                focus = item
                message = ""
                return when (val status = draft.status(entry)) {
                    is BrowseDraft.Status.InDraft -> BrowseAction.Remove(status.row)
                    // Takes the linked parent out, as Space on this row took it over.
                    is BrowseDraft.Status.MovesWith -> BrowseAction.Remove(status.row)
                    is BrowseDraft.Status.CanAdd -> addition(status)
                    BrowseDraft.Status.Ignored, is BrowseDraft.Status.LinkProblem, BrowseDraft.Status.CannotAdd -> null
                }
            }
            key.isCharIgnoreCase('e') && row != null -> return BrowseAction.Edit(row)
            key.isCharIgnoreCase('x') && entry != null -> {
                focus = item
                message = ""
                return if (entry.ignored) BrowseAction.StopIgnoring(path(entry)) else BrowseAction.Ignore(path(entry), row)
            }
            view == View.DETAILS -> scroll(key)
            key.isCharIgnoreCase('l') -> {
                val links = linksToTakeOver(draft)
                if (links.ready.isEmpty()) return null
                message = ""
                return BrowseAction.TakeOverAll(links.ready.map(::addition), links.problems)
            }
            key.isCharIgnoreCase('i') -> { view = View.LISTS; viewport.reset(); message = "" }
            key.isCharIgnoreCase('u') && hiddenCount(draft) > 0 -> reveal = !reveal
            key.isCharIgnoreCase('f') && (foundOnly || foundCount(draft) > 0) -> { foundOnly = !foundOnly; message = "" }
            key.isKey(KeyCode.ENTER) && item is Item.Directory -> { focus = item; view = View.DETAILS; viewport.reset() }
            key.isUp() || key.isDown() -> move(draft) { index, _ -> index + if (key.isUp()) -1 else 1 }
            key.isHome() || key.isEnd() -> move(draft) { _, last -> if (key.isEnd()) last else 0 }
        }
        return null
    }

    /** After [BrowseAction.Add]: `refusal` says why the draft did not take the suggestion, or is null when it did. */
    fun added(refusal: String?) {
        message = refusal?.let(::notAdded).orEmpty()
    }

    /**
     * After [BrowseAction.AddAll]: `added` were added, and each of `overlapped` was not, naming the relocation it
     * overlaps when known. Says so only when something was skipped.
     */
    fun addedGroup(added: Int, overlapped: List<Path?>, unaddable: Int, ignored: Int, notFound: Int) {
        message = groupAdded(added, overlapped.map { it?.let(::displayPath) }, unaddable, ignored, notFound).orEmpty()
    }

    /** After [BrowseAction.TakeOverAll]: as [addedGroup], and how many links with problems `L` left out. */
    fun tookOver(added: Int, overlapped: List<Path?>, problems: Int) {
        message = tookOverLinks(added, overlapped.map { it?.let(::displayPath) }, problems)
    }

    fun back(): Boolean {
        if (view == View.LIST) return false
        view = View.LIST; message = ""; viewport.reset(); return true
    }

    private fun scroll(key: KeyEvent) {
        when {
            key.isUp() -> viewport.scroll(-1)
            key.isDown() -> viewport.scroll(1)
            key.isHome() -> viewport.scroll(-Int.MAX_VALUE)
            key.isEnd() -> viewport.scroll(Int.MAX_VALUE)
        }
    }

    private fun move(draft: BrowseDraft, to: (index: Int, last: Int) -> Int) {
        val rows = rows(draft)
        if (rows.isEmpty()) return
        focus = rows[to(selectedIndex(rows), rows.size - 1).coerceIn(0, rows.size - 1)].item
        message = ""
    }

    /** The selected row's index in `rows`: [focus] where it is listed, else the row at the position last shown. */
    private fun selectedIndex(rows: List<Row>): Int =
        rows.indexOfFirst { it.item == focus }.takeIf { it >= 0 } ?: shown.coerceIn(0, maxOf(0, rows.size - 1))

    /** The selected row, which keys act on; null when there are no suggestions. */
    private fun selected(draft: BrowseDraft): Row? = rows(draft).let { it.getOrNull(selectedIndex(it)) }

    /** The listed entries, each in the place it was first listed. */
    private fun listed(draft: BrowseDraft): Map<Path, BrowseDraft.Entry> {
        val entries = entriesByPath(draft)
        order.addAll(entries.keys)
        val place = order.withIndex().associate { (i, path) -> path to i }
        return entries.entries.sortedBy { place.getValue(it.key) }.associate { it.key to it.value }
    }

    /** The shown links that `L` takes over, a linked parent once, and how many shown links it leaves out. */
    private data class Links(val ready: List<BrowseDraft.Status.CanAdd>, val problems: Int)

    private fun linksToTakeOver(draft: BrowseDraft): Links {
        val statuses = rows(draft).filter { it.item is Item.Directory }.map { draft.status(it.entries.single()) }
        return Links(
            statuses.filterIsInstance<BrowseDraft.Status.CanAdd>().filter { it.link != null }.distinct(),
            statuses.filterIsInstance<BrowseDraft.Status.LinkProblem>().distinctBy { it.link.path }.size,
        )
    }

    private fun groupState(members: List<BrowseDraft.Entry>, draft: BrowseDraft): GroupState {
        val statuses = members.map(draft::status)
        val counted = statuses.filter { it is BrowseDraft.Status.InDraft || it is BrowseDraft.Status.CanAdd }
        return when {
            statuses.isNotEmpty() && statuses.all { it is BrowseDraft.Status.MovesWith } -> GroupState.MANAGED
            counted.isEmpty() -> GroupState.EMPTY
            counted.all { it is BrowseDraft.Status.InDraft } -> GroupState.ALL
            counted.none { it is BrowseDraft.Status.InDraft } -> GroupState.NONE
            else -> GroupState.SOME
        }
    }

    private fun groupKey(heading: Item, state: GroupState): KeyHint? {
        val category = heading is Item.Category
        return when (state) {
            GroupState.ALL -> KeyHint(
                "Space", "Remove all",
                description = if (category) REMOVE_CATEGORY else REMOVE_GROUP,
            )
            GroupState.SOME, GroupState.NONE -> KeyHint(
                "Space", "Add all", description = if (foundOnly) ADD_FOUND else if (category) ADD_CATEGORY else ADD_GROUP,
            )
            GroupState.MANAGED, GroupState.EMPTY -> null
        }
    }

    /**
     * The shown rows in screen order: each category's heading, then each of its apps' headings with the app's
     * directories. A directory's app is its first definition's, which is your list's when it names one. Categories and
     * apps keep the order they first appear in; Other tools, then Other directories, come last. A heading with no
     * shown directory beneath it is not listed.
     */
    private fun rows(draft: BrowseDraft, foundOnly: Boolean = this.foundOnly): List<Row> {
        val categories = categories(draft)
        val tops = listed(draft).values
            .filter { entry -> (reveal || !hidden(entry, draft)) && (!foundOnly || shownWhenFiltered(entry)) }
            .groupBy { entry -> app(entry)?.let { Item.Category(categories[it]) } ?: Item.App(null) }
        fun directories(entries: List<BrowseDraft.Entry>) = entries.map { Row(Item.Directory(path(it)), listOf(it)) }
        // A stable sort, so the named categories keep their order.
        return tops.entries.sortedBy { (top, _) -> listOf(Item.Category(null), Item.App(null)).indexOf(top) }
            .flatMap { (top, entries) ->
                listOf(Row(top, entries)) + if (top is Item.Category) {
                    entries.groupBy(::app).flatMap { (app, members) -> listOf(Row(Item.App(app), members)) + directories(members) }
                } else directories(entries)
            }
    }

    /**
     * A heading, which Space acts on as a whole: its mark (`●` all added, `◐` some, `○` none, `−` none can be) after
     * `indent` cells, its name in bold, and at the notes column how many of its directories that can be added are added,
     * or why none can be.
     * Each level is two cells further in than the one above, so headings stand apart without colour.
     */
    private fun headingRow(
        name: String, indent: Int, members: List<BrowseDraft.Entry>, draft: BrowseDraft, selected: Boolean,
    ): StyledElement<*> {
        val statuses = members.map(draft::status)
        val counted = statuses.filter { it is BrowseDraft.Status.InDraft || it is BrowseDraft.Status.CanAdd }
        val added = addedCount(counted.count { it is BrowseDraft.Status.InDraft }, counted.size)
        val (mark, color, count) = when (groupState(members, draft)) {
            GroupState.ALL -> Triple(ADDED_MARK, palette.ok, added)
            GroupState.SOME -> Triple(SOME_ADDED_MARK, palette.text, added)
            GroupState.NONE -> Triple(NOT_ADDED_MARK, palette.text, added)
            // A dim `●`, as on the rows that move with their linked parent.
            GroupState.MANAGED -> Triple(ADDED_MARK, palette.dim, ALL_MANAGED)
            GroupState.EMPTY -> Triple(CANNOT_ADD_MARK, palette.dim, noneToAdd(statuses))
        }
        val label = literal(name)
        return line(
            pointer(selected),
            Span.raw(" ".repeat(indent)),
            Span.styled(mark, Style.EMPTY.fg(color).bold()),
            // The count starts where the directories' notes do.
            Span.styled(
                " " + label + " ".repeat(maxOf(1, PATH_COLUMN + DIRECTORY_INDENT - indent - CharWidth.of(label))),
                Style.EMPTY.fg(palette.text).bold(),
            ),
            Span.styled(count, weight(palette.dim, selected)),
        )
    }

    private fun focusedEntry(draft: BrowseDraft): BrowseDraft.Entry? = (focus as? Item.Directory)?.let { entry(draft, it.path) }

    /**
     * Under the Lists lines: how many directories are found on this machine, once each is checked, and how many are
     * hidden. While `f` is on, it also counts the rows shown because they are in the configuration, though not found.
     * One line, so the list keeps its rows at 24 lines.
     */
    private fun countLine(draft: BrowseDraft): String? = listOfNotNull(
        draft.discovery?.let { result ->
            // The count would climb while rows are checked, so it waits for the last one.
            val checking = result.candidates.any { it.observation.kind == CandidateObservation.Kind.PENDING }
            if (checking) CHECKING_THIS_MACHINE else foundLine(
                foundCount(draft), foundOnly,
                configured = entriesByPath(draft).values.count { it.row != null && !found(it) },
            )
        },
        hiddenCount(draft).takeIf { it > 0 }?.let { hiddenLine(it, reveal) },
    ).takeIf { it.isNotEmpty() }?.joinToString(" · ")

    /** Hidden directories that `u` would show: while only found ones are shown, only those found. */
    private fun hiddenCount(draft: BrowseDraft): Int =
        entriesByPath(draft).values.count { hidden(it, draft) && (!foundOnly || found(it)) }
}

/** Escapes control and format characters as `\uXXXX`, so untrusted text cannot control the terminal. */
internal fun literal(text: String): String = buildString {
    text.codePoints().forEach { point ->
        if (Character.isISOControl(point) || Character.getType(point) == Character.FORMAT.toInt()) {
            append("\\u%04x".format(point))
        } else appendCodePoint(point)
    }
}

private fun entry(draft: BrowseDraft, path: Path): BrowseDraft.Entry? = draft.entries().firstOrNull { it.sourcePath == path }

// Browse lists only entries with a source path: [entriesByPath] leaves out the others.
private fun path(entry: BrowseDraft.Entry): Path = checkNotNull(entry.sourcePath)

private fun entriesByPath(draft: BrowseDraft): Map<Path, BrowseDraft.Entry> {
    val entries = linkedMapOf<Path, BrowseDraft.Entry>()
    draft.entries().forEach { e -> e.sourcePath?.let { path -> entries.putIfAbsent(path, e) } }
    // Discovery's order comes first, so adding or taking out a row never reorders the list while the user marks the
    // rows next to it.
    val ordered = linkedMapOf<Path, BrowseDraft.Entry>()
    draft.discovery?.candidates?.forEach { c ->
        val path = c.catalog.sourcePath
        entries[path]?.let { ordered[path] = it }
    }
    entries.forEach { (path, entry) -> ordered.putIfAbsent(path, entry) }
    return ordered
}

/** Your list's definitions first (see `CandidateCatalog.merge`), so the first one's app and advice win. */
private fun definitions(entry: BrowseDraft.Entry): List<CandidateDefinition> =
    entry.discovery?.catalog?.definitions ?: listOf()

private fun app(entry: BrowseDraft.Entry): String? = definitions(entry).firstOrNull()?.app

/**
 * Each app's category, by app name across both lists: your list's when it gives the app one, else the built-in
 * list's; within a list, the first one given.
 */
private fun categories(draft: BrowseDraft): Map<String, String> =
    draft.discovery?.candidates.orEmpty().flatMap { it.catalog.definitions }
        .sortedBy { it.source.kind != CandidateSource.Kind.SHARED }
        .mapNotNull { d -> d.app?.let { app -> d.category?.let { app to it } } }
        .distinctBy { it.first }.toMap()

/**
 * Hidden when every list that names it marks it usually not needed, and only while each of those lists is read in
 * this check; never when it is in the configuration or ignored, so the user always sees what they chose.
 */
private fun hidden(entry: BrowseDraft.Entry, draft: BrowseDraft): Boolean {
    val definitions = definitions(entry)
    return entry.row == null && !entry.ignored && definitions.isNotEmpty() && definitions.all { d ->
        d.advice == CandidateDefinition.Advice.USUALLY_UNNECESSARY &&
            draft.discovery?.sources.orEmpty().any { s -> s.source == d.source && s.status == CandidateDiscovery.SourceStatus.CURRENT }
    }
}

/**
 * Found on this machine: the last check saw a directory or a link there, or a linked parent above it. A link counts,
 * since a directory Lighten has moved is a link, and so does a row inside a linked parent, which moves with it. A
 * file, a problem or not checked yet does not.
 */
private fun found(entry: BrowseDraft.Entry): Boolean = when (entry.discovery?.observation?.kind) {
    CandidateObservation.Kind.DIRECTORY, CandidateObservation.Kind.LINK, CandidateObservation.Kind.BLOCKED_BY_LINK -> true
    CandidateObservation.Kind.PENDING, CandidateObservation.Kind.MISSING, CandidateObservation.Kind.REGULAR_FILE,
    CandidateObservation.Kind.OTHER, CandidateObservation.Kind.INACCESSIBLE,
    CandidateObservation.Kind.BLOCKED_BY_NON_DIRECTORY, CandidateObservation.Kind.UNKNOWN, null,
    -> false
}

/** Shown while `f` is on: found, or in the configuration, which, as with `u`, is never hidden. */
private fun shownWhenFiltered(entry: BrowseDraft.Entry): Boolean = found(entry) || entry.row != null

/** Every listed directory found, those `u` hides included. */
private fun foundCount(draft: BrowseDraft): Int = entriesByPath(draft).values.count(::found)

/** What Space adds: a relocation from the directory, or a link to take over as it is, with its real path. */
private fun addition(status: BrowseDraft.Status.CanAdd): BrowseAction.Add = BrowseAction.Add(status.source, status.link?.pointsTo)

private fun addition(status: BrowseDraft.Status): BrowseAction.Add? = (status as? BrowseDraft.Status.CanAdd)?.let(::addition)

/**
 * Why a heading's directories can't be added: the reason they all share, or [NONE_CAN_BE_ADDED]. Each directory's
 * Details give its own reason.
 */
private fun noneToAdd(statuses: List<BrowseDraft.Status>): String = statuses.map { status ->
    when (status) {
        BrowseDraft.Status.Ignored -> ALL_IGNORED
        is BrowseDraft.Status.LinkProblem -> ALL_LINK_PROBLEMS
        is BrowseDraft.Status.InDraft, is BrowseDraft.Status.MovesWith, is BrowseDraft.Status.CanAdd,
        BrowseDraft.Status.CannotAdd,
        -> null
    }
}.distinct().singleOrNull() ?: NONE_CAN_BE_ADDED

private fun toggleKey(entry: BrowseDraft.Entry, draft: BrowseDraft): KeyHint? = when (val status = draft.status(entry)) {
    is BrowseDraft.Status.InDraft -> KeyHint("Space", "Remove", description = REMOVE_SUGGESTION)
    is BrowseDraft.Status.MovesWith -> KeyHint("Space", "Remove", description = REMOVE_LINKED_PARENT)
    is BrowseDraft.Status.CanAdd ->
        if (status.link == null) KeyHint("Space", "Add", description = ADD_SUGGESTION)
        else KeyHint("Space", "Take over", description = TAKE_OVER_LINK)
    BrowseDraft.Status.Ignored, is BrowseDraft.Status.LinkProblem, BrowseDraft.Status.CannotAdd -> null
}

private fun ignoreKey(entry: BrowseDraft.Entry): KeyHint =
    if (entry.ignored) KeyHint("x", "Stop ignoring", description = BROWSE_STOP_IGNORING)
    else KeyHint("x", "Ignore", description = BROWSE_IGNORE)

private fun editKey(entry: BrowseDraft.Entry): KeyHint? =
    KeyHint("e", "Edit", description = EDIT_SUGGESTION).takeIf { entry.row != null }

/**
 * A directory row under its app, indented [DIRECTORY_INDENT] cells: the mark (`●` added, a dim `●` moved with its linked
 * parent, `○` not added, `⊘` ignored, `−` cannot be added), the path under the source root and its notes. A note that
 * only says the directory is not there yet is dim. The other notes keep their weight.
 */
private fun directoryRow(entry: BrowseDraft.Entry, draft: BrowseDraft, selected: Boolean): StyledElement<*> {
    val status = draft.status(entry)
    val marker = when (status) {
        is BrowseDraft.Status.InDraft, is BrowseDraft.Status.MovesWith -> ADDED_MARK
        BrowseDraft.Status.Ignored -> IGNORED_MARK
        is BrowseDraft.Status.CanAdd -> NOT_ADDED_MARK
        is BrowseDraft.Status.LinkProblem, BrowseDraft.Status.CannotAdd -> CANNOT_ADD_MARK
    }
    val path = compact(relative(draft, path(entry)))
    val main = if (entry.row != null) palette.ok else palette.text
    val directory = entry.discovery?.observation?.kind == CandidateObservation.Kind.DIRECTORY
    val notes = listOfNotNull(
        (IGNORED_NOTE to palette.text).takeIf { entry.ignored },
        stateNote(entry, draft).takeIf { !directory },
        (USUALLY_NOT_NEEDED_NOTE to palette.text)
            .takeIf { definitions(entry).firstOrNull()?.advice == CandidateDefinition.Advice.USUALLY_UNNECESSARY },
    )
    val muted = status !is BrowseDraft.Status.InDraft && status !is BrowseDraft.Status.CanAdd
    return line(
        pointer(selected),
        Span.styled(" ".repeat(DIRECTORY_INDENT), weight(main, selected)),
        Span.styled(marker, weight(if (muted) palette.dim else main, selected)),
        Span.styled(" " + path + " ".repeat(maxOf(1, PATH_COLUMN - CharWidth.of(path))), weight(main, selected)),
        *notes.flatMapIndexed { i, (note, color) ->
            listOfNotNull(Span.styled(" · ", weight(palette.dim, selected)).takeIf { i > 0 }, Span.styled(note, weight(color, selected)))
        }.toTypedArray(),
    )
}

/** The one-cell pointer; the selected row is also bold. */
private fun pointer(selected: Boolean): Span = Span.styled(if (selected) "❯" else " ", Style.EMPTY.fg(palette.focus).bold())

private fun weight(color: Color, selected: Boolean): Style = Style.EMPTY.fg(color).let { if (selected) it.bold() else it }

private fun line(vararg spans: Span): StyledElement<*> = Toolkit.richText(Text.from(dev.tamboui.text.Line.from(spans.toList())))

private fun relative(draft: BrowseDraft, path: Path): String =
    if (path.startsWith(draft.sourceRoot)) draft.sourceRoot.relativize(path).toString() else displayPath(path)

/** A path from discovery, escaped for the screen. */
private fun shownPath(path: Path): String = literal(displayPath(path))

private fun compact(path: String): String {
    val value = literal(path)
    return if (value.length <= 28) value else value.substring(0, 13) + "…" + value.substring(value.length - 14)
}

/**
 * The longest path a row shows, [compact]'s 28 cells, and two spaces before the notes. With [DIRECTORY_INDENT] the
 * notes start at column 38, so the longest note, 40 cells, fits beside the scrollbar at 80 columns.
 */
private const val PATH_COLUMN = 30

/** A directory sits under an app under a category, two cells further in per level. */
private const val DIRECTORY_INDENT = 4

/**
 * What the last check saw at `entry`, and its color: for a link, whether it can be taken over, else the observation's
 * note. A row in the draft that is a linked parent has no observation of its own, but its link is known.
 */
private fun stateNote(entry: BrowseDraft.Entry, draft: BrowseDraft): Pair<String, Color> {
    val link = draft.link(entry)
    val status = draft.status(entry)
    if (link != null && link.path != entry.sourcePath) {
        val parent = shownPath(link.path)
        return if (status is BrowseDraft.Status.MovesWith) insideManaged(parent) to palette.text
        else insideLink(parent) to if (status is BrowseDraft.Status.LinkProblem) palette.warn else palette.text
    }
    if (link != null) {
        return if (status is BrowseDraft.Status.LinkProblem) linkProblemNote(status.problem) to palette.warn
        else ALREADY_A_LINK to palette.text
    }
    val observation = entry.discovery?.observation ?: return NOT_CHECKED to palette.dim
    return observationNote(observation) to when (observation.kind) {
        CandidateObservation.Kind.MISSING, CandidateObservation.Kind.PENDING -> palette.dim
        CandidateObservation.Kind.LINK, CandidateObservation.Kind.DIRECTORY -> palette.text
        CandidateObservation.Kind.REGULAR_FILE, CandidateObservation.Kind.OTHER, CandidateObservation.Kind.INACCESSIBLE,
        CandidateObservation.Kind.BLOCKED_BY_LINK, CandidateObservation.Kind.BLOCKED_BY_NON_DIRECTORY,
        CandidateObservation.Kind.UNKNOWN,
        -> palette.warn
    }
}

/** A link's problem in a row's note, which has 40 cells at 80 columns; Details says it in full. */
private fun linkProblemNote(problem: BrowseDraft.Problem): String = when (problem) {
    is BrowseDraft.Problem.Target -> linkTargetNote(problem.target)
    is BrowseDraft.Problem.Ignored -> IGNORED_NOTE
    is BrowseDraft.Problem.Overlap -> linkOverlapsNote(problem.other?.let(::shownPath))
}

/** Why a link can't be taken over, as Details says it. */
private fun linkProblem(problem: BrowseDraft.Problem): String = when (problem) {
    is BrowseDraft.Problem.Target -> linkTargetProblem(problem.target)
    is BrowseDraft.Problem.Ignored -> LINK_IGNORED
    is BrowseDraft.Problem.Overlap -> linkOverlaps(problem.other?.let(::shownPath))
}

/** The two Lists lines over the suggestions: the built-in list, then yours, each with its state. */
private fun listLines(draft: BrowseDraft): List<Line> {
    val sources = draft.discovery?.sources.orEmpty()
    val yours = sources.firstOrNull { it.source.kind == CandidateSource.Kind.SHARED }
    val yourLine = when {
        yours != null -> listLine(yours)
        draft.discovery?.request?.sharedLocation == null && draft.discovery != null -> Line(NO_LIST_OF_YOUR_OWN, palette.dim)
        else -> Line(listSummary(YOUR_LIST, null, LIST_CHECKING), palette.dim)
    }
    val builtIn = sources.firstOrNull { it.source.kind == CandidateSource.Kind.BUNDLED }
    return listOf(builtIn?.let(::listLine) ?: Line(listSummary(BUILT_IN_LIST, null, LIST_CHECKING), palette.dim), yourLine)
}

private fun listLine(outcome: CandidateDiscovery.SourceOutcome): Line {
    val name = listName(outcome.source)
    val location = outcome.source.takeIf { it.kind == CandidateSource.Kind.SHARED }?.let { displayPath(Path.of(it.location)) }
    return when (outcome.status) {
        CandidateDiscovery.SourceStatus.PENDING -> Line(listSummary(name, location, LIST_CHECKING), palette.dim)
        CandidateDiscovery.SourceStatus.CURRENT -> Line(
            listSummary(
                name, location,
                suggestionCount(outcome.catalog?.definitions.orEmpty().map { it.sourcePath }.distinct().size),
                outcome.modified?.let(::fileUpdated),
            ),
            palette.dim,
        )
        CandidateDiscovery.SourceStatus.FAILED -> Line(
            listSummary(name, location, listNotUsed(outcome.problems.firstOrNull()?.let { listProblem(it.kind) } ?: listErrors(outcome.diagnostics.size))),
            palette.warn,
        )
    }
}

private fun listName(source: CandidateSource): String =
    if (source.kind == CandidateSource.Kind.BUNDLED) BUILT_IN_LIST else YOUR_LIST

/** The `i` view: each list's line, then where it is and everything said about why it was not used. */
private fun listDetails(draft: BrowseDraft): List<Line> {
    val result = draft.discovery ?: return listOf(Line(LISTS_DO_NOT_BLOCK))
    return listOf(Line(LISTS_DO_NOT_BLOCK)) + result.sources.flatMap { source ->
        listOf(listLine(source).copy(bold = true)) +
            listOfNotNull(source.source.takeIf { it.kind == CandidateSource.Kind.SHARED }?.let { Line(location(shownPath(Path.of(it.location)))) }) +
            source.problems.flatMap { problem ->
                listOf(Line(listProblemAdvice(problem.kind), palette.warn), Line(diagnostic(literal(problem.detail))))
            } +
            listOfNotNull(Line(LIST_REJECTED, palette.warn).takeIf { source.diagnostics.isNotEmpty() }) +
            source.diagnostics.map { d ->
                Line(
                    literal(d.message) + (if (d.location.isEmpty()) "" else " · " + literal(d.location)) +
                        (if (d.line == 0) "" else " · " + inputPosition(d.line, d.column)),
                    palette.warn,
                )
            }
    } + listOfNotNull(result.rootFailure?.let { d -> Line(rootProblem(literal(d.detail), shownPath(d.path))) })
}

/** The details of `entry`, or that the directory at `path` is no longer listed. */
private fun detailLines(entry: BrowseDraft.Entry?, path: Path?, draft: BrowseDraft): List<Line> {
    if (entry == null) {
        return listOfNotNull(Line(NO_LONGER_LISTED), path?.let { Line(sourceText(shownPath(it))) }, Line(BACK_FOR_SUGGESTIONS))
    }
    val candidate = entry.discovery
    val observation = candidate?.observation
    val missing = observation?.kind == CandidateObservation.Kind.MISSING
    return listOfNotNull(
        Line(
            when {
                entry.row != null -> IN_CONFIGURATION
                entry.ignored -> IGNORED_BY_YOU
                else -> NOT_IN_CONFIGURATION
            },
            palette.text, true,
        ),
        Line(sourceText(shownPath(path(entry)))),
        Line(stateLine(stateNote(entry, draft).first)),
    ) + (if (missing) MISSING_SUGGESTION.map(::Line) else listOf()) +
        listOfNotNull(
            Line(SIZE_AND_OWNERSHIP),
            observation?.takeIf { it.kind != CandidateObservation.Kind.PENDING }?.let { Line(observedLine(it.observedAt.toString())) },
            draft.link(entry)?.let { link ->
                Line(linkLine(shownPath(link.path), shownPath(link.pointsTo), literal(link.text.toString()).takeIf { link.text != link.pointsTo }))
            },
        ) +
        observation?.diagnostics.orEmpty().map { d -> Line(observationDetail(literal(d.detail), shownPath(d.path))) } +
        candidate?.ancestors.orEmpty().map { Line(suggestedAround(shownPath(it))) } +
        draft.discovery?.candidates.orEmpty().filter { c -> candidate != null && candidate.catalog.sourcePath in c.ancestors }
            .map { c -> Line(suggestedInside(shownPath(c.catalog.sourcePath))) } +
        Line(whatSpaceDoes(entry, draft)) +
        attribution(entry)
}

/** The Details line that says what Space does for `entry`, or why it does nothing. */
private fun whatSpaceDoes(entry: BrowseDraft.Entry, draft: BrowseDraft): String = when (val status = draft.status(entry)) {
    is BrowseDraft.Status.InDraft -> CHANGE_IN_CONFIGURATION
    BrowseDraft.Status.Ignored -> IGNORED_IN_BROWSE
    is BrowseDraft.Status.MovesWith -> movesWithParent(shownPath(status.parent))
    is BrowseDraft.Status.CanAdd -> when (val link = status.link) {
        null -> ADDING_ASKS
        else -> if (link.path == entry.sourcePath) takesOverLink(shownPath(link.pointsTo))
        else takesOverParent(shownPath(link.path), shownPath(link.pointsTo))
    }
    is BrowseDraft.Status.LinkProblem -> cannotTakeOver(shownPath(status.link.path), linkProblem(status.problem))
    BrowseDraft.Status.CannotAdd -> CANNOT_ADD
}

/** Which lists suggest it and what each says, your list's first, so the built-in advice shows too. */
private fun attribution(entry: BrowseDraft.Entry): List<Line> {
    val definitions = definitions(entry)
    if (definitions.isEmpty()) return listOf(Line(NO_LIST_SUGGESTS))
    return listOf(Line(SUGGESTED_BY, palette.text, true)) + definitions.flatMap { d ->
        listOfNotNull(
            Line(listName(d.source) + " · " + literal(d.app ?: OTHER_DIRECTORIES)),
            Line(adviceLine(adviceLabel(d.advice))),
            d.reason?.let { Line(reasonLine(literal(it))) },
            d.caution?.let { Line(cautionLine(literal(it)), palette.warn) },
            // The built-in list is inside Lighten, so only your list has a location worth showing.
            Line(
                fromLine(
                    d.source.location.takeIf { d.source.kind == CandidateSource.Kind.SHARED }?.let { shownPath(Path.of(it)) },
                    literal(d.location), literal(d.originalPath),
                ),
            ),
        )
    }
}

private fun adviceLabel(advice: CandidateDefinition.Advice?): String = when (advice) {
    CandidateDefinition.Advice.CONSIDER -> ADVICE_CONSIDER
    CandidateDefinition.Advice.USUALLY_UNNECESSARY -> ADVICE_USUALLY_NOT_NEEDED
    null -> ADVICE_NOT_GIVEN
}

private fun listProblem(kind: CandidateDiscovery.SourceProblem.Kind): String = when (kind) {
    CandidateDiscovery.SourceProblem.Kind.MISSING -> LIST_MISSING
    CandidateDiscovery.SourceProblem.Kind.UNREADABLE -> LIST_UNREADABLE
    CandidateDiscovery.SourceProblem.Kind.NOT_REGULAR -> LIST_NOT_REGULAR
    CandidateDiscovery.SourceProblem.Kind.IO_ERROR -> LIST_IO_ERROR
    CandidateDiscovery.SourceProblem.Kind.DEADLINE -> LIST_DEADLINE
    CandidateDiscovery.SourceProblem.Kind.PREVIOUS_PENDING -> LIST_PREVIOUS_PENDING
}

private fun listProblemAdvice(kind: CandidateDiscovery.SourceProblem.Kind): String = when (kind) {
    CandidateDiscovery.SourceProblem.Kind.MISSING -> LIST_MISSING_ADVICE
    CandidateDiscovery.SourceProblem.Kind.UNREADABLE -> LIST_UNREADABLE_ADVICE
    CandidateDiscovery.SourceProblem.Kind.NOT_REGULAR -> LIST_NOT_REGULAR_ADVICE
    CandidateDiscovery.SourceProblem.Kind.IO_ERROR -> LIST_IO_ERROR_ADVICE
    CandidateDiscovery.SourceProblem.Kind.DEADLINE -> LIST_DEADLINE_ADVICE
    CandidateDiscovery.SourceProblem.Kind.PREVIOUS_PENDING -> LIST_PREVIOUS_PENDING_ADVICE
}
