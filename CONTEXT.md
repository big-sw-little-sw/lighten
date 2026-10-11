# Lighten

Lighten moves directories out of a home directory to storage and puts a link in the place of each one. For each directory that it manages, the desired state is a real target directory and a source symlink to it.

Each term starts with the word that users see in the user guide's "Words to know" and on the screens. Then it gives the name that the code uses. In the guide, the TUI and new docs, use the word that users see.

## Configuration

**Directory**:
A place on disk that holds files and other directories. Linux and its tools use this word.
Code: `PathState.DIRECTORY`.
_Avoid_: folder

**Relocation**:
One directory that Lighten manages. It has a source, a target and rules.
Code: `Relocation`; in the file, `RelocationFile` under `relocations`.
_Avoid_: mapping, migration, entry

**Source**:
The place where programs look for the directory, usually in the home directory. After a move, the source is a link to the target.
Code: `Relocation.sourcePath`, `source-path`. A `RelocationSourceState` (in `fs`) records what is at the source now, compared with the target.

**Target**:
The place in storage where the contents of the directory are.
Code: `Relocation.targetPath`, `target-path`. When it is not set, `derivedTarget` makes it from `source-root` and `target-root`.
_Avoid_: destination

**Target root, source root**:
The target root is where the storage is. The source root is the directory that usually holds the sources (default `~`). A relocation without a target keeps its path below the source root, but inside the target root.
Code: `target-root`, `source-root` in `LightenFile`.

**Rule**:
A saved setting that decides what happens each time a relocation is in one observed state. The default is "Ask each time".
Code: `WhenOnlyTargetExists`, `WhenSourceAndTargetDirectoriesExist`, `WhenAdoptingTarget` ("Ask each time" is `PROMPT`). `BothExistRule` holds the two "Both exist" settings as one value. `GoverningRule` is the rule for the state observed now.
_Avoid_: policy (in user-facing text)

**Ignored**:
A directory that the user told Lighten to leave alone. Lighten plans nothing for it, but it still shows it in the list.
Code: `ignored-source-paths`, `LightenConfiguration.ignoredSourcePaths`.
_Avoid_: excluded, skipped

## Planning and applying

**Plan**:
The steps that Lighten would do for each relocation. Lighten makes the plan from what is on disk now and from the rules. Making a plan changes nothing.
Code: `ReconciliationPlan` of `RelocationPlan`s; a step is a `ReconciliationAction`; `ReconciliationPlanner` makes the plan.

**Choose**:
A relocation whose rule for its current state is "Ask each time". The user must make a decision before the plan can be applied.
Code: `ReconciliationConflict`; badge `PlanBadge.CONFLICT`.
_Avoid_: conflict (in user-facing text)

**One-time choice**:
A decision for one relocation that is used only for the next apply. When the user checks again or applies, Lighten forgets it. When the user saves it, it becomes the rule.
Code: `DecisionChoice`, held by `LightenSession`. `RelocationDecision` (`relocationDecision`) holds it together with the rule that it replaces. `plan --json` shows the choices as the `resolutions` of a conflict.
_Avoid_: override, resolution

**Blocked**:
A relocation that Lighten cannot do in the current conditions. For example, a file is where a directory must go. When the user removes the cause and checks again, the relocation is no longer blocked.
Code: `ReconciliationAction.Blocked`; badge `PlanBadge.BLOCKED`.

**Review**:
The screen that shows all the steps of the plan before anything changes. `y` applies exactly that plan.
Code: `ApplyModel.Reviewed`, `ReviewedExecution`.

**Apply**:
To do the steps of a reviewed plan. Each step first checks that the disk is as the plan expects. If it is different, the apply stops.
Code: `ReviewedExecution`, `ReconciliationExecutor`.
_Avoid_: execute, run (in user-facing text)

**Check again**:
To read the disk and the configuration again and make a new plan (`r`).
Code: `LightenSession.refresh`.
_Avoid_: refresh, re-plan (in user-facing text)

**In sync**:
A relocation whose target is a real directory and whose source is the correct link to it. A new plan has no steps for it.
Code: `RelocationOutcome.CONVERGED`; badge `PlanBadge.IN_SYNC`.
_Avoid_: completed move, migrated, converged (in user-facing text)

**Left as is**:
A relocation that Lighten does not change on purpose, because its rule or choice is "Leave both as they are".
Code: `WhenSourceAndTargetDirectoriesExist.LEAVE_UNCHANGED`, `RelocationOutcome.UNCHANGED`; badge `PlanBadge.SKIPPED`.
_Avoid_: no-op, preserved

**Keep target**:
To use an existing target directory as the one to keep. When the source is also a directory, the user must also decide what to do with the source: delete it or archive it.
Code: `ADOPT`, `ADOPT_TARGET`. The badge is `PlanBadge.ADOPT` when the source is deleted, and `PlanBadge.LINK` when there is no source.
_Avoid_: adopt (in user-facing text), adopt source

**Archive**:
To move the source into an archive directory, so that the user can move it back. The default archive directory is `.lighten-archive` next to the source. The opposite is delete, which removes the source permanently.
Code: `WhenAdoptingTarget.ARCHIVE_SOURCE`, `Relocation.archiveRoot`, `ReconciliationAction.ArchiveDirectory`; badge `PlanBadge.BACKUP`.
_Avoid_: backup

**Staging**:
A directory that Lighten owns, on the same filesystem as the target. The default is `.lighten-staging` next to the target. Lighten copies a directory into it and checks the copy. Then it renames the copy into its final place.
Code: `staging-root`, `Staging.kt`, `ReconciliationAction.MigrateDirectoryForPublication`.
_Avoid_: temporary directory, transaction journal

## Suggestions

**Suggestion list**:
A list of directories that Browse suggests to move. The built-in list is part of Lighten. The user can also have a list of their own, in the file that `suggestion-list` names.
Code: `CandidateCatalog`; a suggestion is a candidate (`CandidateDefinition`). The user's list is the shared list (`CandidateSource.Kind.SHARED`, `LightenConfiguration.sharedList`). The built-in list is `CandidateCatalog.BUNDLED`.
_Avoid_: candidate list (in user-facing text)

**Take over**:
To add a link that the user made by hand to the configuration as it is: a relocation from the link to where the link points. Nothing on disk changes, and the next plan finds the relocation in sync. In Browse, `Space` takes over one link and `L` takes over every link shown. A directory inside a linked parent is taken over with its parent.
Code: `BrowseDraft.takeOver`, `BrowseDraft.TakeOver`, `BrowseAction.TakeOverAll`; what discovery saw is `CandidateObservation.Link`.
_Avoid_: adopt (that is **Keep target**), import, track

**Category, app**:
The two levels that Browse uses to group suggestions: first a category, for example Python, then an app, for example uv.
Code: `CandidateDefinition.category`, `CandidateDefinition.app`.
_Avoid_: ecosystem, group
