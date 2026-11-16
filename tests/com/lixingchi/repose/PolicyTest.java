package com.lixingchi.repose;

import org.json.JSONObject;
import org.junit.Test;
import java.time.*;
import static org.junit.Assert.*;

public class PolicyTest {
    Settings settings(Object... values) throws Exception {
        JSONObject object = new JSONObject();
        for (int i = 0; i < values.length; i += 2) object.put((String)values[i], values[i + 1]);
        return new Settings(object);
    }
    @Test public void allRulesDefaultOff() throws Exception {
        Settings c = settings();
        assertFalse(c.b("timed_enabled")); assertFalse(c.b("window_enabled")); assertFalse(c.b("fail_enabled")); assertFalse(c.b("panic_enabled"));
        assertEquals("off", c.s("sms_mode")); assertEquals("off", c.s("inbox_mode"));
    }
    @Test public void elapsedIncludesSuspendAndIgnoresWallChanges() throws Exception {
        Settings c = settings("timed_enabled", true, "timed_minutes", 3);
        assertEquals("", Policy.due(c, 100, 180099, Instant.EPOCH, true, 0, 0));
        assertEquals("timed", Policy.due(c, 100, 180100, Instant.ofEpochSecond(-5000), true, 0, 0));
        assertEquals(180100, Policy.next(c, 100, 1000, Instant.EPOCH, true, 0, 0));
    }
    @Test public void noUnlockMeansNoDeadline() throws Exception {
        Settings c = settings("timed_enabled", true);
        assertEquals("", Policy.due(c, -1, 99999999, Instant.EPOCH, true, 0, 0));
        assertEquals(Long.MAX_VALUE, Policy.next(c, -1, 9999, Instant.EPOCH, true, 0, 0));
    }
    @Test public void unlockedDeferralIsExplicit() throws Exception {
        Settings c = settings("timed_enabled", true, "timed_minutes", 1);
        assertEquals("", Policy.due(c, 0, 60000, Instant.EPOCH, false, 0, 0));
        assertEquals(Long.MAX_VALUE, Policy.next(c, 0, 60000, Instant.EPOCH, false, 0, 0));
        c = settings("timed_enabled", true, "timed_minutes", 1, "timed_wait_locked", false);
        assertEquals("timed", Policy.due(c, 0, 60000, Instant.EPOCH, false, 0, 0));
    }
    @Test public void overnightWindowUsesStartDay() throws Exception {
        Settings c = settings("timezone", "UTC", "window_start", "22:00", "window_end", "06:00", "window_days", "1");
        assertTrue(Policy.inWindow(c, Instant.parse("2026-11-17T05:59:59Z")));
        assertFalse(Policy.inWindow(c, Instant.parse("2026-11-17T06:00:00Z")));
        assertFalse(Policy.inWindow(c, Instant.parse("2026-11-16T05:59:59Z")));
    }
    @Test public void fullDayWindowAndSkip() throws Exception {
        Settings c = settings("timezone", "UTC", "window_start", "06:00", "window_end", "06:00", "window_days", "1");
        assertTrue(Policy.inWindow(c, Instant.parse("2026-11-16T07:00:00Z")));
        assertTrue(Policy.inWindow(c, Instant.parse("2026-11-17T05:00:00Z")));
        assertEquals(Instant.parse("2026-11-17T06:00:00Z"), Policy.skipThrough(c, Instant.parse("2026-11-16T07:00:00Z")));
        assertEquals(Instant.parse("2026-11-17T06:00:00Z"), Policy.skipThrough(c, Instant.parse("2026-11-15T12:00:00Z")));
    }
    @Test public void dstOpeningResolvesNonexistentLocalTime() throws Exception {
        Settings c = settings("timezone", "America/New_York", "window_start", "02:30", "window_end", "04:00");
        assertEquals(Instant.parse("2026-03-08T07:30:00Z"), Policy.nextOpening(c, Instant.parse("2026-03-08T06:00:00Z")));
        assertFalse(Policy.inWindow(c, Instant.parse("2026-03-08T07:15:00Z")));
        assertTrue(Policy.inWindow(c, Instant.parse("2026-03-08T07:30:00Z")));
        c = settings("timezone", "America/New_York", "window_start", "02:30", "window_end", "03:15");
        assertEquals(Instant.parse("2026-03-09T06:30:00Z"), Policy.nextOpening(c, Instant.parse("2026-03-08T06:00:00Z")));
    }
    @Test public void snoozeBothButSkipWindowOnly() throws Exception {
        Settings c = settings("timed_enabled", true, "timed_minutes", 1, "window_enabled", true, "window_minutes", 1, "timezone", "UTC");
        Instant wall = Instant.parse("2026-11-16T03:00:00Z");
        assertEquals("", Policy.due(c, 0, 60000, wall, true, 60001, 0));
        assertEquals("timed", Policy.due(c, 0, 60000, wall, true, 0, 60001));
    }
    @Test public void nextDeadlineWaitsForWindow() throws Exception {
        Settings c = settings("window_enabled", true, "window_minutes", 1, "timezone", "UTC", "window_start", "22:00", "window_end", "06:00");
        Instant wall = Instant.parse("2026-11-16T21:00:00Z");
        assertEquals(3600000 + 100000, Policy.next(c, 0, 100000, wall, true, 0, 0));
    }
    @Test public void authenticationScopesAreDistinct() {
        assertTrue(Policy.unlockAccepted("any", "trust"));
        assertFalse(Policy.unlockAccepted("authenticated", "trust"));
        assertTrue(Policy.unlockAccepted("authenticated", "biometric"));
        assertFalse(Policy.unlockAccepted("credential", "biometric"));
    }
    @Test public void rejectsInvalidNumbersDatesIdentityAndUrls() throws Exception {
        for (Object[] bad : new Object[][]{{"timed_minutes", 0}, {"fail_limit", -1}, {"timed_minutes", 1.5}, {"timed_enabled", "true"},
                {"window_start", "25:00"}, {"window_start", " 01:00"}, {"window_days", "1,1"}, {"timezone", "typo"}, {"user", "0:0;reboot"},
                {"inbox_url", "http://example.com"}, {"inbox_url", "https://secret@example.com"}, {"device_id", " a "}}) {
            try { settings(bad); fail("accepted " + bad[0]); } catch (Exception expected) { }
        }
    }
}
