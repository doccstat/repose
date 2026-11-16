package com.lixingchi.repose;

import org.json.JSONObject;
import org.junit.Test;
import java.io.*;
import static org.junit.Assert.*;

public class WireTest {
    @Test public void frameRoundTripPreservesInput() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        String text = "  密码 🌙\n ";
        Wire.write(output, new JSONObject().put("message", text));
        assertEquals(text, Wire.read(new ByteArrayInputStream(output.toByteArray())).getString("message"));
    }
    @Test public void rejectsOversizedNegativeTruncatedAndInvalidJson() throws Exception {
        for (int size : new int[]{-1, 0, Wire.LIMIT + 1, 10}) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            new DataOutputStream(output).writeInt(size);
            try { Wire.read(new ByteArrayInputStream(output.toByteArray())); fail(); } catch (IOException expected) { }
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(output); data.writeInt(3); data.writeBytes("bad");
        try { Wire.read(new ByteArrayInputStream(output.toByteArray())); fail(); } catch (org.json.JSONException expected) { }
        try { Wire.write(output, new JSONObject().put("body", "x".repeat(Wire.LIMIT))); fail(); } catch (IOException expected) { }
    }
}
