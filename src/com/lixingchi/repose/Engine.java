package com.lixingchi.repose;

import org.json.*;
import java.time.Instant;
import java.time.Duration;
import java.util.concurrent.Executor;

/** One serialized state machine; slow profile/reboot operations never hold its monitor. */
public final class Engine {
    public interface Store { void write(JSONObject value) throws Exception; }
    public interface Device {
        long elapsed();
        Instant wall();
        boolean locked(String identity) throws Exception;
        boolean unlocked(String identity) throws Exception;
        boolean bfu() throws Exception;
        void validate(Settings settings) throws Exception;
        void pause(String profile, String parent) throws Exception;
        void reboot() throws Exception;
    }
    final Store store;
    final Device device;
    final Executor executor;
    JSONObject state;
    volatile Settings config;
    long generation;
    public Engine(JSONObject saved, String boot, Store store, Device device, Executor executor) throws Exception {
        this.store = store; this.device = device; this.executor = executor;
        state = new JSONObject(saved.toString());
        config = new Settings(state.optJSONObject("config") == null ? new JSONObject() : state.getJSONObject("config"));
        if (!boot.equals(state.optString("boot"))) {
            state.put("boot", boot).put("afu", false).put("last_unlock", -1).put("snooze", 0).put("skip", 0)
                .put("auth_at", -1).put("presence_at", -1).put("action", "idle").put("events", new JSONObject()).put("admin_counter", -1);
            if (config.b("fail_boot_reset")) state.put("failures", 0);
        }
        // Pending authentication evidence cannot attest a dismissal after framework restoration.
        state.put("auth_at", -1).put("presence_at", -1);
        commit();
    }
    private void commit() throws Exception { store.write(state); }
    public synchronized void note(String code) throws Exception {
        JSONArray old = state.optJSONArray("log"), log = new JSONArray();
        if (old != null) for (int i = Math.max(0, old.length() - 63); i < old.length(); i++) log.put(old.getString(i));
        log.put(device.wall().toString() + " " + code); state.put("log", log); commit();
    }
    public synchronized void configure(JSONObject input) throws Exception {
        if (state.optString("action").equals("running")) throw new IllegalStateException("action_running");
        Settings candidate = new Settings(input);
        device.validate(candidate);
        JSONObject secrets = state.optJSONObject("secrets") == null ? new JSONObject() : new JSONObject(state.getJSONObject("secrets").toString());
        for (String key : new String[]{"panic_secret", "remote_secret", "public_key", "inbox_token"}) {
            if (!input.has(key)) continue;
            String value = input.getString(key);
            if (value.length() > 4096 || value.indexOf('\0') >= 0) throw new IllegalArgumentException("invalid_secret");
            if (key.endsWith("secret") && !value.isEmpty()) {
                if (key.equals("panic_secret")) {
                    if (candidate.s("panic_type").equals("pin") && !value.matches("[0-9]{4,256}"))
                        throw new IllegalArgumentException("invalid_panic_pin");
                    if (candidate.s("panic_type").equals("password") && !value.matches("[ -~]{4,256}"))
                        throw new IllegalArgumentException("invalid_panic_password");
                }
                value = Crypto.verifier(value);
            }
            if (key.equals("public_key") && !value.isEmpty()) Crypto.publicKey(value);
            if (key.equals("inbox_token") && (value.contains("\n") || value.contains("\r"))) throw new IllegalArgumentException("invalid_secret");
            secrets.put(key, value);
        }
        if (candidate.b("panic_enabled") && secrets.optString("panic_secret").isEmpty()) throw new IllegalArgumentException("missing_panic_secret");
        for (String channel : new String[]{"sms", "inbox"}) {
            if (candidate.accepts(channel, "passphrase") && secrets.optString("remote_secret").isEmpty()) throw new IllegalArgumentException("missing_remote_secret");
            if (candidate.accepts(channel, "signed") && (secrets.optString("public_key").isEmpty() || candidate.s("device_id").isEmpty()))
                throw new IllegalArgumentException("missing_public_key");
        }
        if (!candidate.s("inbox_mode").equals("off") && secrets.optString("inbox_token").isEmpty()) throw new IllegalArgumentException("missing_inbox_token");
        if (!candidate.s("panic_type").equals(config.s("panic_type")) && !input.has("panic_secret") && candidate.b("panic_enabled"))
            throw new IllegalArgumentException("missing_panic_secret");
        JSONObject next = new JSONObject(state.toString());
        if (!candidate.s("user").equals(config.s("user")) || !candidate.s("unlock_scope").equals(config.s("unlock_scope")))
            next.put("last_unlock", -1).put("auth_at", -1).put("presence_at", -1);
        if (!candidate.s("failure_user").equals(config.s("failure_user")) || !candidate.s("fail_source").equals(config.s("fail_source"))
                || !candidate.s("fail_biometrics").equals(config.s("fail_biometrics")) || !candidate.b("fail_enabled"))
            next.put("failures", 0).put("admin_counter", -1);
        next.put("config", candidate.json()).put("secrets", secrets);
        store.write(next);
        state = next; config = candidate; generation++;
        note("settings_saved");
    }
    public synchronized void event(String kind, int user, long stamp, long sequence, String boot, String provider, int counter) throws Exception {
        long now = device.elapsed();
        if (!state.getString("boot").equals(boot) || stamp > now || now - stamp > 10000 || sequence < 0) return;
        JSONObject events = state.optJSONObject("events");
        if (events == null) events = new JSONObject();
        String key = provider + ":" + kind + ":" + user;
        if (sequence <= events.optLong(key, -1)) return;
        events.put(key, sequence); state.put("events", events);
        int monitored = Settings.identity(config.s("user"))[0], failureUser = Settings.identity(config.s("failure_user"))[0];
        if (user == monitored && (kind.equals("credential") || kind.equals("biometric"))) {
            state.put("auth_at", stamp).put("auth_kind", kind);
        }
        if (user == failureUser && kind.equals("password_success")
                && (config.s("fail_reset").equals("credential") || !device.locked(config.s("failure_user")))) {
            state.put("failures", 0).put("admin_counter", -1);
        }
        boolean failure = kind.equals("password_failure") && provider.equals(config.s("fail_source"));
        if ((kind.equals("fingerprint") || kind.equals("face")) && provider.equals("hook"))
            failure = Policy.biometricAccepted(config, kind);
        if (failure && user == failureUser && config.b("fail_enabled")) {
            if (provider.equals("admin")) {
                if (counter <= 0) { commit(); return; }
                state.put("admin_counter", counter);
            }
            state.put("failures", Math.min(1000000, state.optInt("failures") + 1));
            note("unlock_failed");
            if (state.optInt("failures") >= config.n("fail_limit")) request("failures");
        }
        commit();
        if (user == monitored && (kind.equals("credential") || kind.equals("biometric"))) creditPresence();
    }
    public synchronized void presence(int user) throws Exception {
        if (user == Settings.identity(config.s("failure_user"))[0] && config.s("fail_reset").equals("unlock")
                && !device.locked(config.s("failure_user")) && device.unlocked(config.s("failure_user"))) {
            state.put("failures", 0).put("admin_counter", -1); commit();
        }
        if (Settings.identity(config.s("user"))[0] != user || device.locked(config.s("user")) || !device.unlocked(config.s("user"))) return;
        state.put("presence_at", device.elapsed());
        creditPresence(); commit();
    }
    private void creditPresence() throws Exception {
        long present = state.optLong("presence_at", -1), now = device.elapsed(), at = state.optLong("auth_at", -1);
        if (present < 0 || now < present || now - present > 10000 || device.locked(config.s("user")) || !device.unlocked(config.s("user"))) return;
        String auth = at >= 0 && Math.abs(present - at) <= 10000 ? state.optString("auth_kind") : "trust";
        if (Policy.unlockAccepted(config.s("unlock_scope"), auth)) {
            state.put("afu", true).put("last_unlock", present).put("presence_at", -1).put("auth_at", -1);
            generation++; note("unlock_observed");
        }
    }
    public synchronized void snooze(int minutes) throws Exception {
        if (minutes < 1 || minutes > 525600) throw new IllegalArgumentException("invalid_snooze");
        state.put("snooze", device.elapsed() + minutes * 60000L); generation++; note("snoozed");
    }
    public synchronized void skip() throws Exception {
        state.put("skip", device.elapsed() + Duration.between(device.wall(), Policy.skipThrough(config, device.wall())).toMillis());
        generation++; note("window_skipped");
    }
    public synchronized void resume() throws Exception {
        state.put("snooze", 0).put("skip", 0); generation++; note("resumed");
    }
    public synchronized void acknowledge() throws Exception {
        if (state.optString("action").equals("running")) throw new IllegalStateException("action_running");
        state.put("action", "idle").put("failures", 0).put("admin_counter", -1); generation++; note("acknowledged");
    }
    public synchronized boolean panic(String value, int user, String type) throws Exception {
        if (!config.b("panic_enabled") || user != Settings.identity(config.s("user"))[0] || !type.equals(config.s("panic_type"))
                || !device.locked(config.s("user"))) return false;
        if (!Crypto.matches(value, secret("panic_secret"))) return false;
        request("panic"); return true;
    }
    public synchronized boolean remote(String channel, String message) throws Exception {
        if (!channel.equals("sms") && !channel.equals("inbox")) throw new IllegalArgumentException("invalid_channel");
        if (message.length() > 4096) return false;
        if (config.accepts(channel, "passphrase") && Crypto.matches(message, secret("remote_secret"))) {
            request(channel); return true;
        }
        if (!config.accepts(channel, "signed") || !message.startsWith("R1|")) return false;
        Crypto.Command command = Crypto.verify(message, config.s("device_id"), secret("public_key"), device.wall().getEpochSecond(), config.n("command_ttl"));
        JSONObject old = state.optJSONObject("replay");
        JSONObject reserved = Crypto.reserve(old == null ? new JSONObject() : old, command, device.wall().getEpochSecond());
        JSONObject next = new JSONObject(state.toString()).put("replay", reserved);
        store.write(next); state = next;
        request(channel); return true;
    }
    synchronized String secret(String key) { return state.optJSONObject("secrets") == null ? "" : state.optJSONObject("secrets").optString(key); }
    private boolean bfu() throws Exception {
        if (state.optBoolean("afu")) return false;
        if (device.bfu()) return true;
        state.put("afu", true); commit(); return false;
    }
    private synchronized String due() throws Exception {
        if (bfu()) return "";
        return Policy.due(config, state.optLong("last_unlock", -1), device.elapsed(), device.wall(), device.locked(config.s("user")),
                state.optLong("snooze"), state.optLong("skip"));
    }
    public synchronized long tick() throws Exception {
        if (!state.optString("action", "idle").equals("idle") || bfu()) return Long.MAX_VALUE;
        String trigger = due();
        if (!trigger.isEmpty()) { request(trigger); return Long.MAX_VALUE; }
        return Policy.next(config, state.optLong("last_unlock", -1), device.elapsed(), device.wall(), device.locked(config.s("user")),
                state.optLong("snooze"), state.optLong("skip"));
    }
    private synchronized void request(String trigger) throws Exception {
        String action = state.optString("action", "idle");
        if (action.equals("running") && !timed(trigger) && timed(state.optString("trigger"))) {
            state.put("trigger", trigger); note("security_trigger_priority"); return;
        }
        if (!action.equals("idle")) return;
        // Already BFU: never create an automatic reboot loop before the first storage unlock.
        if (bfu()) { note("already_bfu"); return; }
        state.put("action", "running").put("trigger", trigger); commit();
        final long revision = generation;
        final Settings selected = config;
        executor.execute(() -> execute(trigger, revision, selected));
    }
    private static boolean timed(String trigger) { return trigger.equals("timed") || trigger.equals("window"); }
    private boolean eligible(String trigger, long revision) throws Exception {
        synchronized (this) {
            String effective = state.optString("trigger", trigger);
            boolean valid = !timed(effective) || generation == revision && effective.equals(due());
            if (!valid) { state.put("action", "idle"); note("action_cancelled"); }
            return valid;
        }
    }
    private void execute(String trigger, long revision, Settings selected) {
        try {
            if (!eligible(trigger, revision)) return;
            if (!selected.s("profile").equals("none")) {
                try { device.pause(selected.s("profile"), selected.s("user")); note("profile_paused"); }
                catch (Exception error) {
                    note("profile_pause_failed");
                    if (selected.s("profile_failure").equals("abort")) throw error;
                }
            }
            synchronized (this) {
                if (!eligible(trigger, revision)) return;
                state.put("action", "reboot_requested"); note("reboot_requested");
            }
            device.reboot();
        } catch (Exception error) {
            synchronized (this) {
                try { state.put("action", "failed"); note("action_failed_" + error.getClass().getSimpleName()); }
                catch (Exception persistenceFailure) { throw new IllegalStateException("state_write_failed", persistenceFailure); }
            }
        }
    }
    public synchronized JSONObject status() throws Exception {
        JSONObject c = config.json();
        for (String key : new String[]{"panic_secret", "remote_secret", "public_key", "inbox_token"}) c.put("has_" + key, !secret(key).isEmpty());
        return new JSONObject().put("config", c).put("action", state.optString("action", "idle"))
            .put("failures", state.optInt("failures")).put("armed", state.optLong("last_unlock", -1) >= 0)
            .put("snoozed", device.elapsed() < state.optLong("snooze"))
            .put("skipped", device.elapsed() < state.optLong("skip"))
            .put("log", state.optJSONArray("log") == null ? new JSONArray() : state.getJSONArray("log"));
    }
}
