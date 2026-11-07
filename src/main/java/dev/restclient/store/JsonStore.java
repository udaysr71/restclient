package dev.restclient.store;

import dev.restclient.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Human-readable JSON files written atomically (temp file + rename) so a crash can't corrupt them. */
public final class JsonStore {
    private JsonStore() {
    }

    public static byte[] toBytes(Object value) {
        return JsonMapper.toJson(value).getBytes(StandardCharsets.UTF_8);
    }

    public static <T> T read(Path file, Class<T> type) throws IOException {
        return JsonMapper.fromJson(Files.readString(file, StandardCharsets.UTF_8), type);
    }

    /** Reads the file, or returns null if it doesn't exist. */
    public static <T> T readIfExists(Path file, Class<T> type) throws IOException {
        return Files.exists(file) ? read(file, type) : null;
    }

    public static void write(Path file, Object value) throws IOException {
        writeBytes(file, toBytes(value));
    }

    public static void writeBytes(Path file, byte[] bytes) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, "." + file.getFileName(), ".tmp");
        try {
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** Deep copy through JSON. */
    public static <T> T deepCopy(T value, Class<T> type) {
        return JsonMapper.copy(value, type);
    }
}
