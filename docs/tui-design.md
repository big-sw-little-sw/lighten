# Lighten TUI Design

This file gives the current rules for the full-screen TUI. It says only what the
TUI must do now. The reasons for each rule are in the TUI section of
[decisions.md](decisions.md#tui). When you change one of these rules, update
this file in the same pull request.

## 1. Principles

- **TamboUI first.** When a TamboUI widget or feature does the job, use it and
  delete our own version. Write our own code only where TamboUI cannot do what a
  rule needs. Then give the reason in a comment.
- **Plain language.** Help, labels and messages use everyday words. They do not
  use internal names, configuration keys or enum values. The configuration's
  "policy" is a **rule** on screen.
- **Say the next step.** After a save, a check again or a finished apply, tell
  the user what to do next, when there is something to do. For example: `Saved.
  1 relocation will change: press a to review and apply.`
- **Dialogs for short questions, screens for work.** A dialog shows over the
  current screen. It asks one question or confirms one action, and it always
  returns the user to exactly where they were. A screen holds work with several
  steps. It has its own navigation and one clearly labelled way back, which
  keeps the user's place.
- **Safety stays explicit.** Only `y` confirms changes on disk or the
  replacement of a file. Enter never does. Saving never applies. Applying never
  saves.

## 2. Screens and navigation

Lighten is one TamboUI application that stays open. The JSON commands share the
planning and applying behaviour with the TUI, but not its screens.

| Screen | Purpose | Way back |
| --- | --- | --- |
| Workspace | Current state of every relocation, one-time choices, entry to everything else | It is the home screen |
| Review | The exact plan to apply, then progress and results in place | `1`, or `n`/Esc before confirming |
| Configuration | Create or edit the configuration file | Esc from the list closes it |
| Browse | Suggested directories to add, inside Configuration | Esc returns to Configuration |
| Help | Two tabs: This screen (place, purpose, step and keys) and Guide (the user guide) | Esc, `q`, `?` or F1 returns where you were |

The header shows `⌂ LIGHTEN` and then the numbered screens, for example
`[1: Workspace]  [2: Review]`. The second slot reads `[2: Results]` while
Lighten keeps the results. It reads `[Review unavailable]` when the plan cannot
be reviewed. During an apply, the header shows only `[Applying]`. Configuration
shows as `[Configuration]`, and Browse as `[Configuration › Browse]`.

Ways in:

- `lighten`, `status`, `plan` and `apply` open Workspace. `apply` never starts
  changes without review.
- `lighten init` and `lighten config` open Configuration. If the file exists,
  Configuration loads it for editing. If it does not exist, Configuration starts
  a new file. The header says `existing file` or `new file`.
- From Workspace, `e` opens Configuration. When there is no configuration, `i`
  opens it.

## 3. Keys and focus

- Keys: TamboUI's `standard` bindings (arrows, Home/End, PageUp/PageDown). There
  are no vim letters, so every printable key types into a focused text field.
- Focus uses TamboUI's `FocusManager`, with a fixed id for each element that can
  take focus. Tab and Shift-Tab move through every control on the screen in
  order: the list, then each field, then back. Inside a form, ↑/↓ also move
  between fields.
- Esc goes back one level. From a field it goes to the field's list. From a
  list it closes the screen. From a dialog it cancels the dialog. At
  Workspace's list it does nothing. Esc never exits.
- `q` quits from any list. Inside a text field it types `q`. In Configuration,
  `q` and Esc from the list close Configuration. If the draft is the same as the
  file when it was opened, it closes at once. If not, Lighten asks before it
  discards the draft. When there are one-time choices that are not applied yet,
  or during an apply, `q` asks first (see Quit).
- One app key handler handles the keys that TamboUI elements do not handle. It
  uses the focused id to decide what to do, and it always reports the key as
  handled.
- Lighten captures the mouse, but uses only its wheel. Wheel up and down scroll
  the pane under the pointer. Over a list, they move its selection. They never
  change focus or a tab. Sideways scrolling, clicks, drags and taps do nothing.
  Because the mouse is captured, the user must hold the terminal's bypass
  modifier to select text: Shift-drag in WezTerm and Ghostty, Option-drag in
  iTerm2. The guide says this. TamboUI turns capture off when Lighten exits, on
  every exit path.

Global keys on Workspace and Review: `1` Workspace, `2` Review or Results, `r`
check again, `q` quit. `2` never starts changes.

### Quit

`q` (or Ctrl-C) exits at once, unless something would be lost:

- **One-time choices not applied yet** (Workspace or Review): a dialog asks
  first. The count reads `1 choice` or `2 choices`:

  ```
  Quit Lighten?
  You have 2 choices that are not applied yet. Quitting forgets them.
  Press n to go back. You can keep choosing, or press a to review and apply.
  To make a choice the rule, select its relocation and press s.
  y: Quit · n/Esc: Go back
  ```

  A plan with no one-time choices does not ask, because the next run makes the
  same plan again.
- **During an apply:** see section 6, Review, applying and results.

After the user sets Lighten to exit when the apply finishes, `q` does nothing.
So the help stops showing `q: Quit`.

## 4. Visual language

### Color

Lighten paints its own dark background on the whole screen. It uses the
**Harbor** palette as exact RGB colors. If `COLORTERM` is not `truecolor` or
`24bit`, each role uses the basic ANSI color of the same hue instead. (In ANSI
names, white is light gray and bright black is dark gray.) Views name only
roles. One palette file maps roles to colors. Headings inside panes are bold
body text.

| Role | Color | Basic | Used for |
| --- | --- | --- | --- |
| background | `#1b1d22` | black | Whole screen |
| text | `#d8dbe2` | white | Body text |
| brand | `#7fd1c7` | cyan | `⌂ LIGHTEN` |
| focus | `#6cb6e8` | bright blue | Focused pane border, `❯` pointer, running spinner, active header slot, scrollbar thumb |
| ok | `#7cc79a` | green | Done, in sync, the chosen value, gauge fill |
| change | `#7fd1c7` | cyan | Rows that apply will change |
| warn | `#e6b55c` | yellow | Needs a choice, data will be deleted or replaced |
| error | `#e76f6f` | red | Failed, blocked |
| dialog | `#b39cf0` | magenta | Dialog border and title |
| dim | `#6f7a88` | bright black | Help lines, inactive borders, pending steps, secondary notes |

Color alone may carry meaning when the screen also shows the same information
in another way: text, a glyph or a count. Choose colors that look good, not
only colors that are safe.

### Layout and glyphs

- Master-detail panes: the list takes about 45% of the width, and the details
  take the rest. The focused pane has a thick border (`┏━┓`) in the focus
  color. Other panes have a plain border (`┌─┐`) and are dim. The border shape
  shows focus without color. Lists, Browse and Review's plan are inside a
  TamboUI panel, because their elements have no thick border.
- `❯` marks the selected row or the focused field. A text field shows its
  cursor.
- Glyphs: `✔` done or in sync, `⠋…⠏` running (TamboUI `Spinner`), `○` pending,
  `✖` failed or blocked, `⚠` needs attention, `─` left as is, `⚡` will change.
- There are three sets of marks, and they share one rule. An empty circle means
  that nothing has happened to the row yet, or that the row is not included. A
  filled circle, or `✔`, means that it has. Every set uses marks with nothing
  around them. The marks do not show whether only one row of a group can be
  chosen. The behaviour shows it: choosing one choice clears the others, and
  the Help for the choice keys says so.
  - Progress (Review, Applying, Results): `○` not run yet, spinner running,
    `✔` done, `✖` failed.
  - Included or not (Browse): `●` added, a dim `●` moved with its linked
    parent, `○` not added, `⊘` ignored by you, `−` can't be added. On a category or app heading, `◐` means some are added.
    `⊘` is the empty circle with a line through it: not included, on purpose.
    Its shape is different from every other mark, and its note says `ignored
    by you`, so it is clear without color. A group row gets a mark only when
    the user can select the group itself and act on it. A heading that is only
    a label gets no mark. Relocation rows in Review, Applying and Results keep
    their progress marks, because those marks show status, not selection.
  - One of several choices (Workspace Details): `●` chosen, `○` not chosen.
    The chosen choice is green and bold, focused or not. The focused choice has
    the `❯` pointer. Without color, `●` and bold mark the choice, and `❯` marks
    focus.
- Every path on screen shows the home directory as `~`. This includes Paths
  sections, and the CLI's messages to people. Only the home directory becomes
  `~`: a path under a `source-root` in another place shows in full. JSON keeps
  every path in full. Configuration's fields show the file's text as written.
- Wrap prose at words. Wrap a path at any character only when it has no other
  place to break.
- Scrollbars show only when the content does not fit. There are no line
  counters.
- When there are other relocations, Lighten hides the relocations that are in
  sync. `c` shows or hides them. The list title says how many are hidden and
  which key shows them: `Relocations · c: show 2 in sync`, or `c: hide 2 in
  sync` while they show. Workspace rows are in groups, by how urgent they are:
  needs a choice, blocked or can't read; warning; changes; left as is; in sync.
  In each group, the rows keep the order of the configuration file. This is the
  order that Configuration's list shows. A row that changes group, for example
  after a choice, moves and stays selected.
- Ignored sources come last, below in sync. They are a group that is closed at
  the start of each run. Its heading is a row in dim text: `i: show 2 ignored`,
  or `i: hide 2 ignored` while the group is open. `i` opens and closes the group
  from anywhere on the Workspace, and Help lists `i`. While the group is open,
  each ignored source follows as `[Ignored] ~/path`, in the file's order. The
  user can select the heading. Its Details say what ignoring means and how to
  open the group. `x` does nothing on the heading.

### Dialogs

Dialogs use TamboUI `dialog()`. A dialog has a double border in the dialog
color. It is centered both ways and sized to its content, with one cell of
padding. It never covers the header or the help lines. While a dialog is open,
everything behind it cannot take focus and loses its focus highlight, so only
the dialog looks active. Dialog keys: `y` confirms, `n`/Esc cancel. Lighten
ignores every other key.

### Choices and fields

- Every choice among fixed values is a TamboUI `Select`: `‹ Keep target, archive
  source ›`. ←/→ change it. Labels are beside their fields, in a fixed column.
- Text fields are TamboUI text inputs: ←/→, Home/End, Backspace, Delete, and
  Ctrl-U to clear. `[` and `]` type as usual.

### Help area

The help area is two lines at the bottom. They are specific to the focused
element: navigation first, then commands. Each binding shows once. Never show a
key that does nothing now. Every screen shows `?: Help` before `q`. In a text
field, `?` types a question mark, so there the help lines show `F1: Help`
instead. F1 opens Help on every screen.

Each screen makes its help in one function (`ScreenHelp`). The help has the
screen's place (such as `Configuration › Target root`), one purpose line for its
current state, its step, and its keys. The help lines and the Help screen both
read this help, so they cannot disagree. Some keys are left out of the help
lines because there is no room: PageUp/PageDown, Home/End, `[`/`]`, `←` for
back, `c` in the list title, and `i` on the ignored group's heading. These keys
are marked Help-only.

### Help screen

`?` (or F1) opens a full-screen **Help** screen from any screen. Its header is
`⌂ LIGHTEN  [Help]`. Below it is a TamboUI tab bar with two tabs:

1. **This screen**: a pane with the place as its title. It shows the purpose
   line, then `Step: Configure › Workspace › Review › Apply › Results`, with
   the current step bold in the focus color. (Configuration and Browse are
   Configure. Applying is Apply.) Then it shows the heading `Keys on <place>`,
   the line "They work after you go back (Esc or q). In Help they do nothing.",
   and the keys in two groups, "Move around" and "Do". Each group title is
   directly above its keys. Each key shows as `key  description`. Help's own
   keys are only on its help lines.

   By default, a key's `description` is the same as its help-line `action`.
   Some short labels make sense only on their screen. For those, the
   description says what the key acts on and whether it asks first, for example
   `↑/↓  Select a relocation` or `q  Quit Lighten; asks first if choices are
   not applied or changes are running`. One `KeyHint` holds both texts, so the
   two places use one source.
2. **Guide**: `docs/user-guide.md` as packaged in the build. TamboUI's Markdown
   element shows it in the palette's colors. The guide is the only copy of its
   text. Nothing in the code repeats it.

Help opens on This screen, with one exception: the empty Workspace before there
is a configuration file. From there, Help opens on Guide, and that Workspace
says `New to Lighten? Press ? to read the guide.` (A Workspace with a file but
no relocations says `Press ? for help.`) From Configuration, Help opens on This
screen, also from a text field.

Keys: Tab and ←/→ change tabs (not `1`/`2`). ↑/↓, PageUp/PageDown, Home/End and
`[`/`]` scroll the open tab. Each tab keeps its scroll position. Esc, `q`, `?`
and F1 all go back to exactly where the user was, with focus and selection
kept. In Help, `q` never quits and never opens a screen's discard question.
This is the same as in less, man and other help screens. Ctrl+C quits in the
usual way, as on every screen. So it shows the quit question when there are
choices that are not applied, the discard question over a Configuration draft,
and the exit-when-finished dialog during an apply. Every other key does
nothing. An apply continues behind Help.

Help lines: `↑/↓: Scroll · PageUp/PageDown: Page · Home/End: Top/bottom` and
`Tab/←/→: Other tab · Esc/q: Back to <screen>`. `<screen>` is the screen in the
title of the This screen pane, for example `Back to Configuration`. `?` and F1
also go back, but the help lines do not show them, because the reader used one
of them to open Help. When the open tab has nothing to scroll, the first line
is empty. All scroll keys are then left out, as on any screen.

The tab bar shows the open tab bold in the focus color, and the other tab dim
(the `TabsElement` highlight style). Bold marks the open tab and the current
step without color too. Lighten keeps bold in the basic palette. Neither
Lighten nor TamboUI removes styles for `NO_COLOR`. So no other marker is
necessary.

TamboUI moves focus on Tab before any handler gets the key. So the open tab
follows focus: the open tab's pane has the focus id of that tab, and the tab
bar has the focus id of the other tab.

The mouse wheel scrolls the open tab and never changes the tab (see section 3,
Keys and focus).

`lighten guide` prints the same guide as Markdown. `lighten --help` ends with
the guide's online address.

## 5. Workspace

Summary rows count relocations:
`4 relocations · ⚡ 2 to change · ⚠ 0 need a choice · ✖ 0 blocked` and
`✔ 2 in sync · ─ 0 left as is`. A risk row shows only when a count is not zero:
`Of these: 1 with warnings · 1 deletes data`.

A relocation **deletes data** when apply removes content that is not kept in
another place. This happens when apply deletes the source and keeps the target,
deletes both, or deletes the original source that an interrupted replacement
left behind. A Move does not delete data. It replaces the source with a link
only after it checks the copy at the target, and its **Will do** line says so.

The details pane gives this information, in this order:

1. **Now:** what is on disk, for example `Now: both ~/.cache/uv and its target
   are directories.` Keep the link, unreadable and missing cases explicit.
2. **Decision:** one line. It shows only when a rule governs the case on disk
   now (both exist, or only the target exists), or when a choice is set. It
   says what will happen and where that comes from: `Decision: ask each time
   (your configuration)` or `Decision: keep target, delete source (your choice,
   this run only)`. Some rows have no rule that governs them: Move, Link, In
   sync, blocked and can't read. These rows have no Decision line. A choice is
   for the next apply only. Any check again, save or apply clears it.
3. **Will do:** the result of the current rule or choice. Without a choice:
   `Will do: nothing until you choose.` A blocked row adds `Problem: …` with
   the reason. For example, a step must create or use a directory, but something
   else is at that path: `Problem: /scratch/archive is a file, not a directory.`
   Relocations that overlap block only themselves, and each one names the
   other: `Problem: ~/a contains ~/a/b, which is also a relocation.` A move is
   blocked before Review when its staging directory is on a different filesystem
   from its target. The problem names both paths and the `staging-root`
   setting. When one of the row's choices makes a plan without that directory,
   the next line is `Or choose an option below that doesn't need this
   directory.` A row that deletes data adds `⚠ This deletes data for good.`
4. **Choices:** the choices that apply. They show only when a choice is
   necessary. While archiving is only offered and not chosen, its choice names
   the destination: `Move the source to ~/.cache/.lighten-archive/… and replace
   it with a link to the target.`
5. **Paths:** the source, the target and the current link destination, each
   once. `Archive:` shows only when the rule or choice archives the source.
   `Left behind:` shows only when apply deletes what an interrupted replacement
   left.

`s: Always do this` shows on the navigation line, beside the choice keys. It
shows while the selected relocation has a choice that is different from its
rule. A choice that the rule already makes has nothing to save. `s` opens a
dialog. The dialog says the rule in words (`From now on, for ~/.cache/uv,` /
`when the source and the target both exist: keep target, delete source.`). It
says that `y` saves the rule in the configuration file, and it names the file.
It says that comments in the file are not kept, and that nothing on disk
changes until apply. Some rules delete data: Keep target, delete source; and
Delete both, start empty. For these, the dialog adds a `⚠` line in the warning
color. The line says that the rule deletes data for good every time it
applies, `lighten apply --yes` included.

`y` writes the rule fields of this relocation to the file. It uses the same
replace as Configuration, which refuses if the file changed after Lighten read
it. Then it checks again, so the Decision line reads `(your configuration)` and
the choice is gone. Then it says the next step. Focus goes back to the list,
with Details scrolled to the top, as after a save in Configuration. This keeps
the Decision line in view. If the save is refused, the choice stays, and a line
below the panes says so. In Details, the help line shows `Esc: Back`. `Tab` and
`←` also go back, but they are Help-only, so that the line fits in 80 columns.
Help › This screen still lists `s` under Do.

**Ignoring a source.** An ignored source is one that the user told Lighten to
leave alone, for example because another tool such as Stow manages it. Lighten
plans nothing for it and never offers to add it. But Lighten always shows it:
in the Workspace's ignored group and in Browse. A path cannot be both a
relocation and ignored. The loader refuses a file that has such a path
(`relocations[1].source-path and ignored-source-paths[0] are both ~/b. A path
can't be both a relocation and ignored: remove it from one of the two
lists.`). Every save refuses it too.

- `x: Ignore` on a relocation opens a dialog, `Ignore ~/.cache/uv?`. The dialog
  says that Lighten will stop managing the directory. It says that `y` moves it
  from relocations to ignored-source-paths in the configuration file, and it
  names the file. It says that Lighten writes the whole file again, that
  comments are not kept, and that nothing on disk changes. When the relocation
  is linked now (`The source link already points to the target.`), a `⚠` line
  in the warning color adds more. It says that the link and the files at the
  target stay as they are. It also says how to undo the move by hand: `rm` the
  link, then `mv` the target back.
- `x: Stop ignoring` on an ignored row asks `Stop ignoring ~/x?` in the same
  way. It adds that Lighten manages the directory only after the user adds it
  as a relocation.
- Saving checks again, and this forgets one-time choices. While a different
  relocation has a one-time choice, both dialogs add `This also forgets your
  other one-time choices.` The ignored relocation's own choice is removed with
  it, so that choice alone does not add the line.
- `y` saves in the same way as `s`. It refuses if the file changed after
  Lighten read it: `Not saved: the configuration file changed after Lighten
  read it. Press r to read the file again, then x again; that forgets one-time
  choices.` Then it checks again and says the next step. `n`/Esc cancel.
- `x` is on the navigation line beside `s`, and Help lists it under Do. In
  Details with choices, the line has no room, so there `x` is Help-only. It
  works from both panes. Lighten does not offer `x` while it keeps results.
- An ignored row's Details say `Ignored by you`. They say that Lighten plans
  nothing for it and leaves it as it is. They say how to undo this (`x`), and
  they show its path.
- The user can ignore the last relocation. A file that only ignores paths
  saves and loads, and the Workspace then has no relocations.

When review is not available, one line below the panes says why: `Choose what
to do for each relocation marked Choose.` or `Fix the blocked paths; see
Details.`

`e: Edit` opens Configuration on the file every time the file loads. When
Lighten cannot read the file, the user must fix it by hand. Lighten does not
offer `e` then, and `lighten init` and `config` refuse the file with the
explanation below.

When Lighten cannot read the file, the Workspace's only pane says what is wrong
and how to fix it. Help's purpose line says the same:

```
Lighten can't read ~/.lighten.json
It isn't valid JSON: line 1, column 1 should start with "{" but starts with "l".

To fix it: open the file in a text editor, correct that line, then press r to check again.
To start over: rename or delete the file, then press r. Lighten then offers i to create a new one.
```

The path is the file in use, also when it comes from `--config`. For text that
is not JSON, Lighten uses plain words and gives the line and column. These
problems also get plain words, with the line but no column:

- a missing key (`target-root is missing. Add it under "lighten".`)
- a value of the wrong kind (`Line 2: relocations[0].source-path should be
  text, but it is a number.`)
- an unknown key (`Line 2: relocations[0] has an unknown setting "existing".
  Check its spelling or remove it.`)
- a bad rule value (`relocations[0].when-only-target-exists can't be
  "sometimes". Use one of: prompt, adopt-target.`)

The loader's own checks (a relative path, a blank path) keep their own words.
When a problem has no line, the text says `correct that setting`. The help
lines show only `r`, `?` and `q`. The CLI prints the same lines on stderr. It
says `run the command again` in place of `press r`, and `run lighten init` in
place of `press r … i`.

Empty states: no configuration (offer `i: Create configuration`), no
relocations (press `e` to add them), all in sync, left as is by a rule, and
blocked (say how to repair it).

## 6. Review, applying and results

**Review** shows the exact plan in a TamboUI list. Each relocation is a
heading, and its action rows are under it. This is the same layout as the apps
in Browse (see section 8, Browse). Summary: `5 planned changes · 2 delete or
replace data`. When the plan has changes, `y` confirms it. `n`/Esc/`1` cancel
and keep the Workspace state. A plan with no changes says `No changes to
apply.` It has no confirmation, and Enter/`1`/`n`/Esc go back.

```
┏Plan━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┓
┃ ✔ ~/.cache/tool-a                ┃
┃   ✔ Copy to target and check     ┃
┃❯  ⠙ Replace source with a link ⚠ ┃
┃ ─ ~/.npm (in sync)               ┃
```

- `❯` has its own column, one cell wide, in the focus color. The selected row
  is bold. A heading is the relocation's progress mark and its path, in bold.
  Its action rows follow, with their marks two cells further in. So headings
  are easy to see without `▼`, guide lines or colour. A relocation that is in
  sync is one dim heading: `─` and its path. At 80 columns, the longest label,
  `Replace source with a link ⚠`, fits beside the scrollbar.
- The user can select every row. The Details pane has the title `Details`. For
  a relocation row, it shows the path, the Decision line in the Workspace's
  words (see section 5, Workspace), and the paths: Source, Target, and Archive
  when a step archives. For an action row, it shows the action and its paths.
  The Decision line is the one-time choice that the plan was reviewed with:
  `Decision: keep target, delete source (your choice, this run only)`. If there
  is no choice, it is the saved rule for the reviewed case: `Decision: keep
  target, archive source (your configuration)`. When the review starts, Lighten
  keeps the choices with the reviewed plan. So Results still name a choice
  after the apply forgets it.
- The list takes ↑/↓, PageUp/PageDown and Home/End. Every other key goes to the
  app first. → opens Details, ← does nothing in the list, and Enter keeps its
  meaning for the screen. Relocations do not collapse. The wheel moves the
  selection one row, and clicks do nothing, as on the other lists (see section
  3, Keys and focus).

**Applying** updates the same list in place:

- Each relocation heading has its own progress mark. It shows a spinner while
  any of its actions runs, `✔` when all are done, `✖` if any failed, and `○`
  in all other cases. The marks show status, not selection (see section 4,
  Visual language). Action rows use the same glyphs.
- The selection stays where the user put it. It never follows the running
  steps.
- The header line is `Applying. Leave Lighten running until it finishes.` A
  TamboUI line gauge (thick style) and one count line follow: `3 of 8 changes
  done · 2 running · 0 failed`. Each relocation that is in sync shows as one
  row, `─ ~/.npm (in sync)`.
- Progress counts actions. Never suggest progress in bytes, or a rollback.
- `q` opens the quit dialog: **Keep running** (the default) or **Exit when it
  finishes**. Changes always run to the end, also when a step fails. Results
  are not kept after Lighten exits. After **Exit when it finishes**, two lines
  below the help say so, and `q: Quit` no longer shows.

**Results** keep the list with the final marks, the gauge and the count line.
The count line then ends with what did not run: `3 of 8 changes done · 1 failed
· 4 not run`. When the apply finishes, the selection moves once: to the first
failure, or if there is none, to the last completed action. The messages are
different for these cases:

- Lighten refused the plan before any change: `Nothing changed: the disk no
  longer matches the reviewed plan. Check again.`
- A step's check found something different from the plan: `Stopped: a step
  found something different from the plan. The steps after it did not run.
  See the failed step's details, then press r to check again.` This message
  does not guess when the disk changed.
- Any other stop. The message counts the changes made: `Stopped after 2
  changes. Check the failed and not-run steps, then check again.` With no
  changes, it is `Stopped. Nothing was changed. Check the failed step, then
  check again.`
- Success: `Done. Checked again; results are kept until you check again.`

Results stay available through `2` until `r` or exit.

A failed step's Details start with its problem in plain words, in the error
colour. The problem gives the path, what is there, what Lighten expected and
what to do. For example: `/scratch/archive/tool-b already exists as a file.
Lighten expected nothing there. Move or remove it.` Paths show home as `~`. The
sentence does not tell the user to press r, because the headline does. It is
the only text for the failure. So it keeps everything that the executor's text
had for a bug report: every path, what was expected and found, and the system's
reason (`Lighten couldn't change ~/x: no space left on device.`). When Lighten
cannot name a failure, for example an internal error, it shows the failure's
text without change.

When a completed copy left out sockets, its Details say so in place of its
text, in the same colour. The text names one socket (`Skipped
~/.local/share/zed/zed-stable.sock; programs recreate it.`) and counts more
than one (`Skipped 3 sockets; programs recreate them.`). `apply --json` keeps
the executor's text, which names all of them. When a copy finds a named pipe
or a device file, it fails with `~/x/ipc is a named pipe; Lighten can't move
it, so it threw the copy away and moved nothing. Remove it, or move this directory
yourself.`

## 7. Configuration

The draft has the same shape as the configuration file. The same loader that
the app uses checks it. `~` and `${USER}` stay as written. Lighten keeps the
settings that the screen does not show. `ignored-source-paths` changes only
through Browse's `x`, and the list does not show it.

- The left list (`Storage and relocations`) shows `Storage locations`, then
  each relocation by its source as written (`~/.m2`). `a` adds a row with the
  source root filled in, and puts focus on its Source. `d` removes the selected
  row. `b` opens Browse. Enter, → or Tab move to the fields.
- When there are no relocations, the list says how to add one. In dim text
  under `Storage locations`, it shows `No directories yet.`, then `Press b to
  pick from 59 built-in suggestions (JetBrains, pip, Cargo, Conan, …), or a to
  type one yourself.` The count is the number of directories in the built-in
  list, counted as Browse counts them. The examples are the first app of each
  of the list's first four categories. Both come from the list, never from the
  code. While a field has focus, `b` and `a` type into it, so the text reads
  `Esc, then b to pick …`.
- **First run.** A new file opens on Target root. Browse opens by itself the
  first time that focus goes from the storage locations' fields to the list
  (with Esc, or with Tab past the last field), if both roots are valid and
  there are no relocations yet. Browse then shows a two-line note above its
  Lists lines: `Pick what to move: Space adds.`, then `Rather type a path
  yourself? Press Esc, then a.` Each line fits in 80 columns. Moving between
  the storage locations' fields does not open Browse, so the user can set
  Source root and a list of their own first. Esc goes back to the list. Browse
  opens by itself only once for each time Configuration opens. An existing
  file, even one with no relocations, opens on its list and never opens Browse
  by itself.
- Right, top: the fields of the selected item, one row each. The label is in a
  column 18 cells wide, beside the value. At 80x24 a field is 32 cells wide.
  Text fields are TamboUI text inputs. Both exist and Only target are TamboUI
  `Select`s (`‹ Ask each time ›`, ←/→ change them). ↑/↓ move between fields,
  and Esc goes back to the list. When a value is longer than its field, it
  scrolls sideways during typing, to keep the cursor in view. It shows its
  start again when the field loses focus.
- Right, bottom: **Details**. It shows the help for the focused field (in a
  text field, also `Esc, then s to save.`). Then a **Resolved** section shows
  each path as the loader reads it, or why the loader cannot read it. It shows
  home as `~` and updates as the user types. Details always shows the whole
  value of a field, and it scrolls with the wheel. So a long path never pushes
  a field out of view.
- Every path must be a full path or start with `~/`. Any other value reads
  `Use a full path, or one starting with ~/`.
- Storage locations fields:
  - **Source root** (default `~`)
  - **Target root**
  - **Suggestion list**. Its placeholder is `optional; adds to built-in list`,
    which fits the 32-cell field at 80 columns. Its help is "Lighten already
    includes 59 suggestions for common tools (JetBrains, pip, Cargo, Conan, …).
    Use this field only to add a list of your own, for example one shared by
    your team. Both lists are merged; yours wins where they overlap." The count
    and examples come from the list, as in the empty list.
- Relocation fields:
  - **Source**
  - **Target**. When it is blank, Lighten derives it from the target root. A
    source outside the source root needs a target.
  - **Both exist**
  - **Only target**
  - **Archive root**. When it is blank, Lighten uses the default beside the
    source.
- **Both exist** values: Ask each time · Keep target, delete source · Keep
  target, archive source · Keep target, ask about source · Leave both as they
  are · Delete both, start empty. **Only target** values: Ask each time · Keep
  target, link source. Lighten does not write a rule that is "Ask each time" to
  the file. Delete both, start empty shows a warning in Details.
- Under the header: the file path, `existing file` or `new file`, and `N unsaved
  changes`. Each of these is one change: a storage location that is different
  from the file as opened; a relocation that is added, removed or edited; a
  path that is ignored or no longer ignored. When the user changes a field back
  to its first value, that is no change.
- `s` saves from the list or a Select. (In a text field it types.) Lighten
  checks the draft first. If the loader would refuse a field, Lighten selects
  the first such field and names it (`Not saved. Storage locations › Target
  root: …`). Lighten refuses relocations that overlap as a whole. Lighten
  creates a new file directly. Before it replaces an existing file, it asks:
  `Replace ~/.lighten.json?`, and it says that comments are not kept. If the
  file changed after it was loaded, the save refuses. The draft stays, and a
  message says so: `Not saved: the configuration file changed after
  Configuration opened it. Your changes are still here. To start again from the
  file, press q, then y, then e.` The write is atomic.
- After a save: go back to Workspace, check again, and say the next step below
  the panes. For example: `Saved. 1 relocation will change: press a to review
  and apply.` The message stays until the next key that the Workspace handles.
- Help lines never show the note of a field. So the help lines and the note
  each stay one row at 80 columns, for every focus. In a text field, the help
  lines show `F1: Help`.

## 8. Browse

The feature is the **suggestion list**: the built-in list and your list. The
screen never says "candidate" or "draft".

- There are always two **Lists** lines at the top: `Built-in list · 9
  suggestions` and `Your list · ~/team/suggestions.json · 4 suggestions · file
  updated 28 Sep`. The date is the file's modification time, read together with
  the list. When there is no list of your own, the second line says so. When
  Lighten did not use a list, its line says why (`not used: file not found`).
  `i` opens **Suggestion lists**, which has the full detail.
- When Browse opens by itself on a first run (see section 7, Configuration),
  its note is above the Lists lines until Browse closes. Its Help purpose
  always ends with `Anything missing: press Esc, then a to type it.`
- The suggestions are a TamboUI list in a panel with the title **Browse**. The
  list has three levels:
  - Each category (`JVM`, `Python`, …) is a heading. Apps that no list gives a
    category are under `Other tools`.
  - Each app is a heading under its category, two cells in.
  - The app's directories follow, one on each row, two cells further in.
    Directories that no list gives an app are under `Other directories`. This
    is a heading at the same level as the categories.

  A heading has a mark (`●` all added, `◐` some, `○` none, `−` none can be
  added, a dim `●` all move with a linked parent in the configuration) and its
  name in bold. In dim text, at the notes column, it shows how
  many of the directories under it that can be added are added (`1 of 2
  added`). When none can be added, it says the reason that they all share
  (`all managed`, `all links with problems`, `all ignored`), else `none can
  be added`. Each directory's Details give its own reason. The indents make
  headings easy to see without
  colour. A path uses at most 28 cells, and the notes column starts after it,
  for headings and rows. At 80 columns this leaves 40 cells for a note, beside
  the scrollbar:

  ```
  ┃ ◐ Python                            1 of 3 added                ┃
  ┃   ◐ uv                              1 of 2 added                ┃
  ┃❯    ● .local/share/uv                                           ┃
  ┃     ○ .local/share/uv/tools                                     ┃
  ┃   ○ pixi                            0 of 1 added                ┃
  ┃     ○ .pixi/envs                    not created yet             ┃
  ┃ − Other directories                 all links with problems     ┃
  ┃     − link-cache                    link is broken              ┃
  ```

  Categories and apps keep the order in which they first appear, with the
  built-in list first. `Other tools` and then `Other directories` come last.

  `❯` is one cell wide, and the selected row is bold. Groups do not collapse.
  The selection stays on its item: check again, `u`, `f`, adding and removing
  never move it to a different row. When the selected row is hidden, Lighten
  selects the row now at its place, and that row stays selected. Rows keep the
  place where they were first listed.
- Marks: `○` not in the configuration, `●` in it (saved before or added now),
  `−` cannot be added. Space adds or removes. Removing a row changes only the
  configuration on screen. `s` in Configuration writes it. A row that the user
  removed stays listed as `○` until Browse closes, even when no list suggests
  it. `e` on a `●` row edits it in Configuration.
- `x` on a directory ignores it (`x: Ignore`). A `●` row moves from the
  relocations to the ignored paths, as on the Workspace. Any other row is added
  to the ignored paths. On a `⊘` row, `x` stops ignoring it (`x: Stop
  ignoring`), and it shows as `○` until Browse closes. Like Space, `x` changes
  only the configuration on screen, and `s` writes it. An ignored row:
  - is `⊘`, with the note `ignored by you`
  - is never hidden
  - can't be added: Space does nothing, and Lighten does not offer it
  - is listed even when no list suggests it (under Other directories)

  Its Details start with `Ignored by you` and say to press `x` to stop ignoring
  it.
- Space on a `○` or `◐` heading (a category or an app) adds every shown
  directory under it that can be added. It adds each one as it would one by
  one, so it skips a directory that overlaps. A link that can be taken over
  counts as one that can be added, and a linked parent is taken over once.
  Space on a `●` heading removes
  them all. Space on a `−` heading does nothing. Each directory is one unsaved
  change. When Lighten skipped rows, a line says so: `Added 3. Skipped 1 that
  overlaps ~/.cache.`, `Skipped 1 that can't be added.`, `Skipped 1 you
  ignored.` A heading's `1 of 2 added` does not count ignored rows. Enter on a
  heading does nothing.
- Row notes, in plain words: `checking…`, `not created yet`, `already a link`,
  `inside ~/.cache, which is a link`, `inside ~/.cache, which Lighten
  manages`, `not a directory`, `can't read: <reason>`, `usually not needed`,
  and a link's problem (below). A note that only says that the directory is
  not there yet (`not created yet`, `checking…`) is dim. Problems are in the
  warning color, the other notes in the text color. A note names a parent with
  `~`, as every path on screen does.
- **Take over** a link that the user made. Discovery reads where each link
  points, at the row's path or at the first linked parent above it, but never
  lists what is there. A link to a real directory outside the source root can
  be taken over: the row is `○`, and `Space` (`Space: Take over`) adds a
  relocation from the link to where it points (its text against its parent,
  not the end of a chain), so the planner finds it in sync. A target that the
  roots would derive anyway is left out of the file. Nothing on disk changes,
  and `s` in Configuration writes the change.
  - Browse decides each row's state in one place, and
    the marks, notes, keys and Details all read it. Its overlap rule is the one
    Configuration uses when it adds the relocation, so a row offered as `○` is
    never refused for an overlap between other relocations.
  - A row inside a linked parent takes over the parent, even when no list
    suggests the parent. The parent is then listed under Other directories,
    and each row inside it shows a dim `●` with `inside ~/.cache, which
    Lighten manages`. `Space` on such a row takes the parent out again. Links
    inside the parent are never touched.
  - Otherwise the row is `−`, and its note says why: `link is broken`, `link
    points to another link` (a chain or a loop), `link points to a file`,
    `link target is unclear` (a relative link whose `..` passes another link,
    so the system finds another directory than its text names),
    `link points inside your home`, `can't read where the link points`, `link
    overlaps ~/.cache/pip` (the draft, compared as a save compares it), or
    `ignored by you` for a parent the user ignores. Details say it in a full
    sentence. Lighten never repairs a link.
  - While a shown link can be taken over, a line over the list says `3
    directories are links you made. Press L to take them over.` It counts
    relocations, so a linked parent counts once. `L` takes over each one, as
    `Space` would, and says `Took over 3. Left out 2 links with problems;
    Enter on one says why.` `L` is off the help lines, which are
    full at 80 columns: the line over the list names it, and Help lists it
    under Do while it applies.
- When both lists name a directory, your list wins: the row shows its app group
  and advice. When both lists name an app with different categories, your
  list's category wins. When your list names an app without a category, the app
  keeps the built-in list's category. Details shows the advice of every list,
  yours first.
- A directory is hidden only when every list that names it marks it as usually
  not needed. The hidden rows are counted (`1 usually not needed, hidden`), and
  `u` shows them. Rows that are already in the configuration, and ignored rows,
  are never hidden.
- A **count line** under the Lists lines says how many listed directories are
  found on this machine. A directory is found when the last check saw a
  directory or a link at its path, or a linked parent above it. A link counts,
  because a directory that Lighten moved is a link. A row inside a linked
  parent counts, because it moves with the parent. The line reads `Checking this machine…` until every
  row is checked. Then it reads `12 found on this machine`, with the hidden
  count after ` · ` on the same line. This keeps the list's rows at 80x24.
- `f` (`f: Found only`, `f: Show all`) shows only the directories that are
  found, and the directories in the configuration. As with `u`, those are never
  hidden, and their `●` and note make them different. The count line then adds
  `, plus 2 in your configuration` when some of those rows are not found. If
  not, it adds `, only these shown`. The hidden count then counts only found
  directories. A heading with nothing shown under it is not listed. A heading's
  `1 of 2 added` counts only the directories shown under it. Found rows keep
  their list order: Lighten does not sort them first. `f` is on the navigation
  help line, because the other line is full at 80 columns. Help lists `f` under
  Do. Lighten offers `f` when something is found, and always while `f` is on,
  so the user can turn it off. When nothing is found and `f` is on, the list
  reads `None found on this machine. Press f to show every suggestion.`
- While `f` is on, Space on a heading adds only the found directories under
  it. Help says so (`Add every directory under it found on this machine that
  can be added`). The next line names what Space skipped: `Added 2. Skipped 3
  not found on this machine; f shows all.` Removing a group does not change:
  Browse shows every directory under it that is in the configuration. Browse
  opens with `f` off, also on a first run.
- `r` is **Check again**. It reads the lists again, and rows read `checking…`
  until they are checked. It never changes the configuration.
- Discovery never stops the screen from responding, and it never lists the
  contents of a directory. Size reads `not estimated`, and ownership reads `not
  evaluated`, until those features exist.
- Enter on a directory opens **Details**. It shows whether the directory is in
  the configuration, its state, its full path, and how it overlaps other
  suggestions (`Also suggested, inside it: …`). Then **Suggested by** shows
  each list's group, advice and reason, or `No list suggests it.` A list's
  caution follows its reason on its own line, `⚠ Caution: …`, in `warn`. The
  sign keeps the caution clear without color.
- The mouse wheel over the list moves its selection one row. Over Details or
  Suggestion lists, it scrolls them. Clicks do nothing.

## 9. Wording

| Thing | Words on screen |
| --- | --- |
| Policy | rule |
| `prompt` or missing | Ask each time |
| Workspace badges | `[Choose]` needs a choice, `[Blocked]`, `[Can't read]`, `[Warning]`, `[Move]`, `[Keep target]`, `[Link]`, `[Archive]`, `[Delete]`, `[Left as is]`, `[In sync]`, and `[Ignored]` for an ignored source |
| Actions | Create parent of target · Create parent of source · Create archive directory · Create target directory · Copy to target and check · Replace source with a link · Link source to target · Fix source link · Archive source · Delete directory · Already in sync · Leave as is |

All screen text is in one TUI wording file.

## 10. Verification

Every UI change includes rendered captures at 80x24 and 120x30. It also checks
that resizing works in both directions. Tests drive a real session over
temporary configuration files. They check rendered screens and key handling.
Passing tests do not mean that the UX is accepted. Changes that the user can see
need a walkthrough by the user.
