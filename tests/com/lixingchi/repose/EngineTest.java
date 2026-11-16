package com.lixingchi.repose;

import org.json.*;
import org.junit.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.Assert.*;

public class EngineTest {
    static class Harness implements Engine.Device {
        long now = 100000, sequence;
        boolean locked = true, unlocked = true, pauseFails, writeFails, bfu;
        int reboots, pauses;
        JSONObject saved = new JSONObject();
        Queue<Runnable> actions = new ArrayDeque<>();
        Engine engine;
        Runnable duringPause;
        Harness() throws Exception { restart("boot-a"); }
        void restart(String boot) throws Exception { engine = new Engine(saved, boot, value -> {
            if (writeFails) throw new java.io.IOException("disk_full");
            saved = new JSONObject(value.toString());
        }, this, actions::add); }
        void configure(Object... fields) throws Exception {
            JSONObject c = engine.config.json();
            for (int i = 0; i < fields.length; i += 2) c.put((String)fields[i], fields[i + 1]);
            engine.configure(c);
        }
        void event(String kind, String provider, int user, int counter) throws Exception {
            engine.event(kind, user, now, ++sequence, "boot-a", provider, counter);
        }
        void unlock() throws Exception { locked = false; engine.presence(0); locked = true; }
        void execute() { while (!actions.isEmpty()) actions.remove().run(); }
        public long elapsed() { return now; }
        public Instant wall() { return Instant.parse("2026-11-16T12:00:00Z").plusMillis(now); }
        public boolean locked(String id) { return locked; }
        public boolean unlocked(String id) { return unlocked; }
        public boolean bfu() { return bfu; }
        public void validate(Settings c) { }
        public void pause(String id, String parent) { pauses++; if (duringPause != null) duringPause.run(); if (pauseFails) throw new IllegalStateException("pause_failed"); }
        public void reboot() { reboots++; }
    }
    @Test public void timedActionRechecksLockBeforeReboot() throws Exception {
        Harness h = new Harness(); h.configure("timed_enabled", true, "timed_minutes", 1); h.unlock(); h.now += 60000;
        h.engine.tick(); h.locked = false; h.execute();
        assertEquals(0, h.reboots); assertEquals("idle", h.engine.status().getString("action"));
    }
    @Test public void unlockDuringPauseCancelsTimedReboot() throws Exception {
        Harness h = new Harness(); h.configure("timed_enabled", true, "timed_minutes", 1, "profile", "10:12"); h.unlock(); h.now += 60000;
        h.duringPause = () -> { try { h.unlock(); } catch (Exception e) { throw new RuntimeException(e); } };
        h.engine.tick(); h.execute(); assertEquals(1, h.pauses); assertEquals(0, h.reboots);
    }
    @Test public void securityTriggerIgnoresSnoozeWindowAndUnlockedState() throws Exception {
        Harness h = new Harness(); h.configure("fail_enabled", true, "fail_limit", 2); h.engine.snooze(999); h.locked = false;
        h.event("password_failure", "admin", 0, 1); h.event("password_failure", "admin", 0, 2); h.engine.presence(0); h.execute();
        assertEquals(1, h.reboots);
    }
    @Test public void securityTriggerEscalatesQueuedTimedActionDespiteSnooze() throws Exception {
        Harness h = new Harness(); h.configure("timed_enabled", true, "timed_minutes", 1, "fail_enabled", true, "fail_limit", 1);
        h.unlock(); h.now += 60000; h.engine.tick(); h.engine.snooze(60);
        h.event("password_failure", "admin", 0, 1); h.locked = false; h.execute();
        assertEquals(1, h.reboots);
    }
    @Test public void securityTriggerDuringProfilePauseSurvivesUnlock() throws Exception {
        Harness h = new Harness(); h.configure("timed_enabled", true, "timed_minutes", 1, "profile", "10:12", "fail_enabled", true, "fail_limit", 1);
        h.unlock(); h.now += 60000;
        h.duringPause = () -> {
            try { h.unlock(); h.event("password_failure", "admin", 0, 1); }
            catch (Exception error) { throw new RuntimeException(error); }
        };
        h.engine.tick(); h.execute(); assertEquals(1, h.reboots);
    }
    @Test public void countsEventsNotRepeatedCounterObservations() throws Exception {
        Harness h = new Harness(); h.configure("fail_enabled", true, "fail_limit", 3);
        h.event("password_failure", "admin", 0, 3); h.event("password_failure", "admin", 0, 3); h.event("password_failure", "admin", 0, 3); h.execute();
        assertEquals(1, h.reboots);
    }
    @Test public void eventReplayAndWrongSourceAndUserDoNotCount() throws Exception {
        Harness h = new Harness(); h.configure("fail_enabled", true, "fail_limit", 2);
        h.engine.event("password_failure", 0, h.now, 1, "boot-a", "admin", 1);
        h.engine.event("password_failure", 0, h.now, 1, "boot-a", "admin", 1);
        h.event("password_failure", "hook", 0, 1); h.event("password_failure", "admin", 10, 2);
        h.engine.event("password_failure", 0, h.now, 999, "old-boot", "admin", 2);
        h.execute(); assertEquals(1, h.engine.status().getInt("failures")); assertEquals(0, h.reboots);
    }
    @Test public void staleEventsAndFutureEventsAreRejected() throws Exception {
        Harness h = new Harness(); h.configure("fail_enabled", true, "fail_limit", 1);
        h.engine.event("password_failure", 0, h.now - 10001, 1, "boot-a", "admin", 1);
        h.engine.event("password_failure", 0, h.now + 1, 2, "boot-a", "admin", 1);
        assertTrue(h.actions.isEmpty());
    }
    @Test public void credentialCounterResetAndBiometricChoice() throws Exception {
        Harness h = new Harness(); h.configure("fail_enabled", true, "fail_limit", 2, "fail_biometrics", "fingerprint");
        h.event("face", "hook", 0, 0); h.event("fingerprint", "hook", 0, 0);
        assertEquals(1, h.engine.status().getInt("failures"));
        h.event("password_success", "admin", 0, 0); assertEquals(0, h.engine.status().getInt("failures"));
    }
    @Test public void otherProfileUnlockResetsItsCounter() throws Exception {
        Harness h = new Harness(); h.configure("failure_user", "10:12", "fail_enabled", true, "fail_reset", "unlock");
        h.event("password_failure", "admin", 10, 1); h.locked = false; h.engine.presence(10);
        assertEquals(0, h.engine.status().getInt("failures"));
    }
    @Test public void frameworkRestartPreservesDeadlineAndCounterButBootClearsArming() throws Exception {
        Harness h = new Harness(); h.configure("timed_enabled", true, "fail_enabled", true, "timed_minutes", 1); h.unlock();
        h.event("password_failure", "admin", 0, 1); h.restart("boot-a");
        assertTrue(h.engine.status().getBoolean("armed")); assertEquals(1, h.engine.status().getInt("failures"));
        h.now += 60000; h.engine.tick(); h.execute(); assertEquals(1, h.reboots);
        h.restart("boot-b"); assertFalse(h.engine.status().getBoolean("armed")); assertEquals(0, h.engine.status().getInt("failures"));
    }
    @Test public void failureBootResetCanBeDisabled() throws Exception {
        Harness h = new Harness(); h.configure("fail_enabled", true, "fail_boot_reset", false);
        h.event("password_failure", "admin", 0, 1); h.restart("boot-b"); assertEquals(1, h.engine.status().getInt("failures"));
    }
    @Test public void bfuNeverRebootsAndPersistentLatchPreventsReplay() throws Exception {
        Harness h = new Harness(); h.configure("fail_enabled", true, "fail_limit", 1); h.bfu = true;
        h.event("password_failure", "admin", 0, 1); h.execute(); assertEquals(0, h.reboots);
        h.bfu = false; h.event("password_failure", "admin", 0, 2); h.execute(); assertEquals(1, h.reboots);
        h.restart("boot-a"); h.event("password_failure", "admin", 0, 3); h.execute(); assertEquals(1, h.reboots);
    }
    @Test public void bfuDisarmsExpiredWakeDeadline() throws Exception {
        Harness h = new Harness(); h.configure("timed_enabled", true, "timed_minutes", 1); h.unlock(); h.now += 60000;
        h.bfu = true; h.restart("boot-b");
        assertEquals(Long.MAX_VALUE, h.engine.tick()); assertTrue(h.actions.isEmpty());
    }
    @Test public void frameworkRestorationCannotEraseBootScopedAfuEvidence() throws Exception {
        Harness h = new Harness(); h.configure("sms_mode", "passphrase", "remote_secret", "exact secret"); h.unlock();
        h.unlocked = false; h.bfu = true; h.restart("boot-a");
        assertTrue(h.engine.remote("sms", "exact secret")); h.execute(); assertEquals(1, h.reboots);
    }
    @Test public void lockedSelectedUserDoesNotSuppressPanicWhenAnotherUserIsAfu() throws Exception {
        Harness h = new Harness(); h.configure("sms_mode", "passphrase", "remote_secret", "exact secret");
        h.unlocked = false; h.bfu = false;
        assertTrue(h.engine.remote("sms", "exact secret")); h.execute(); assertEquals(1, h.reboots);
    }
    @Test public void profileFailureChoiceIsHonored() throws Exception {
        for (String choice : new String[]{"abort", "reboot"}) {
            Harness h = new Harness(); h.configure("fail_enabled", true, "fail_limit", 1, "profile", "10:12", "profile_failure", choice); h.pauseFails = true;
            h.event("password_failure", "admin", 0, 1); h.execute();
            assertEquals(choice.equals("abort") ? 0 : 1, h.reboots);
        }
    }
    @Test public void storageFailureNeverExecutes() throws Exception {
        Harness h = new Harness(); h.configure("fail_enabled", true, "fail_limit", 1); h.writeFails = true;
        try { h.event("password_failure", "admin", 0, 1); fail(); } catch (java.io.IOException expected) { }
        h.execute(); assertEquals(0, h.reboots);
    }
    @Test public void credentialScopeNeedsFreshAuthenticationNotStorageRestore() throws Exception {
        Harness h = new Harness(); h.configure("unlock_scope", "credential"); h.unlock(); assertFalse(h.engine.status().getBoolean("armed"));
        h.locked = false; h.event("credential", "hook", 0, 0); assertTrue(h.engine.status().getBoolean("armed"));
    }
    @Test public void authenticationWithoutLockscreenDismissalDoesNotResetTimer() throws Exception {
        Harness h = new Harness(); h.configure("unlock_scope", "credential"); h.locked = false;
        h.event("credential", "hook", 0, 0); assertFalse(h.engine.status().getBoolean("armed"));
        h.engine.presence(0); assertTrue(h.engine.status().getBoolean("armed"));
    }
    @Test public void frameworkRestartClearsPendingAuthenticationEvidence() throws Exception {
        Harness h = new Harness(); h.configure("unlock_scope", "credential");
        h.event("credential", "hook", 0, 0); h.restart("boot-a"); h.unlock();
        assertFalse(h.engine.status().getBoolean("armed"));
    }
    @Test public void rejectsNonAsciiPanicPasswordWithoutTransformingIt() throws Exception {
        Harness h = new Harness();
        try { h.configure("panic_type", "password", "panic_secret", "密码密码"); fail(); } catch (IllegalArgumentException expected) { }
        assertEquals("pin", h.engine.config.s("panic_type"));
    }
    @Test public void clearingSecretRequiresDisablingDependentMode() throws Exception {
        Harness h = new Harness(); h.configure("sms_mode", "passphrase", "remote_secret", "exact passphrase");
        try { h.configure("remote_secret", ""); fail(); } catch (IllegalArgumentException expected) { }
        assertTrue(h.engine.status().getJSONObject("config").getBoolean("has_remote_secret"));
        h.configure("sms_mode", "off", "remote_secret", "");
        assertFalse(h.engine.status().getJSONObject("config").getBoolean("has_remote_secret"));
    }
    @Test public void passphraseAndPanicAreVerbatimAndNeverInDiagnostics() throws Exception {
        Harness h = new Harness();
        h.configure("sms_mode", "both", "remote_secret", "  löck 🌙  ", "device_id", "test", "public_key", CryptoTest.pem(CryptoTest.keys().getPublic()),
                "panic_enabled", true, "panic_type", "password", "panic_secret", " panic password ");
        assertFalse(h.engine.panic(" panic password ", 0, "pin"));
        assertFalse(h.engine.panic("panic password", 0, "password"));
        assertTrue(h.engine.panic(" panic password ", 0, "password")); h.execute(); assertEquals(1, h.reboots);
        h.engine.acknowledge(); assertTrue(h.engine.remote("sms", "  löck 🌙  ")); h.execute(); assertEquals(2, h.reboots);
        String exported = h.engine.status().toString();
        assertFalse(exported.contains("panic password")); assertFalse(exported.contains("löck"));
        assertFalse(h.saved.getJSONObject("secrets").toString().contains("password"));
    }
}
