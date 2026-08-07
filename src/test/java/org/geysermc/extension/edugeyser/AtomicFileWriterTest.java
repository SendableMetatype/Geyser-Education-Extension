package org.geysermc.extension.edugeyser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AtomicFileWriterTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void replacesExistingFileWithoutLeavingTemporaryFiles() throws IOException {
        Path target = temporaryDirectory.resolve("sessions.yml");
        Files.writeString(target, "old contents");

        AtomicFileWriter.writeString(target, "new contents");

        assertEquals("new contents", Files.readString(target));
        try (var files = Files.list(temporaryDirectory)) {
            assertEquals(List.of(target), files.toList());
        }
    }

    @Test
    void failedReplacementPreservesExistingFileAndRemovesTemporaryFile() throws IOException {
        Path target = temporaryDirectory.resolve("sessions.yml");
        Files.writeString(target, "known good contents");

        assertThrows(IOException.class, () -> AtomicFileWriter.writeString(target, "incomplete contents",
                (temporary, destination) -> {
                    throw new IOException("simulated replacement failure");
                }));

        assertEquals("known good contents", Files.readString(target));
        try (var files = Files.list(temporaryDirectory)) {
            assertEquals(List.of(target), files.toList());
        }
    }
}
