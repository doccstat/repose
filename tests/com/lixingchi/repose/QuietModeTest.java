package com.lixingchi.repose;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import static org.junit.Assert.*;

public class QuietModeTest {
    static class Child extends Process {
        boolean finished = true, destroyed;
        int status;
        final InputStream input;
        Child(int status, String result) { this.status = status; input = new ByteArrayInputStream(result.getBytes(StandardCharsets.US_ASCII)); }
        public InputStream getInputStream() { return input; }
        public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
        public int waitFor() { return status; }
        public boolean waitFor(long timeout, TimeUnit unit) { assertEquals(10, timeout); assertEquals(TimeUnit.SECONDS, unit); return finished; }
        public int exitValue() { return status; }
        public void destroy() { destroyed = true; }
        public Process destroyForcibly() { destroyed = true; return this; }
    }
    @Test public void successClosesTheWorker() throws Exception {
        Child child = new Child(0, ""); QuietMode.await(child); assertTrue(child.destroyed);
    }
    @Test public void failuresExportOnlyStableCodes() throws Exception {
        for (String body : new String[]{"profile_pause_rejected", "", "unstructured private error details\n"}) {
            Child child = new Child(1, body);
            try { QuietMode.await(child); fail(); }
            catch (IllegalStateException expected) { assertEquals(body.equals("profile_pause_rejected") ? body : "profile_pause_helper_failed", expected.getMessage()); }
            assertTrue(child.destroyed);
        }
    }
    @Test public void timeoutTerminatesTheWorker() throws Exception {
        Child child = new Child(0, ""); child.finished = false;
        try { QuietMode.await(child); fail(); }
        catch (IllegalStateException expected) { assertEquals("profile_pause_helper_timeout", expected.getMessage()); }
        assertTrue(child.destroyed);
    }
}
