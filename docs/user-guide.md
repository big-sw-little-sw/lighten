# Lighten

Lighten frees space in your home directory. It moves large directories, such
as caches, to other storage. At the old path it leaves a link, so programs
still find them.

Lighten always shows you its plan first. Nothing on disk changes until you
press y.

## What it does

- **Moves directories to storage.** It copies a directory to storage and
  checks the copy. Then it replaces the original with a link.
- **Prepares directories that do not exist yet.** It creates them in storage
  and links to them, so they never fill your home directory.
- **Handles copies already in storage.** When storage already has the
  directory, a rule decides what happens, or Lighten asks you.
- **Suggests what to move.** Browse lists directories that are usually safe
  and worth moving, such as tool caches.
- **Shows every step before it runs.** You review the whole plan and confirm
  it with `y`.
- **Checks again at any time.** Press `r`. Lighten looks at the disk again
  and makes a new plan.
- **Works in scripts.** `status`, `plan` and `apply` can print JSON.

## How to use it

Press `?` on any screen to see what the screen is for and its keys. In a
text field, press `F1`.

### Free space on this machine

```
Configure → Workspace → Review → Apply → Results
                ↑                           │
                └────── r: check again ─────┘
```

1. **Configure.** Run `lighten`. The first time, there is no configuration
   file yet.
   - Press `i` to open **Configuration**.
   - In **Target root**, type where storage is, such as `/data/me`. Then
     press `Esc`. **Browse** opens with Lighten's built-in suggestions. These
     are directories of common tools such as Maven, Gradle, npm, pip, uv and
     Cargo.
   - Press `Space` on each directory that you want to move.
   - To add a directory that is not in the list, press `Esc` to go back to
     Configuration's list. Then press `a` and type the directory. A path is
     full, or starts with `~/`.
   - Press `s` to save. Saving writes the configuration file and nothing
     else.

   Later you can add a list of your own in **Suggestion list**, for example
   one that your team shares. Browse merges it with the built-in list.
2. **Check the plan on the Workspace (`1`).** Each directory that you added
   is a *relocation*. The Workspace shows what is there now and what Lighten
   will do, such as `[Move]` or `[In sync]`.
   - A relocation marked `[Choose]` needs your choice. Select it, press
     `Tab`, pick a choice and press `Enter`. The chosen one is marked `●` and
     the others `○`. When you pick another, it replaces the first.
   - A choice is for the next apply only. To always do this for that
     relocation, press `s` to save the choice as its rule.
   - A relocation marked `[Blocked]` cannot be done as things are. For
     example, a file is where its archive directory must go. Its Details say
     what is in the way. Fix that, then press `r`. If Details say that you
     can, pick a choice below that does not need that directory.
3. **Review (`2`).** Press `a` to see every step that Lighten will take.
   Each step is under the relocation it belongs to. Select a relocation to
   see its decision and paths. Select a step to see what it does. Nothing
   has changed yet. Press `y` to apply, or `n` to go back.
4. **Apply.** Lighten makes the changes and shows each step as it runs.
   Let it run until it finishes. If you press `q`, Lighten finishes the
   changes first, then exits.
5. **Results (`2`).** Each step shows whether it worked. Press `r` to check
   again. The Workspace then shows each relocation as it is now, normally
   `[In sync]`.

   If a step finds something different from the plan, Lighten stops there.
   The steps after it do not run. The line at the top says how many changes
   Lighten made before it stopped, or `Stopped. Nothing was changed.`
   - Select the failed step. Its Details say what is there, what Lighten
     expected and what to do.
   - Do that, then press `r` to check again.
   - To report a bug, send a screenshot of these Details. That is enough.

### Change the configuration later

Press `e` on the Workspace to open Configuration. Change it, then press `s`.
Lighten checks again and shows the new plan.

The Workspace lists relocations in this order:

1. Those that need you: `[Choose]`, `[Blocked]` and `[Can't read]`.
2. `[Warning]`.
3. Those with changes, such as `[Move]`.
4. `[Left as is]`.
5. `[In sync]`.

Within each group, relocations keep the order of your configuration.

- Select **Storage locations** or a relocation in the list on the left.
  Press `Enter` to change its fields, and `Esc` to go back to the list. In a
  field, every letter types, so press `Esc` before `s`.
- Press `b` to open Browse and pick from the suggestions. Press `a` to add a
  relocation that you type. Press `d` to remove the selected one. Nothing
  changes in the file until you press `s`.
- Saving asks first, because it replaces the whole file. Saving does not
  keep comments in the file.
- The file can change after you open Configuration, for example when you
  edit it by hand. Then Lighten does not replace it, and your changes stay
  on screen. To start again from the file, press `q`, then `y`, then `e`.

### Leave a directory alone

Another tool can manage a directory, for example Stow or a sync app. Then
tell Lighten to ignore that directory. Select its relocation on the
Workspace and press `x`. Lighten asks first. Then it moves the directory
from your relocations to the ignored paths in the configuration file.
Nothing on disk changes.

The directory can already be linked to storage. Then the link and the files
in storage stay as they are. The dialog tells you how to undo the move by
hand.

The Workspace lists ignored directories at the end, under
`i: show 2 ignored`. Press `i` to show them, and press it again to hide
them. Each one is marked `[Ignored]`. To manage one again, select it and
press `x`. Lighten stops ignoring it, and you can add it again in
Configuration.

Browse marks an ignored directory `⊘`, with the note `ignored by you`.
Browse always shows it, and you cannot add it.

- Press `x` on a directory in Browse to ignore it.
- Press `x` on a `⊘` directory to stop ignoring it.
- Then press `s` in Configuration to save.

`Space` on a category or app does not add the ignored directories under it,
and Browse says so.

A directory cannot be both a relocation and ignored. If the configuration
file lists a directory as both, Lighten tells you which two settings to fix.

`lighten config` opens Configuration directly. The configuration file is
`~/.lighten.json`, or the file that you give with `--config`. You can also
edit the file by hand. Then press `r` in Lighten to check again. The
**Configuration file** section under Reference lists every setting.

Run Lighten again at any time, for example after you add a directory.
Lighten changes only what is not in sync yet.

### If Lighten can't read your configuration

If the file has a mistake, the Workspace shows `Lighten can't read`, the
file's name, and what is wrong. For example:

```
target-root is missing. Add it under "lighten".
Line 2: relocations[0].source-path should be text, but it is a number.
lighten.target-root: Use a full path, or one starting with ~/
```

- **To fix it:** open the file in a text editor. Correct the line or setting
  that the message names. Then press `r` to check again.
- **To start over:** rename or delete the file, then press `r`. Lighten then
  offers `i` to create a new file.

Configuration cannot open a file that Lighten cannot read. So Lighten does
not offer `e` until you fix the file. `lighten config` and the `--json`
commands print the same explanation and exit with code 1.

## Suggestion lists

Browse is inside Configuration. It suggests directories to move. The
suggestions come from two suggestion lists:

- **The built-in list.** It comes with Lighten. It names directories that
  are usually large and safe to move: package caches, build caches,
  toolchains and SDKs, and the versions that version managers install. It
  covers Maven, Gradle, npm, pip, uv, pyenv, Cargo, Conan, vcpkg, ccache,
  Bazel, Go, the Android SDK, mise, VS Code, Zed and others.
- **Your list** (optional). A file that you write, for example one on a
  shared drive that your whole team uses. Browse always shows the built-in
  list too.

Press `b` in Configuration's list to open Browse. Its first two lines show
the lists:

- The built-in list, with its number of suggestions.
- Your list, with its location, its number of suggestions and the day its
  file last changed. If Lighten cannot use your list, this line says why.
  Press `i` for the full detail.

Below these lines, Browse lists the suggestions under their category, such
as Editors, Python or C and C++. Under each category is the name of each
app. Apps with no category are under "Other tools". Directories with no app
are under "Other directories". These two groups are at the end.

Each directory has a mark:

- `●` it is in your configuration, saved earlier or added now.
- A dim `●` it is inside a linked parent that is in your configuration.
  It moves with the parent.
- `○` it is not. Press `Space` to add it.
- `−` you cannot add it, for example because it is a broken link. Its note
  says why.
- `⊘` you ignore it, so Lighten leaves it alone. Press `x` to stop ignoring
  it.

`Space` on a `●` row removes it again. A directory that you remove stays in
the list until you leave Browse, so `Space` can add it back. Adding and
removing change only what Configuration shows. The file changes when you
press `s`. Press `e` on a `●` row to change its target or rules.

Each category's and app's name has a mark too:

- `●` all the directories under it are added.
- `◐` some are added.
- `○` none are added.
- `−` none can be added.
- A dim `●` all of them are inside a linked parent that is in your
  configuration.

Beside the name, Browse counts them, such as `1 of 2 added`. A link that
Lighten can take over counts as one that can be added. When none can be
added, it says why: `all managed`, `all links with problems`, `all ignored`,
or `none can be added` when the reasons differ. Press `Enter` on a directory
to see its own reason. `Space` on the
name adds all the directories under it that are shown and can be added. So
`Space` on "Python" adds the directories of every Python tool at once. On a
`●` name, `Space` removes them all. If Browse could not add some, it says
so, for example `Added 3. Skipped 1 that overlaps ~/.cache.`

A list can mark a directory **usually not needed**. Browse hides a directory
when every list that names it says so. Browse counts what it hid. Press `u`
to show them. Browse never hides a directory that is in your configuration
or that you ignore.

Under the lists, a line such as `12 found on this machine` counts the
suggested directories that exist on this machine. A directory inside a
linked parent counts too. Press `f` to show only
those, and press `f` again to show all. Browse always shows the directories
in your configuration. While `f` is on, `Space` on a category's or app's
name adds only the found directories under it.

Both lists can name the same directory. Then Browse shows it once, in your
list's group and with your list's advice. Both lists can also give the same
app different categories. Then Browse uses your list's category. To see
which lists suggest a directory, select it and press `Enter`. Browse shows
what each list says, including any caution, marked `⚠ Caution`.

Some tools have clean commands, such as `sdk flush` or `deno clean`. These
commands remove the link to a moved directory. The tool then creates a new
directory in its place, and Lighten asks which directory to keep. The built-in
list gives a caution about these tools.

### Take over links you made

You can move a directory to storage yourself and put a link in its place.
Browse shows such a directory with the note `already a link`. Lighten can
take over the link: it adds a relocation from the link to the directory
that it leads to. Nothing on disk changes. After you save, the Workspace
shows the relocation as `[In sync]`.

- Press `Space` on a `○` row with the note `already a link` to take it over.
- Press `Space` again to take it out.
- Press `s` in Configuration to save.

A directory can be inside a parent directory that is a link. An example is
`~/.cache/pip` when `~/.cache` is a link. Its note is `inside ~/.cache,
which is a link`. `Space` on it takes over the parent, `~/.cache`, also when
no list suggests the parent. Then each directory inside `~/.cache` shows a
dim `●` and the note `inside ~/.cache, which Lighten manages`. Lighten does
not add these directories on their own. Links inside `~/.cache` stay as they
are.

When there are links to take over, a line above the list counts them. For
example: `3 directories are links you made. Press L to take them over.`
Press `L` to take over all the links that Browse shows. `L` takes over each
linked parent once. Then Browse says how many it took over and how many
links it left out. Press `Enter` on a `−` link to see why.

A link can lead to its directory through other links. For example,
`~/.cache/JetBrains` links to `~/.local-heavy/cache/JetBrains`, and
`~/.local-heavy` links to `/local/disk`. Lighten follows each link to the
directory at the end and makes that directory the target:
`/local/disk/cache/JetBrains`. The links stay as you wrote them. Details
show both, such as `Link: ~/.cache/JetBrains → /local/disk/cache/JetBrains
(written as ../.local-heavy/cache/JetBrains)`. If a link on the way later
leads somewhere else, the Workspace shows the relocation as `[Blocked]`.

Lighten takes over only a link that leads to a directory outside your home.
If it cannot, the row is `−` and its note says why:

- `link is broken`: nothing is where the link leads, or the links go in a
  circle.
- `link points to a file`: the target must be a directory.
- `link points inside your home`: moving it frees no space.
- `link overlaps ~/.cache/pip`: the relocation would overlap one in your
  configuration.

Lighten does not repair a broken link. Fix the link yourself, then press `r`
to check again.

### Write your own list

A suggestion list is a JSON file. For example:

```json
{
  // Suggestions for Lighten's Browse.
  "apps": [
    {
      "name": "Hugging Face",
      "category": "Machine learning",
      "directories": [
        {"path": ".cache/huggingface", "advice": "consider",
         "reason": "Downloaded models and datasets",
         "caution": "Stop running notebooks before moving it"}
      ]
    },
    {
      "name": "Docker",
      "directories": [
        {"path": ".docker/buildx", "advice": "usually-unnecessary",
         "reason": "Small on our machines"}
      ]
    }
  ],
  "directories": [
    {"path": "scratch", "reason": "Scratch data from the cluster"}
  ]
}
```

- `apps` holds groups. Each group has a `name` and its `directories`.
- The top-level `directories` holds suggestions with no group. Browse shows
  them under "Other directories".
- A file needs `apps`, top-level `directories`, or both.
- `category` is optional on an app. It is the heading that Browse shows the
  app under, such as `Python`. An app with no category is under "Other
  tools". You can move a built-in app, such as Gradle, under another
  heading. To do this, name the app in your list with the category that you
  want and at least one of its directories.
- `path` is relative to your home directory. Write `.cache/huggingface`,
  not `~/.cache/huggingface` or `/home/me/.cache/huggingface`. A `path`
  cannot use `..`, variables such as `$USER`, or wildcards.
- `advice` is optional: `consider` or `usually-unnecessary`.
- `reason` is optional. Browse shows it with the suggestion.
- `caution` is optional. It is a warning about moving the directory, such as
  a command that undoes the move. Browse shows it after the reason, marked
  `⚠ Caution`.
- Comments (`//` and `/* */`) and trailing commas are allowed. Any other key
  is an error.
- The file can be up to 1 MiB and list up to 10,000 directories.

One error rejects the whole file. Browse then says so on your list's line,
and uses only the built-in list. Press `i` for the detail. Lighten waits at
most 5 seconds for the file, so a slow or missing drive never blocks you.
Configuration saves in both cases.

To use a list, enter its path in Configuration's **Suggestion list** field.
Or add it to the configuration file:

```json
"suggestion-list": "/net/team/lighten/suggestions.json"
```

The path must be full or start with `~/`. Each person keeps their own
configuration file. Only the list is shared. After someone changes the
list, press `r` in Browse to check again.

## Words to know

**Relocation.** One directory that Lighten manages. Its **source** is where
programs look for it, in your home directory. Its **target** is where its
contents are, in storage. After a move, the source is a link to the target.

**In sync.** The source already links to the target. There is nothing to do.

**Ignored.** A directory that you told Lighten to leave alone. Lighten plans
nothing for it. The configuration file lists it under
`ignored-source-paths`.

**Take over.** Lighten adds a link that you made to the configuration as it
is. Nothing on disk changes.

**Rule or one-time choice.** A rule is saved in the configuration file and
decides every time. A one-time choice decides for one relocation, for the
next apply only. Lighten forgets it when you check again or apply. Press `s`
to save a choice as the rule.

**Archive or delete.** Archive moves the source's contents into an archive
directory, so you can move them back. Delete removes them permanently.

**Check again.** Lighten looks at the disk again and makes a new plan. Do it
after you change files or the configuration outside Lighten.

## Undo

There is no undo command. Nothing changes before you press `y`. After that,
you can reverse a change by hand:

- **Moved or linked:** remove the link, then move the target back. For
  example, run `rm ~/.cache/uv` to remove only the link. Then run
  `mv /local/home/me/.cache/uv ~/.cache/uv` to move the target back.
- **Archived:** remove the link, then move the archive back. The target
  keeps its own contents. For example, run
  `mv ~/.cache/.lighten-archive/uv ~/.cache/uv` to move the archive back.
- **Deleted:** Lighten cannot recover it. Restore it from a backup.

First remove the relocation from the configuration. If you do not, Lighten
plans to move it again. To remove it, press `e` and select it. Then press
`d` and `s`.

## Reference

### Configuration file

Lighten reads its settings from `~/.lighten.json`, or from the file that you
give with `--config`. Configuration writes this file for you. You can also
write or change it by hand. Then press `r` to check again. For example:

```json
{
  "lighten": {
    // Where storage is. The only setting you must give.
    "target-root": "/local/home/${USER}",
    "suggestion-list": "/net/team/lighten/suggestions.json",
    "relocations": [
      // No target-path: it goes to /local/home/<you>/.m2.
      {"source-path": "~/.m2"},
      {
        "source-path": "~/.cache/uv",
        "target-path": "/scratch/${USER}/uv",
        "when-source-and-target-directories-exist": "adopt",
        "when-adopting-target": "archive-source",
        "archive-root": "~/old",
      },
    ],
    "ignored-source-paths": ["~/.cache/pip"],
  }
}
```

All settings go under `"lighten"`:

- `target-root` (required): where storage is, such as a larger disk.
- `source-root` (default `~`): the directory that your sources are usually in.
  A relocation without a `target-path` keeps its place under this directory,
  inside `target-root`. So `~/.m2` goes to `<target-root>/.m2`. A source
  outside `source-root` needs a `target-path`. Browse also looks for
  suggestions under this directory.
- `staging-root` (default: a `.lighten-staging` directory beside each
  target): where Lighten copies a directory before it puts the copy in
  place. It must be inside `target-root`, on the same disk as the targets.
  If a relocation to move has its target on another disk, it is `[Blocked]`
  until you fix this. Configuration does not show this setting, but keeps it
  when it saves.
- `suggestion-list` (default: none): your own suggestion list. **Write your
  own list** tells you how to make one. Empty text means none.
- `relocations` (default: none): the directories that Lighten manages.
  Lighten never touches a directory that is not in this list.
- `ignored-source-paths` (default: none): directories that Lighten leaves
  alone. **Leave a directory alone** tells you more. A directory cannot be
  both here and a relocation's `source-path`.

Each relocation has these settings:

- `source-path` (required): the directory to move.
- `target-path` (default: from `source-root` and `target-root`, as
  above): where its contents go.
- `when-only-target-exists`: `prompt` (the default) or `adopt-target`.
- `when-source-and-target-directories-exist`: `prompt` (the default),
  `adopt`, `leave-unchanged` or `discard`.
- `when-adopting-target`: `prompt` (the default), `discard-source` or
  `archive-source`. It decides what happens to the source after `adopt`.
- `archive-root` (default: a `.lighten-archive` directory beside the
  source): where `archive-source` moves the source. It must be on the same
  disk as the source. The source keeps its name there. If that name is
  taken, Lighten adds a short code to it, such as `uv-3f9c2b1d`.

`prompt` is Ask each time. **What each rule does on disk** below tells you
what the other values do. When Configuration saves, it leaves out each
setting that has its default value.

Paths:

- Every path, including `suggestion-list`, is full, such as `/data/me`. Or
  it starts with `~/`, or it is `~`. Lighten refuses a path like `data/me`.
  The meaning of such a path depends on the directory that you run Lighten in.
- `~` is the home directory of your account, not the `HOME` environment
  variable.
- `${USER}` is your user name. It is the `USER` environment variable, or
  your account name when `USER` is not set. Lighten does not fill in other
  variables, such as `$HOME`.
- Configuration keeps `~/` and `${USER}` as you typed them.

The file is JSON, with two additions:

- Comments (`//` and `/* */`). Configuration does not keep comments when it
  saves.
- A comma after the last item of a list or object.

If a setting appears twice in the same object, the last one counts.

Lighten checks the whole file each time it reads it. It cannot read a file
that has one of these problems:

- text that is not JSON, or a missing `target-root` or `source-path`;
- a setting that it does not know, such as a misspelled one;
- a value of the wrong kind, such as a number instead of text, or a rule
  value that is not in the lists above;
- a path that is not full and does not start with `~/`;
- a `staging-root` outside `target-root`, or a source outside
  `source-root` with no `target-path`;
- a directory that is both a relocation and ignored.

Lighten then shows what is wrong and where. **If Lighten can't read your
configuration** tells you more.

Two relocations can overlap: one is inside the other, or both have the same
target. Lighten still reads the file, but marks both `[Blocked]`. The
Details of each one name the other, such as `~/a contains ~/a/b, which is
also a relocation`. Lighten plans the other relocations as usual, but
applies nothing until you fix the overlap. Two paths that are the same
place through a link also overlap, such as `/home` that links to
`/var/home`. A relocation whose source and target overlap is `[Blocked]` on
its own.

### What each rule does on disk

Lighten looks at the source and the target. Then:

- **Only the source exists:** Lighten copies the source to the target and
  checks the copy. Then it replaces the source with a link. The Workspace
  marks it `[Move]`. For sockets, named pipes and device files in the
  source, see **Special files** below.
- **Neither exists:** Lighten creates an empty target and links the source
  to it (`[Link]`).
- **The source already links to the target:** Lighten does nothing
  (`[In sync]`). This is also true when the link leads to the target
  through other links. Lighten never changes such a link.
- **The source links somewhere else:** Lighten does nothing (`[Blocked]`).
  The link can belong to another program, so Lighten never replaces it.
  Details say where it points, such as `~/.cache/tool links to /data/tool,
  not to /scratch/local/tool. Remove the link, or set its target to where
  it points.` Then press `r`. The link is blocked even when what it points to
  does not exist now, for example on a disk that is not mounted. Details
  then say so and end with `Mount the disk or fix the link, then check
  again.`
- **Only the target exists:** the **Only target** rule decides.
- **Both exist:** the **Both exist** rule decides.

#### When only the target exists

The configuration value is `when-only-target-exists`.

- **Ask each time** (`prompt`): nothing happens until you choose
  (`[Choose]`).
- **Keep target, link source** (`adopt-target`): Lighten links the source
  to the existing target (`[Link]`).

#### When source and target both exist

The configuration value is `when-source-and-target-directories-exist`.
When Lighten keeps the target, `when-adopting-target` decides what happens
to the source.

- **Ask each time** (`prompt`): nothing happens until you choose
  (`[Choose]`).
- **Keep target, ask about source** (`adopt`, `prompt`): nothing happens
  until you choose (`[Choose]`). You get the same choices as for **Ask each
  time**.
- **Keep target, delete source** (`adopt`, `discard-source`): Lighten
  deletes the source and replaces it with a link to the target
  (`[Keep target]`). The source's contents are permanently gone.
- **Keep target, archive source** (`adopt`, `archive-source`): Lighten
  moves the source into the archive directory. Then it links the source to the
  target (`[Archive]`). The archive directory is `.lighten-archive` beside the
  source, unless `archive-root` gives another directory. So `~/.cache/uv` goes
  to `~/.cache/.lighten-archive/uv`.
- **Leave both as they are** (`leave-unchanged`): Lighten does nothing
  (`[Left as is]`).
- **Delete both, start empty** (`discard`): Lighten deletes both. Then it
  creates an empty target and links the source to it (`[Delete]`). The
  contents of both are permanently gone.

A one-time choice gives the same results, for one relocation and the next
apply only.

#### Special files

A directory to move can hold files that are not ordinary files, directories or
links. Lighten cannot copy them:

- **Sockets** let a running program listen for other programs. Lighten
  leaves sockets out of the copy, and the program makes a new one when it
  starts. A program that was killed often leaves its socket behind. In
  Results, the copy step names the socket, for example:
  `Skipped ~/.local/share/zed/zed-stable.sock; programs recreate it.`
  If there are several, the step counts them. When Lighten replaces the
  source with a link, the socket is gone from both places. So close the
  program before you apply.
- **Named pipes and device files** stop the move. The plan does not look
  for them. To find them, Lighten would have to read every file of every
  directory to move each time it checks. When the copy finds one, Lighten
  deletes the copy and nothing moves. The copy step names the file, such as
  `~/.cache/tool/ipc is a named pipe; Lighten can't move it, so it threw the
  copy away and moved nothing. Remove it, or move this directory yourself.`
  Then press `r`.

### Scripting

Three commands never ask a question. Each one prints one line of JSON:

- `lighten status --json`: what is on disk now for each relocation. If there
  is no configuration file at the default path, `configured` is `false` and
  `relocations` is empty.
- `lighten plan --json`: the steps that Lighten would take for each
  relocation, any choices still needed (`conflicts`) and any warnings. It
  changes nothing.
- `lighten apply --json --yes`: makes that plan. If nothing is blocked and
  no choice is needed, it applies the plan and prints how each step went.
  Otherwise it prints the plan and changes nothing. The `message` of a
  failed step is Lighten's exact error, not the sentence that Results show.

In `plan --json`, a relocation that needs a choice has a `conflict`. Its
`resolutions` are the choices that Details offers for it, by these names:

- `adopt-target`: **Keep target, link source**, when only the target
  exists.
- `adopt-and-discard-source`: **Keep target, delete source**, when both
  exist.
- `adopt-and-archive-source`: **Keep target, archive source**.
- `leave-unchanged`: **Leave both as they are**.
- `discard-both`: **Delete both, start empty**.

These are choice names, not configuration values. One choice can set two
rules. For example, `adopt-and-discard-source` is `adopt` with
`discard-source`.

To make a choice without the screens, save it as the rule. **What each rule
does on disk** tells you how.

A problem that no choice fixes is not a conflict. An example is a source
that links somewhere else. Its `actions` hold one step of `type` `blocked`.
The `reason` of that step says what is wrong and how to fix it. Fix it, then
plan again.

Screens and messages show your home directory as `~`. JSON output shows
every path in full.

`apply --json` needs `--yes`, which confirms the plan that the command
makes. Without `--json`, `status`, `plan` and `apply` open Lighten as
`lighten` does. They start on the Workspace, with or without `--yes`.

Every response is a JSON object that starts with `"schema": 1`. The number
changes when the output changes in a way that could break a script.

Exit codes:

- `0`: the command worked. `plan --json` exits 0 also when a choice is
  needed or a step is blocked. Read `conflicts` and `blocked`.
- `1`: one of these:
  - The configuration file has a problem. Lighten writes what is wrong and
    how to fix it on stderr, and nothing on stdout.
  - `apply` was blocked, needed a choice or had a step that failed. Lighten
    writes JSON on stdout.
- `2`: the command line is wrong, such as an unknown option or
  `apply --json` without `--yes`. Lighten writes a message on stderr.
- `70`: a bug in Lighten. Lighten writes one line on stderr. Please report
  it.

`lighten update` exits `0` when it updated Lighten or found nothing to
update. It exits `1` when it could not update, and writes a message on
stderr. `lighten update --check` exits `0` whether or not there is a newer
release.

### Update Lighten

`lighten update` updates Lighten to the latest release. It runs the install
script of that release on the directory that holds your `lighten`. The script
downloads the new `lighten` and checks it against the `SHA256SUMS` file of
the release. Only then does it put the new `lighten` in place. So a failed
download leaves your `lighten` as it was. The script needs `curl` or
`wget`.

- `lighten update --check` shows the installed and the latest version. It
  changes nothing.
- `lighten update --version 1.2.3` installs that release, even an older
  one. Without `--version`, it never installs an older one.
- Lighten uses the network only while `lighten update` runs.
- If you installed Lighten with mise, update it with mise instead.
  `lighten update` tells you the command:
  `mise upgrade github:big-sw-little-sw/lighten`
- An install by eget or ubi updates in the same way as an install by the
  install script. So `lighten update` works for those too.
- You may not have permission to write to the directory that holds `lighten`,
  such as `/usr/local/bin`. Then `lighten update` changes nothing. It shows
  how to update `lighten` as a user who has permission.

### More help

- This guide online:
  https://github.com/big-sw-little-sw/lighten/blob/main/docs/user-guide.md
- `lighten --help` lists the commands and options.
- `lighten apply --help` shows the options of one command, here `apply`.
- `lighten guide` prints this guide, for example to read with
  `lighten guide | less`.
- To select and copy text while Lighten runs, hold Shift as you drag
  (WezTerm, Ghostty) or Option (iTerm2). Lighten uses the mouse wheel to
  scroll.
