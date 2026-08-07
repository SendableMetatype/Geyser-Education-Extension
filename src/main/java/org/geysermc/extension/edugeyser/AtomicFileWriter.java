package org.geysermc.extension.edugeyser;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Writes a complete file beside its destination and replaces the destination
 * only after the new contents have been flushed successfully.
 */
final class AtomicFileWriter {

    private AtomicFileWriter() {
    }

    static void writeString(Path target, String contents) throws IOException {
        writeString(target, contents, AtomicFileWriter::replace);
    }

    static void writeString(Path target, String contents, Replacer replacer) throws IOException {
        Path absoluteTarget = target.toAbsolutePath();
        Path parent = absoluteTarget.getParent();
        if (parent == null) {
            throw new IOException("Cannot determine the parent directory for " + target);
        }
        Files.createDirectories(parent);

        Path temporary = Files.createTempFile(parent, absoluteTarget.getFileName() + ".", ".tmp");
        boolean replaced = false;
        try {
            byte[] bytes = contents.getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(temporary,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }

            replacer.replace(temporary, absoluteTarget);
            replaced = true;
        } finally {
            if (!replaced) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private static void replace(Path temporary, Path target) throws IOException {
        try {
            Files.move(temporary, target,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @FunctionalInterface
    interface Replacer {
        void replace(Path temporary, Path target) throws IOException;
    }
}
