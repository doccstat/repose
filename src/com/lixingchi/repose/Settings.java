package com.lixingchi.repose;

import org.json.JSONObject;
import java.time.LocalTime;
import java.time.ZoneId;
import java.net.URI;
import java.util.Arrays;
import java.util.List;

/** The persisted schema also drives the settings screen. No rule is enabled on installation. */
public final class Settings {
    public static final class Field {
        public final String key, group, kind;
        public final Object initial;
        public final String[] choices;
        public final int min, max;
        Field(String group, String key, Object initial, String kind, int min, int max, String... choices) {
            this.group = group; this.key = key; this.initial = initial; this.kind = kind;
            this.min = min; this.max = max; this.choices = choices;
        }
    }
    static Field flag(String g, String k) { return new Field(g, k, false, "bool", 0, 0); }
    static Field number(String g, String k, int n, int min, int max) { return new Field(g, k, n, "number", min, max); }
    static Field text(String g, String k, String v) { return new Field(g, k, v, "text", 0, 0); }
    static Field choice(String g, String k, String... v) { return new Field(g, k, v[0], "choice", 0, 0, v); }
    public static final List<Field> FIELDS = Arrays.asList(
        new Field("device", "user", "0:0", "user", 0, 0),
        choice("device", "unlock_scope", "any", "authenticated", "credential"),
        new Field("device", "profile", "none", "profile", 0, 0),
        choice("device", "profile_failure", "abort", "reboot"),
        flag("timer", "timed_enabled"), number("timer", "timed_minutes", 180, 1, 525600),
        new Field("timer", "timed_wait_locked", true, "bool", 0, 0),
        flag("window", "window_enabled"), number("window", "window_minutes", 180, 1, 525600),
        text("window", "window_start", "00:00"), text("window", "window_end", "06:00"),
        text("window", "window_days", "1,2,3,4,5,6,7"), text("window", "timezone", "system"),
        new Field("window", "window_wait_locked", true, "bool", 0, 0),
        number("window", "snooze_minutes", 60, 1, 525600),
        flag("failures", "fail_enabled"), number("failures", "fail_limit", 5, 1, 100000),
        new Field("failures", "failure_user", "0:0", "failure_user", 0, 0),
        choice("failures", "fail_source", "admin", "hook"),
        choice("failures", "fail_biometrics", "none", "fingerprint", "face", "both"),
        choice("failures", "fail_reset", "credential", "unlock"),
        new Field("failures", "fail_boot_reset", true, "bool", 0, 0),
        flag("panic", "panic_enabled"), choice("panic", "panic_type", "pin", "password"),
        choice("remote", "sms_mode", "off", "passphrase", "signed", "both"),
        choice("remote", "inbox_mode", "off", "passphrase", "signed", "both"),
        text("remote", "device_id", ""), number("remote", "command_ttl", 300, 1, 86400),
        text("remote", "inbox_url", ""), number("remote", "inbox_interval", 60, 15, 86400)
    );
    final JSONObject data;
    public Settings(JSONObject supplied) throws Exception {
        data = new JSONObject();
        for (Field f : FIELDS) {
            Object v = supplied.has(f.key) ? supplied.get(f.key) : f.initial;
            boolean valid;
            switch (f.kind) {
                case "bool": valid = v instanceof Boolean; break;
                case "number": valid = v instanceof Number && ((Number)v).doubleValue() == ((Number)v).longValue()
                        && ((Number)v).longValue() >= f.min && ((Number)v).longValue() <= f.max; break;
                case "choice": valid = v instanceof String && Arrays.asList(f.choices).contains(v); break;
                default: valid = v instanceof String && ((String)v).length() <= 2048;
            }
            if (!valid) throw new IllegalArgumentException("invalid_" + f.key);
            data.put(f.key, v);
        }
        identity(s("user")); identity(s("failure_user"));
        if (!s("profile").equals("none")) identity(s("profile"));
        for (String key : Arrays.asList("window_start", "window_end")) {
            if (!s(key).matches("[0-2][0-9]:[0-5][0-9]")) throw new IllegalArgumentException("invalid_" + key);
            LocalTime.parse(s(key));
        }
        if (!s("window_days").matches("[1-7](,[1-7])*")) throw new IllegalArgumentException("invalid_window_days");
        String[] days = s("window_days").split(",");
        if (Arrays.stream(days).distinct().count() != days.length) throw new IllegalArgumentException("invalid_window_days");
        zone();
        if (!s("device_id").isEmpty() && !s("device_id").matches("[A-Za-z0-9_-]{1,64}"))
            throw new IllegalArgumentException("invalid_device_id");
        if (!s("inbox_url").isEmpty()) {
            URI uri = new URI(s("inbox_url"));
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null)
                throw new IllegalArgumentException("invalid_inbox_url");
        }
        if (!s("inbox_mode").equals("off") && s("inbox_url").isEmpty()) throw new IllegalArgumentException("invalid_inbox_url");
    }
    public static int[] identity(String value) {
        if (!value.matches("[0-9]{1,9}:[0-9]{1,9}")) throw new IllegalArgumentException("invalid_identity");
        String[] pair = value.split(":");
        return new int[]{Integer.parseInt(pair[0]), Integer.parseInt(pair[1])};
    }
    public boolean b(String k) { return data.optBoolean(k); }
    public int n(String k) { return data.optInt(k); }
    public String s(String k) { return data.optString(k); }
    public ZoneId zone() { return s("timezone").equals("system") ? ZoneId.systemDefault() : ZoneId.of(s("timezone")); }
    public boolean accepts(String channel, String mode) {
        String selected = s(channel + "_mode");
        return selected.equals(mode) || selected.equals("both");
    }
    public JSONObject json() throws Exception { return new JSONObject(data.toString()); }
}
