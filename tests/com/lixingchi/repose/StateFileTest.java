package com.lixingchi.repose;

import java.nio.file.*;
import org.json.JSONObject;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class StateFileTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    @Test public void atomicReplacementAndRestartKeepExactState() throws Exception {
        Path path = folder.getRoot().toPath().resolve("state.json");
        StateFile file = new StateFile(path);
        assertEquals(0, file.read().length());
        file.write(new JSONObject().put("counter", 1).put("value", " exact 密码 "));
        file.write(new JSONObject().put("counter", 2).put("value", " exact 密码 "));
        JSONObject restored = new StateFile(path).read();
        assertEquals(2, restored.getInt("counter")); assertEquals(" exact 密码 ", restored.getString("value"));
        assertFalse(Files.exists(path.resolveSibling("state.json.new")));
    }
    @Test public void failedWriteKeepsCommittedStateAndCorruptionNeverBecomesDefaults() throws Exception {
        Path path = folder.getRoot().toPath().resolve("state.json");
        StateFile file = new StateFile(path); file.write(new JSONObject().put("counter", 1));
        Files.createDirectory(path.resolveSibling("state.json.new"));
        try { file.write(new JSONObject().put("counter", 2)); fail(); } catch (java.io.IOException expected) { }
        assertEquals(1, file.read().getInt("counter"));
        Files.write(path, new byte[]{0, 1, 2});
        try { file.read(); fail(); } catch (org.json.JSONException expected) { }
    }
}
