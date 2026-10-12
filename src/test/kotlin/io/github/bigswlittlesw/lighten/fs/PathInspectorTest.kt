package io.github.bigswlittlesw.lighten.fs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class PathInspectorTest {
    @Test
    fun reportsFilesystemStatesWithoutFollowingLinks(@TempDir root: Path) {
        val expected = root.resolve("local")
        Files.createDirectories(expected)
        val inspector = PathInspector()

        assertEquals(PathState.ABSENT, inspector.inspect(root.resolve("absent")).state)

        val directory = root.resolve("directory")
        Files.createDirectory(directory)
        val directoryObservation = inspector.inspect(directory)
        assertEquals(PathState.DIRECTORY, directoryObservation.state)
        assertEquals(true, directoryObservation.emptyDirectory)
        Files.createFile(directory.resolve("entry"))
        assertEquals(false, inspector.inspect(directory).emptyDirectory)

        val file = root.resolve("file")
        Files.createFile(file)
        assertEquals(PathState.FILE, inspector.inspect(file).state)

        val real = expected.toRealPath()
        val correct = root.resolve("correct")
        Files.createSymbolicLink(correct, expected)
        val correctObservation = inspector.inspect(correct)
        assertEquals(PathObservation(PathState.SYMLINK, expected, SymlinkTargetAvailability.EXISTS, symlinkRealPath = real, symlinkText = expected), correctObservation)
        assertEquals(RelocationSourceState.CORRECT_SYMLINK, correctObservation.sourceStateForTarget(real))

        // Its text differs, but it leads to the same directory through another link.
        Files.createSymbolicLink(root.resolve("via"), root)
        val through = root.resolve("through")
        Files.createSymbolicLink(through, Path.of("via/local"))
        assertEquals(RelocationSourceState.CORRECT_SYMLINK, inspector.inspect(through).sourceStateForTarget(real))

        val wrong = root.resolve("wrong")
        val other = Files.createDirectory(root.resolve("other"))
        Files.createSymbolicLink(wrong, other)
        assertEquals(RelocationSourceState.WRONG_SYMLINK, inspector.inspect(wrong).sourceStateForTarget(real))

        val broken = root.resolve("broken")
        Files.createSymbolicLink(broken, root.resolve("missing"))
        assertEquals(PathObservation(PathState.SYMLINK, root.resolve("missing"), SymlinkTargetAvailability.ABSENT, symlinkText = root.resolve("missing")), inspector.inspect(broken))
        assertEquals(RelocationSourceState.BROKEN_SYMLINK, inspector.inspect(broken).sourceStateForTarget(real))

        // Links that loop can't be followed.
        Files.createSymbolicLink(root.resolve("loop-a"), Path.of("loop-b"))
        Files.createSymbolicLink(root.resolve("loop-b"), Path.of("loop-a"))
        assertEquals(SymlinkTargetAvailability.INACCESSIBLE, inspector.inspect(root.resolve("loop-a")).symlinkTargetAvailability)
    }

    @Test
    fun treatsAnInaccessibleSymlinkDestinationAsInaccessible(@TempDir root: Path) {
        val expected = root.resolve("local")
        val observation = PathObservation(PathState.SYMLINK, expected,
                SymlinkTargetAvailability.INACCESSIBLE, symlinkText = expected)

        assertEquals(RelocationSourceState.INACCESSIBLE, observation.sourceStateForTarget(expected))
    }

    @Test
    fun aLinkHasARealPathExactlyWhenItsDestinationExists(@TempDir root: Path) {
        assertThrows<IllegalArgumentException> {
            PathObservation(PathState.SYMLINK, root, SymlinkTargetAvailability.EXISTS, symlinkText = root)
        }
        assertThrows<IllegalArgumentException> {
            PathObservation(PathState.SYMLINK, root, SymlinkTargetAvailability.ABSENT, symlinkRealPath = root, symlinkText = root)
        }
    }
}
