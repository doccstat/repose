package com.lixingchi.repose;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import org.json.JSONObject;

/** A successful write means data, atomic replacement and the parent directory were synced. */
public final class StateFile implements Engine.Store {
    final Path path;
    StateFile(Path path) { this.path = path; }
    JSONObject read() throws Exception {
        try { return new JSONObject(new String(Files.readAllBytes(path), StandardCharsets.UTF_8)); }
        catch (NoSuchFileException absent) { return new JSONObject(); }
    }
    @Override public void write(JSONObject value) throws Exception {
        Path temporary = path.resolveSibling(path.getFileName() + ".new");
        try (FileOutputStream stream = new FileOutputStream(temporary.toFile())) {
            stream.write(value.toString().getBytes(StandardCharsets.UTF_8));
            stream.getFD().sync();
        }
        Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        try (FileChannel directory = FileChannel.open(path.toAbsolutePath().getParent(), StandardOpenOption.READ)) {
            directory.force(true);
        }
    }
}
