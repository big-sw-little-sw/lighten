# Lighten Decisions

This file lists the rules Lighten follows today and the reason for each, grouped by theme. Each rule gives the decision and a one-line reason. It lists open omissions as `[skipped: X, add when Y]`, and it links the issues and pull requests behind the rule. The dates in parentheses record when a rule was decided. Implementation details, measurements and rejected alternatives are in those issues and pull requests and in git history. The [archive](#archive) lists rules that were replaced. [`AGENTS.md`](../AGENTS.md) tells how decisions are made.

## Product and safety

### One library core, two thin adapters

One binary holds a workflow and reconciliation core that does not know how its results are shown. A full-screen TUI for people and `--json` commands for automation sit on top of it. Lighten uses no dependency injection, classpath scanning, plugin framework or application framework (Spring, Quarkus). Why: a filesystem tool needs fast startup and plain tests, and each layer on the safety-critical path adds risk. See [`architecture.md`](architecture.md). (2026-08-29, 2026-09-07, 2026-09-12)

### Apply exactly the reviewed plan

The TUI keeps the plan that Review shows. Before any step runs, preflight checks the states that the plan expects. If the disk has changed, the plan is stale and the user must check again. After `y`, Lighten never makes a new plan and runs it instead. Why: the user confirmed those steps, not other steps. (2026-09-12)

### Directories only, no ownership registry

Lighten relocates directories only. A source that is a file is blocked. Lighten keeps no record of what it did. It recognizes a correct link from the disk. To keep a target or replace anything else, it needs a rule or a one-time choice. Why: with no record, there is no recovery, stale data or lifecycle state to get wrong. (2026-09-08)

A source link that points somewhere other than its target is blocked and never replaced. No rule or choice offers to replace it, because the link can belong to another tool. The reason names where the link points and gives both fixes: remove the link, or set its target to where it points. These words are correct when the user makes the fix in Configuration and when the user edits the file. `plan --json` shows this case as a `blocked` action with that reason, not as a conflict. Why: this case was a conflict before, and that conflict offered no choice. (#223)

A broken source link that points somewhere other than its target is blocked in the same way, because a link to a disk that is not mounted looks broken. Its reason adds that what the link points to does not exist now. A broken link that points to the target keeps its own handling. Why: Lighten replaced such a link without a rule or a choice. (#237)

- `[skipped: naming which tool owns the link, add with #5]`

### Directory permissions are kept or the move is refused

Each published directory keeps the nine POSIX permission bits of its source. If the target filesystem cannot store them, Lighten refuses to publish. Lighten does not keep ownership, ACLs, timestamps, extended attributes or special bits. Why: users often move home directories to shared storage, and a wider mode than `0700` exposes private data there. (2026-09-30)

### Existing ancestors may be symlinks; overlap compares real paths

An existing ancestor can be a link to a directory (`/home -> /var/home`). But each directory that Lighten creates must be real, and so must the source, the target and the staging root. Why: Fedora Atomic and macOS have linked ancestors. (#128)

- `[skipped: real-path check of an archive path against its own relocation, add when an archive root reached through a symlink is reported]`
- `[skipped: re-checking aliased overlap in preflight, add when ancestor links are seen to change between review and apply]`
- `[skipped: removing toRealPath() from cli and application test fixtures, add when those tests next change]`
- `[skipped: rewriting ensureDirectories's walk without mutable locals, add when it changes for another reason]`

### Overlap blocks only the relocations involved

Two relocations overlap when one is inside the other or both have the same target, as written or through a link. Lighten blocks each of them, with a reason that names the other path. A relocation whose source and target overlap blocks only itself. Lighten plans the other relocations, but any block keeps Review closed. Inspection records the real spellings of paths, and the planner compares them without doing I/O. A save still refuses overlap as written. Why: one mistake must not hide the whole plan. Also, I/O in the planner could wait on a slow mount after each choice. (#128, #207)

- `[skipped: naming every overlapping relocation in one reason, add when users configure three or more that overlap]`

### One staging operation per target

A move copies the directory into `operation-<sha256 of the target's real spelling>` in the staging root. It holds a lock on `operation-<same>.lock` and does not wait for that lock. It then verifies the copy and publishes it with one atomic rename. If this process or another process is already staging the target, that is an environment failure. Old files at that name are removed the next time Lighten stages the target. Why: different targets never touch each other's files, so relocations that share a staging root can run at the same time. (#130)

- `[skipped: sweeping other targets' leftovers, add when abandoned staging copies are reported]`
- `[skipped: deleting per-target lock files, add when users object; safe deletion is defeated by inode reuse]`

### A source is replaced by its link in atomic steps

Lighten renames the source to `.lighten-replaced-<name>-<sha256 of the target's absolute normalized path>`. It puts the link in its place and then deletes the renamed tree. Lighten recognizes a renamed tree only while the source links to that target, and the next plan deletes it. Why: a crash never leaves a partial source that a saved rule could treat as "both exist". (#132)

- `[skipped: finishing an interrupted replacement while the source is absent, add when users ask why an only-target conflict follows a crash]`

### A crash between publishing and setting the source aside is left as is

This crash leaves two whole directories, and the next plan reports "both exist". Why: the user decided that recovery would depend on fragile naming conventions. This is the crash case between publishing the target and setting the source aside, which is left as is.

- `[skipped: crash recovery between copying and linking, add when users report "both exist" after an interrupted apply]`

### The archive destination is the source's name under the archive root

An archive moves a source to `<archive root>/<source name>`. If that name is already used on disk or by another relocation, the name is `<source name>-<8 hex digits>`, from the SHA-256 of the source's real spelling. The default archive root is `.lighten-archive` beside the source. Why: the name is easy to read in the usual case, and the same state always gives the same path. (#142)

- `[skipped: a counter or further suffix when the suffixed name is also taken, add when users hit it]`

### The planner blocks a directory that is not a directory

Inspection examines each directory that a step can create or use. The planner blocks a relocation when its steps need a path that is a file, a link or unreadable. The staging root must be a real directory. The planner also blocks a move when its staging root is on a different filesystem from its target, and the reason names the `staging-root` setting. When a choice avoids that directory, Details offers it. Why: before this rule, the apply stopped halfway and blamed a change made before `y`. (#163, #207)

- `[skipped: a plan-time check that the staging root and target support POSIX permissions, add when a user's apply stops on it]`
- `[skipped: preflight re-checking these directories between review and y, add when a directory breaking in that window is reported]`
- `[skipped: a stricter check when a configured staging root is also a source, target or archive parent, add when someone configures one that way]`

### The copy skips sockets and stops on named pipes and device files

The copy and its check skip sockets, and Results names them. A named pipe or a device file stops the copy before Lighten opens it. Lighten then deletes the copy and publishes nothing. The planner does not check for these files. Why: programs make their sockets again. A pipe or device can block or never end. Reading every source tree on each check is too slow. (#198)

- `[skipped: plan-time block for pipes and devices, add when a user hits one]`
- `[skipped: saying in Review that sockets will be skipped, add when users are surprised by it in Results]`
- `[skipped: a socket in the native comparison, add when CI containers have a tool that makes one]`

### Independent relocations run two at a time; an apply waits on a hung mount

Relocations whose paths do not overlap run through `mapBounded`, on at most `RELOCATION_CONCURRENCY` (2) virtual threads. The steps of one relocation stay in order. After a failure, no new relocation starts, and the relocations that are running finish. A step can block in the kernel, for example on a stuck NFS mount. That step keeps its thread, and the apply waits. There is no timeout, and no thread is abandoned. If the user quits during an apply, Lighten exits when the apply finishes. To stop sooner, the user ends the process. Why: this is safe. The source changes only at atomic renames, and the staging lock ends with the process. The next apply of that target removes its old copy. (#10)

### Only environment failures fail an action; bugs are internal errors

An action fails only for I/O or an expected environment failure, and reports it as a typed `ActionFailure`. Expected environment failures are a changed disk, staging on a different filesystem and no POSIX permissions. Any other exception is a bug. It propagates after cleanup and shows `Internal error (please report): <Type>: <message>`, with exit code 70 and no stack trace. Why: a bug must not look like a disk problem or an invalid file. (2026-10-04, #172)

- `[skipped: printing the stack trace, add when a bug report needs more than the exception type and message, e.g. behind a debug option]`

### Choices are for one apply; rules are saved on request

A one-time choice in Workspace holds for the next apply only. Any check, save or apply removes it. `s: Always do this` saves the choice as the relocation's rule. It first shows a dialog, which warns when the rule deletes data. A missing rule means "Ask each time" (`prompt`), and Lighten does not write it to the file. Why: a one-time "delete both" must not become permanent for `apply --json --yes`. (#116)

Only the rule for the state observed now decides. **Only target** decides when the source is missing. **Both exist** decides when the source and target are both directories. One module, `relocationDecision`, reads the observed states, the saved rules and the one-time choice. From them it gives the governing rule, the choices offered, the choice in force (`●`), and whether that choice comes from the configuration or the one-time choice. Workspace, Review, Results, the `s` dialog and `plan --json` read this module. Configuration's **Both exist** field uses its `BothExistRule`. `plan --json` lists these choices as a conflict's `resolutions`: `adopt-target`, `adopt-and-discard-source`, `adopt-and-archive-source`, `leave-unchanged` and `discard-both`. They stay under `schema: 1` because 1.0 is not released yet. Why: five copies of this logic did not agree. For example, `●` was missing when a Both exist rule was next to an Only target rule or choice. (#211)

- `[skipped: keeping one-time choices across a re-check, add when users re-check often with many open choices]`
- `[skipped: showing a missing rule apart from an explicit prompt, add when a global defaults layer exists]`
- `[skipped: generic Policy<C>, add when a fourth rule appears or code needs to treat all rules the same way]`

### Ignored sources are listed, never planned

`x` ignores a source. Lighten plans nothing for an ignored source and never suggests it, but always shows it. A path cannot be both a relocation and ignored. The loader and each save refuse this. Why: the user can always see and undo what they ignored. (#147)

- `[skipped: listing ignored paths in Configuration's list with d to remove one, add when users want to manage ignores without Browse]`
- `[skipped: shared ignore lists in their own files, add when users want ignores shared across machines]`
- `[skipped: naming which app owns a path, add with #5/#117]`
- `[skipped: an ignored count in the Workspace summary rows, add when users miss it]`

### Browse takes over links the user made, as they are

`Space` or `L` in Browse takes over a link that the user made: it adds a relocation from the link to where the link points, so the next plan finds it in sync. Nothing on disk changes, and only `s` writes the configuration. The word is "take over", because "adopt" already names Keep target. A row inside a linked parent takes over the parent, once, also when no list suggests it. Links inside the parent are never touched. Only a link to a real directory outside the source root can be taken over: the planner needs the target to be a directory, not another link, and a target in the home frees no space. The target is the link's text against its parent, as the planner reads a source link, so a chain is refused, not followed, and so is a relative link whose `..` passes another link, which the system resolves elsewhere. Browse and Configuration check a new relocation's overlap with one rule: against each relocation in the draft, not against overlaps already among them, which the save reports. A link that changes after Browse took it over is blocked by the planner, as any link to somewhere else is. To tell these cases apart, discovery reads what is where a link points, within the same time limit, but it still never lists a directory. A row inside a linked parent counts as found, so `f` shows it. A heading whose rows all move with a linked parent in the configuration has a dim `●`, as its rows do. "Inside the home" means inside the source root. Space on a heading takes over links too, each linked parent once. Why: users who had moved directories by hand saw `already a link` and could not manage them. (#245)

- `[skipped: taking over or repairing a broken link, add when users ask to manage broken links from Browse]`
- `[skipped: comparing a take-over's target with the draft by real path, add when users take over links whose targets are spelled through other links]`
- `[skipped: Details for a heading that list each directory's reason, add when users miss why a group can't be added]`

### The name is Lighten

The tool is Lighten and the command is `lighten`. The file is `~/.lighten.json` with the key `"lighten"`. Names on disk start with `.lighten-`. Lighten does not accept earlier names. Why: the old name was long and a trademark used it, and no release had used it. (#174)

## Platform and build

### Kotlin on JVM 25, released as native Linux binaries

The code is Kotlin with kotlinx.serialization. Releases are GraalVM Native Image binaries for two targets:

- Linux x86_64: fully static, with musl.
- Linux arm64: `--static-nolibc`, glibc 2.17 or later, `-march=compatibility`.

macOS is a development platform only. Why: Kotlin gives null safety, data and sealed types, and compile-time serializers. Native startup time and memory use suit the target machines. (#40, 2026-09-30)

A test build on 2026-09-30 used Oracle GraalVM 25.0.3. These are its results:

- Native start took 2–3 ms, against 130–210 ms on the JVM.
- The TUI's first frame took 8–48 ms.
- Peak RSS was 18–27 MB, against 93–106 MB on the JVM.
- The binary was about 31 MB.
- A native build needs 3 GB of RAM. The arm64 build needs gcc 12 (Oracle Linux 8).
- The test suite passed on Oracle Linux 7.9, Ubuntu 24.04 (with a `noexec` `/tmp`), Debian 13 and Fedora 44.
- The musl x86_64 binary ran on systems as old as CentOS 6, and on Alpine.

### One Gradle module with package boundaries

The build uses the Gradle Kotlin DSL, the official `org.graalvm.buildtools.native` plugin and one module. Why: packages keep concerns apart without the work of many modules. (2026-09-07, 2026-10-01)

### Native support through supported mechanisms only

Lighten uses no GraalVM internals (`@Substitute`, `@TargetClass`, svm APIs), no `kotlin-reflect`, and no HTTP or TLS in the process. Native builds use JLine's exec provider. Why: internals tie each upgrade to a GraalVM release. TLS added 15 MB. JLine's JNI provider fails on a `noexec` `/tmp` and with musl, and its FFM provider hangs. (2026-10-01, #169)

### Keep picocli; generate its metadata

picocli stays the CLI library. On each build, picocli-codegen makes its reflection metadata from the compiled classes. Why: Clikt could do all the same options. But it needed about 45 lines to rebuild picocli features: inherited options, refusing repeated options, exit codes and UTF-8 output. A binary 2.5 MB smaller and no codegen step were not worth that and the change to the help text. Generated metadata always matches the code. (#70, #156, #161)

### Stay with TamboUI

TamboUI is the TUI toolkit, and its version is pinned in `gradle/libs.versions.toml`. Lighten uses as much of it as possible, and its own code works around missing features. Why: no Kotlin TUI framework gives a full-screen app with layout widgets in a static native binary. Look at this decision again if Mosaic releases alternate-screen support and tested native support. Also look again if TamboUI has no progress for about six months. (#88)

## Configuration and formats

### JSON, read and written by kotlinx.serialization

The configuration and the suggestion lists are JSON, with `//` and `/* */` comments and trailing commas. One set of `@Serializable` classes defines each format. kotlinx refuses unknown keys, missing keys, wrong types and unknown rule values. If a key occurs twice, the last value is kept. The loader adds only domain rules. Writing uses `encodeDefaults = false` and removes comments. Environment variables and system properties cannot override settings. Why: this gives precise errors, no YAML aliases or tags in shared input, and no hand-written parser. (#49, #79)

### Paths are full or start with `~/`, and stay as written

After Lighten fills in `${USER}`, each path in the file must be `~`, a full path, or a path that starts with `~/`. This includes `suggestion-list`. Lighten refuses a relative path. `~` is the home directory (see below). `${USER}` is the `USER` variable or, if that is not set, the OS account name. If neither is available, Lighten refuses a path that uses `${USER}`, and never replaces it with empty text. Paths expand only when Lighten converts them to domain types, so a saved file keeps `~` and `${USER}`. `source-root` is `~` by default. If a relocation gives no target, Lighten derives the target from the source's place under the source root. Why: a relative path would depend on the directory where Lighten runs. (#114, #207)

### The home directory comes from `HOME`

The home directory, which `~` names and which holds `.lighten.json`, is the `HOME` variable when it is a full path. Else it is the JVM's `user.home` when that is a full path. Else Lighten stops and tells the user to set `HOME`. It does not guess. One function gives the home directory to all of Lighten. Why: the shell and other tools use `HOME`. Also, the static musl x86_64 binary cannot find a user who comes from LDAP or SSSD, so the JVM sets `user.home` to `?`. This replaces the earlier rule that `~` is `user.home`. (#243)

### One editor for creating and editing

Configuration edits the file's own structure and checks it with the loader's own conversion, so one part of the code owns the path rules. Lighten writes a new file directly. Before it replaces a file, it asks the user and says that comments are lost. It refuses if the file changed after Lighten read it, and it writes atomically. The user can edit only a file that loads. Why: one set of rows and rules is simpler than two editors. (#114)

- `[skipped: opening an invalid file in Configuration, add when users ask to fix a broken file from the editor]`
- `[skipped: per-row changed/new markers, add when users lose track of edits in long lists]`
- `[skipped: Tab completion for paths, add when typing paths becomes a complaint]`
- `[skipped: a way to reorder relocations in Configuration, add when users ask to rearrange without editing the file]`

### An unreadable configuration says what is wrong and how to fix it

The loader throws `InvalidConfigurationException` with the path and the line. Where a recognizer knows a kotlinx message, Lighten gives it new words. The recognizers cover syntax, wrong kind, missing or unknown key, and bad rule value. Other messages pass through unchanged. The TUI and the CLI both show the problem, `To fix it` and `To start over`. Why: the parser's own text did not tell the user what to do next. (#164)

- `[skipped: start over with a backup from inside the app, add when users ask]`
- `[skipped: own steps for a file Lighten cannot open (a directory at the path, no permission, not UTF-8), add when a user hits one]`

## CLI and JSON contract

### TUI for people, JSON for scripts

`lighten` and its commands open the TUI. There is no plain-text output for people. `--json` never starts a terminal. A command without `--json` and without a terminal exits with code 2. `apply --json` needs `--yes`. `--yes` confirms a plan that the rules already decide, and it never makes a choice. Why: there is only one interface for people to keep correct, and automation cannot answer prompts. (2026-09-12)

### Versioned responses and stable exit codes

`status --json`, `plan --json` and `apply --json --yes` start with `"schema": 1`. `status` has one structure, with or without a configuration. The exit codes are:

- 0: success.
- 1: configuration or apply failure.
- 2: usage error.
- 70: internal error.

JSON paths stay full. `apply --json` puts the executor's text in `message`. For an I/O failure, that text is the system's reason and then the paths, such as `permission denied: <path>`, because the JDK's own message can be only a path (#237). Control characters are escaped in lower-case hex (`\u001f`), as kotlinx writes them. Why: scripts need a stable contract, and JSON parsers do not see the case of an escape. (#19, #61, #172, #201)

- `[skipped: one shared envelope for every outcome across commands, add when someone scripts against Lighten and needs it]`
- `[skipped: JSON errors on stdout (config errors, internal errors stay one stderr line), add when a script needs to parse them]`
- `[skipped: config validate --json, add when a script needs validation without planning]`
- `[skipped: a machine-readable failure kind in apply --json, add when a script needs to tell failures apart]`
- `[skipped: merging the Missing and Unconfigured evaluation states, add when the JSON contract is revisited]`
- `[skipped: redirected-I/O/terminal-isolation and source-audit test suites beyond what exists]`

### Paths on screen use `~`; machine output keeps full paths

Each path that people read shows the home directory as `~`, never the source root. `--json` keeps full paths. Inside messages, paths stay `Path` values (`PathText`) until Lighten shows them. Exception text never shows a Java type name. Why: `~` is shorter, and scripts need exact paths. (#201)

- `[skipped: ~ in Configuration's fields, which show the file's text as written, add when a user wants the editor to rewrite full home paths]`

## Release and distribution

### A version tag publishes a GitHub Release

When a maintainer pushes a tag `v<major>.<minor>.<patch>[-<pre-release>]`, the workflow publishes a release. It does this only if the commit is on `main` and the 7 required checks passed on it. The build uses the tag's version. The notes are the notes that GitHub generates. A tag with a pre-release part publishes a pre-release. Why: Lighten releases only tested commits, with no manual steps. [`CONTRIBUTING.md`](../CONTRIBUTING.md) gives the steps. (#167)

- `[skipped: signed releases (minisign or cosign), add when users outside the maintainer's machines install it]`
- `[skipped: JBang catalog, add when a JVM build is wanted for macOS/Windows or architectures without native binaries]`
- `[skipped: Homebrew tap, add when Linuxbrew users ask or macOS binaries ship]`
- `[skipped: waiting for the checks in the release workflow, add when tagging right after a merge becomes common]`

### Release assets

Asset names are a public contract. `install.sh`, `lighten update` and tools that install from GitHub Releases (mise, ubi, eget) use them. A published name never changes.

```text
lighten-<version>-linux-x86_64-musl    static musl binary, any Linux x86_64
lighten-<version>-linux-aarch64-gnu    glibc 2.17+ binary, Linux arm64
SHA256SUMS                             sha256sum output for both binaries
install.sh                             the install script
```

Why: plain binaries need no `tar`. Names from `uname -m` need no lookup table. The libc suffix tells what each binary needs. Alpine on arm64 needs `gcompat`. (#167)

- `[skipped: lighten-<version>-linux-aarch64-musl, add when Alpine arm64 users ask; eget would then ask which arm64 binary to take]`
- `[skipped: a <asset>.sha256 file per binary for eget's check, add when eget users ask]`
- `[skipped: aqua-registry entry, add when aqua users ask]`

### Install script

`install.sh` is a POSIX `sh` script. It selects the asset from `uname -m` and reads the latest version from `SHA256SUMS`, without the GitHub API. It checks the hash, runs `--version` as a test, and renames the binary into place. The default directory is `~/.local/bin`. It never calls sudo. It asks once before it edits a shell startup file, and it edits only files that the user owns in their home directory. Why: there is one safe way to install and update. If it fails, the existing install does not change. (#168)

- `[skipped: signature checks beyond SHA256SUMS, add with signed releases]`
- `[skipped: an uninstall option, add when users ask; removing ~/.local/bin/lighten and the marked line is the uninstall]`
- `[skipped: a containers test of the shasum fallback, add when a supported distro lacks sha256sum]`
- `[skipped: a system-wide PATH entry (/etc/profile.d) for root installs, add when admins install for all users]`

### `lighten update` runs the release's install script

`update` checks `SHA256SUMS`. Then it uses `curl` or `wget` to run that release's `install.sh` with `--no-modify-path`, on the real directory of the running binary. Without `--version`, it never installs an older version. Before it downloads, it refuses development builds, the JVM, mise installs, directories it cannot write to, and a binary whose name is not `lighten`. `--check` only reports. Why: the install rules exist in one place only, and the binary has no TLS, which would add about 15 MB. (#169)

- `[skipped: automatic update notice, add when users run old versions without knowing]`
- `[skipped: signature verification, add with signed releases]`
- `[skipped: a distinct --check exit code for "update available", add when a script needs it]`
- `[skipped: a native end-to-end update in CI, add by testing the release build in release.yml]`
- `[skipped: detecting aqua or Homebrew installs, add when either is a documented install method]`

## Suggestion-list curation

### Lists are files, the built-in list first, yours merged in

The built-in list is in the binary. `suggestion-list` names an optional shared file, usually on storage that the machines already share. Lighten never gets lists over HTTP or Git. When both lists name a directory, your list gives its app and advice. An app takes the category from your list if your list gives one, and otherwise from the built-in list. Lists have limits on size, record count and string length, and Lighten decodes them strictly. Why: there are no network failures or credentials. A team can add to the built-in list without typing it again. (2026-09-30, #115, #165)

### Categories group apps, in the file's order

Each app can name a `category`. Browse shows categories, apps and directories in the order they first occur, with Other tools and Other directories last. The built-in order is Editors, Python, Rust, C and C++, JVM, JavaScript, Go, Ruby, Android, Build tools, Version managers. A version manager for one language is under that language. Why: one key adds a whole stack, and the file has the same order as the screen. (#165, #176, #191)

- `[skipped: moving an app to another category without naming one of its directories, add when teams want to retag built-in apps wholesale]`
- `[skipped: the category in Details' "Suggested by" lines, add when users ask which list set it]`

### Each directory is checked and the narrowest safe one is listed

A directory goes into the built-in list only after a test in a container. The test installs the tool, relocates the directory with `lighten apply`, uses the tool again and cleans it with the tool's own commands. The paths come from the tool's documentation or source code. The list names the cache or install directory. It does not name a parent directory that also holds the tool, its shims or its settings. The exception is a tool that needs the whole parent on one filesystem (Volta). If a tool's clean command replaces the link with a directory, the entry has a `caution`, which Browse shows in Details. Why: the tool must continue to work after the move. (#165, #176, #191)

- `[skipped: conda, mamba and micromamba directories, add when users ask for a specific install layout]`
- `[skipped: .cache/mise, add when users report it growing large]`
- `[skipped: legacy .fnm/node-versions, add when users with old installs ask]`
- `[skipped: Volta's .volta/tools alone, add if Volta stages installs inside tools]`
- `[skipped: a caution on the Browse row itself, add when users miss cautions that only Details shows]`
- `[skipped: CMake, add when it gains a default per-user cache]`
- `[skipped: Zed's whole data directory; #198 removed the socket that blocked it, add when users ask for Zed's database and logs to move too]`
- `[skipped: .platformio/platforms and .platformio/.cache, add when users report them large]`
- `[skipped: .cache/bazelisk, add when users report versions piling up]`
- `[skipped: .local/share/Google (Android Studio plugins), add when users report it large]`
- `[skipped: keeping hard links when moving, add when users move Hunter or similar caches and ask about the space]`

## TUI

The rules are in [`tui-design.md`](tui-design.md), which holds only the current rules. Their history is here and in git.

- **A full-screen TamboUI app** with a screen for each command. It is not a desktop GUI, because a GUI needs a display over SSH. ([§2](tui-design.md#2-screens-and-navigation), 2026-09-12)
- **Its own visual language**, designed for relocation work, not a general dashboard. ([§1](tui-design.md#1-principles))
- **The Harbor palette on Lighten's own background**, with basic colors as a fallback. A color can carry meaning only when the screen also says the same thing in another way. ([§4 Color](tui-design.md#color))
- **TamboUI owns focus, fields, choices and dialogs**, with its `standard` key bindings. Esc goes back one level and never exits. ([§3](tui-design.md#3-keys-and-focus))
  - `[skipped: TamboUI FormElement, add when it supports per-field key handling and a dialog on top]`
- **The mouse is captured for the wheel only.** Clicks do nothing. To select text, the user holds the terminal's bypass modifier. ([§3](tui-design.md#3-keys-and-focus), #146)
  - `[skipped: click to focus or select, add when users ask]`
  - `[skipped: the mouse wheel over Configuration's list and fields, add when users ask]`
- **Dialogs for one question, screens for work.** ([§4 Dialogs](tui-design.md#dialogs))
  - `[skipped: dimming the whole screen behind a dialog, add when the border and lost focus are not enough separation]`
  - `[skipped: wrapping long paths in dialogs, add when a path cut off at 80 columns is reported]`
- **Help is a screen with two tabs**, This screen and Guide. The user guide is its only text. The help lines and the Help screen read the keys from one source. ([§4 Help screen](tui-design.md#help-screen), #146)
  - `[skipped: tying key handlers to their listing, add when a walkthrough finds a listed key that does nothing]`
  - `[skipped: first-run tour, add when walkthroughs show Help is not found]`
  - `[skipped: PageUp/PageDown and Home/End in Review's Action details, add when long action details are reported]`
  - `[skipped: "Leave" group for q/Esc, add when a walkthrough still misreads q or Esc after the descriptions]`
- **Workspace** keeps the file's order in each urgency group. It gives the decision once, and it warns only where data is lost permanently. ([§5](tui-design.md#5-workspace), #110, #183)
  - `[skipped: plain-language reasons for blocked rows, add when the planner's reasons are reworded for JSON output too]`
  - `[skipped: keeping the Decision line in view when Details takes focus, add when users miss it]`
- **Quit asks before it forgets one-time choices.** ([§3 Quit](tui-design.md#quit), #111)
- **Review, Applying and Results** show the plan as headings and rows. Progress never moves the selection, which moves once at the end. A failed step tells what is on disk and what to do. ([§6](tui-design.md#6-review-applying-and-results), #111, #157, #172)
  - `[skipped: a softer Review warning for a verified "Replace source with a link" step, add when the Review walkthrough finds it alarming]`
  - `[skipped: a status line in a relocation's Details, add when the step marks are not enough]`
  - `[skipped: folding a relocation's steps, add when plans are long enough that users ask to fold them]`
  - `[skipped: indenting action rows more than two cells, add when the plan list is wider at 80 columns or its rows become one line each]`
  - `[skipped: plain words for the preflight refusal's diagnostics, add when a walkthrough finds them unclear]`
  - `[skipped: own words for errors the OS reports only as a reason (no space left, read-only filesystem), add when a user hits one]`
- **Configuration** has labels next to its fields, and it opens Browse on a first run. ([§7](tui-design.md#7-configuration), #114, #189)
  - `[skipped: selecting the first added relocation when the first-run Browse closes, add when users miss where their picks went]`
- **Browse** is a list of categories, apps and directories with plain marks. It always shows its lists, removes old results while it checks, and can show only what it finds on this machine. It takes over links the user made, and a heading with nothing to add says why. ([§8](tui-design.md#8-browse), #115, #165, #190, #245)
  - `[skipped: showing previous results while checking again, add when re-checks are slow enough that blank rows annoy users]`
  - `[skipped: per-row list history, add when users need to know a list used to suggest a row]`
  - `[skipped: PageUp/PageDown in Browse, add when suggestion lists grow past a few screens]`
  - `[skipped: the year in "file updated", add when lists older than a year are common]`
  - `[skipped: folding categories, add when the built-in list grows past a few screens]`
  - `[skipped: / to filter by text, add when lists grow past two screens]`
  - `[skipped: first-run Browse with f on, add when new users report scrolling past tools they don't have]`
- **Plain marks for choices**: `●` for chosen and `○` for not chosen, in green and bold. ([§4 Layout and glyphs](tui-design.md#layout-and-glyphs), #173)
- **Screens are checked at 80x24 and 120x30.** ([§10](tui-design.md#10-verification))
  - `[skipped: 200x50 checks, add when a wide-terminal layout bug appears]`

## Archive

These entries were replaced or are history only. They use the names of their time. Git history has their full text.

- 2026-08-29: Keep integrations behind adapters. History: no adapter layer was necessary. The core reads the filesystem through `java.nio.file`, and Lighten does not download lists.
- 2026-08-29: Design for native-image compatibility. Done: the releases are native binaries.
- 2026-09-07: Prefer guided CLI over desktop GUI. Replaced by the full-screen TUI. The decision against a desktop GUI stays.
- 2026-09-07: Combine non-interactive and guided CLI in a single binary. Replaced by "TUI for people, JSON for scripts".
- 2026-09-07: Maintain a single Maven module. Gradle replaced Maven. The single module stays.
- 2026-09-07: Use plain Java 25 and targeted libraries. Kotlin and kotlinx.serialization replaced Java, SnakeYAML and Jackson.
- 2026-09-30: Stay on Java; ship Linux native binaries. Kotlin replaced Java. The native targets and the test-build numbers stay. Rust was possible, but a port was about 13k lines.
- 2026-09-30: Replace smallrye-config with snakeyaml. JSON replaced snakeyaml. Lighten still has no environment overrides.
- 2026-09-30: Relax candidate-list strictness. Replaced by JSON.
- 2026-09-30: Run agent work through a cloud coordinator. History only.
- 2026-10-01: Move to Kotlin and kotlinx.serialization. This is now "Kotlin on JVM 25". The migration (#40, `kotlin-migration` branch) is done.
- 2026-10-01: Read JSON configuration strictly. Replaced by "JSON, read and written by kotlinx.serialization" (#79).
- 2026-10-01: Keep Java whitespace semantics for validation. Moved to `AGENTS.md` rule 10.
- 2026-10-01: Keep threads and locks during the Kotlin migration. Moved to `AGENTS.md` rule 11. #10 chose bounded virtual threads.
- 2026-10-04: How design and simplification decisions are made. Moved to `AGENTS.md`.
- 2026-10-04: Application-layer cleanup. Done.
- 2026-10-05: Functions return their results. Moved to `AGENTS.md` rule 5.
- 2026-10-06: Review's plan is a TamboUI tree. Replaced by headings and rows (#157).
- 2026-10-06: Browse is a tree of suggestions. Replaced by the heading list, variant C (#115).
- 2026-10-07: Browse groups apps by ecosystem. The level is now "category" (#174).
- 2026-10-07: Rename HomeLight to Lighten. The repository rename that it left open is done.

## How to add

Add a decision under its theme, as a `###` rule in the form above. Give the rule, a one-line reason, the open `[skipped: …]` items, and the issue or pull request. When a rule changes, change it in place. When a rule is removed or replaced, add a line to the archive. Put details in the pull request, not here.
