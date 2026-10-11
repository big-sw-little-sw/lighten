package io.github.bigswlittlesw.lighten.fs

import java.nio.file.Path

/**
 * A no-follow observation of one filesystem path.
 *
 * Only a symlink has a target and an availability; only a directory can be empty. `symlinkTarget` is the link's text
 * against its parent. `symlinkRealPath` is where the system finds the link's destination, with every link on the way
 * resolved. A link has it exactly when its destination exists.
 */
data class PathObservation(
    val state: PathState,
    val symlinkTarget: Path? = null,
    val symlinkTargetAvailability: SymlinkTargetAvailability = SymlinkTargetAvailability.NOT_A_SYMLINK,
    val emptyDirectory: Boolean = false,
    val symlinkRealPath: Path? = null,
) {
    init {
        val symlink = state == PathState.SYMLINK
        val hasTarget = symlinkTarget != null || symlinkTargetAvailability != SymlinkTargetAvailability.NOT_A_SYMLINK
        require(symlink || !hasTarget) { "Only symlink observations may have a symlink target" }
        require(!symlink || (symlinkTarget != null && symlinkTargetAvailability != SymlinkTargetAvailability.NOT_A_SYMLINK)) {
            "A symlink observation needs its target and availability"
        }
        require(!emptyDirectory || state == PathState.DIRECTORY) { "Only directory observations may be empty" }
        require((symlinkRealPath != null) == (symlinkTargetAvailability == SymlinkTargetAvailability.EXISTS)) {
            "A link has a real path exactly when its destination exists"
        }
    }

    /** Where a link leads: its real path when its destination exists, else its text against its parent. */
    val linkDestination: Path? get() = symlinkRealPath ?: symlinkTarget

    /**
     * What is at this source, compared with a target whose real path is [targetRealPath]. A link is correct when its
     * real path is the target's, whatever its text says: another tool may own the link and spell it through other
     * links.
     */
    fun sourceStateForTarget(targetRealPath: Path): RelocationSourceState = when (state) {
        PathState.ABSENT -> RelocationSourceState.ABSENT
        PathState.FILE -> RelocationSourceState.FILE
        PathState.DIRECTORY -> RelocationSourceState.DIRECTORY
        PathState.INACCESSIBLE -> RelocationSourceState.INACCESSIBLE
        PathState.OTHER -> RelocationSourceState.OTHER
        PathState.SYMLINK -> when (symlinkTargetAvailability) {
            SymlinkTargetAvailability.EXISTS ->
                if (symlinkRealPath == targetRealPath)
                    RelocationSourceState.CORRECT_SYMLINK
                else
                    RelocationSourceState.WRONG_SYMLINK
            SymlinkTargetAvailability.ABSENT -> RelocationSourceState.BROKEN_SYMLINK
            SymlinkTargetAvailability.INACCESSIBLE -> RelocationSourceState.INACCESSIBLE
            SymlinkTargetAvailability.NOT_A_SYMLINK -> error("Invalid symlink observation")
        }
    }
}
