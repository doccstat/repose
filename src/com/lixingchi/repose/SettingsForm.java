package com.lixingchi.repose;

import android.Manifest;
import android.app.TimePickerDialog;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.text.*;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import com.google.android.material.chip.*;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.*;
import java.time.*;
import java.time.format.TextStyle;
import java.time.temporal.WeekFields;
import java.util.*;
import org.json.*;

/** A local draft: editing, pickers and permission requests never configure the engine. */
final class SettingsForm {
    static final List<String> SECRETS = Arrays.asList("panic_secret", "remote_secret", "public_key", "inbox_token");
    static JSONObject copy(JSONObject object) {
        try { return new JSONObject(object.toString()); } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }
    static final class State {
        final String group;
        final JSONObject original, draft;
        final Set<String> cleared = new HashSet<>();
        final Set<String> reenter = new HashSet<>();
        int scroll;
        State(String group, JSONObject config) { this.group = group; original = copy(config); draft = copy(config); }
        JSONObject saved() {
            JSONObject safe = copy(draft);
            Set<String> pending = new HashSet<>(reenter);
            for (String key : SECRETS) { if (!safe.optString(key).isEmpty() && !cleared.contains(key)) pending.add(key); safe.remove(key); }
            try { return new JSONObject().put("group", group).put("original", original).put("draft", safe).put("cleared", new JSONArray(cleared)).put("reenter", new JSONArray(pending)).put("scroll", scroll); }
            catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        }
        static State restore(JSONObject saved) throws JSONException {
            State state = new State(saved.getString("group"), saved.getJSONObject("original"));
            JSONObject draft = saved.getJSONObject("draft");
            for (Iterator<String> keys = draft.keys(); keys.hasNext();) { String key = keys.next(); if (!SECRETS.contains(key)) state.draft.put(key, draft.get(key)); }
            JSONArray cleared = saved.optJSONArray("cleared");
            if (cleared != null) for (int i = 0; i < cleared.length(); i++) if (SECRETS.contains(cleared.optString(i))) state.cleared.add(cleared.getString(i));
            JSONArray pending = saved.optJSONArray("reenter");
            if (pending != null) for (int i = 0; i < pending.length(); i++) if (SECRETS.contains(pending.optString(i))) state.reenter.add(pending.getString(i));
            state.scroll = saved.optInt("scroll"); return state;
        }
    }
    static final class Invalid extends Exception {
        final String key;
        Invalid(String key, String message) { super(message); this.key = key; }
    }
    final MainActivity activity;
    final State state;
    final Map<String, View> fields = new LinkedHashMap<>(), blocks = new LinkedHashMap<>();
    final Map<String, TextInputLayout> inputs = new HashMap<>();
    final Map<String, Button> clearButtons = new HashMap<>();
    LinearLayout container, requirements;
    TextView error, scheduleNote, dayError;
    Button keepSaved;
    SettingsForm(MainActivity activity, State state) { this.activity = activity; this.state = state; }
    String s(String key) { return state.draft.optString(key); }
    void put(String key, Object value) {
        try { state.draft.put(key, value); } catch (JSONException impossible) { throw new IllegalArgumentException(impossible); }
    }
    void build(LinearLayout target) {
        container = target;
        error = activity.label(""); error.setTextColor(MaterialColors.getColor(error, androidx.appcompat.R.attr.colorError));
        error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); error.setVisibility(View.GONE); target.addView(error);
        keepSaved = activity.button(target, R.string.keep_saved_credentials, false, () -> {
            for (String key : state.reenter) if (inputs.containsKey(key)) inputs.get(key).setError(null);
            state.reenter.clear(); error.setVisibility(View.GONE); update();
        });
        requirements = new LinearLayout(activity); requirements.setOrientation(LinearLayout.VERTICAL); target.addView(requirements);
        if (state.group.equals("timer") || state.group.equals("window")) target.addView(activity.label(activity.getString(R.string.timer_description)));
        if (state.group.equals("failures")) target.addView(activity.label(activity.getString(R.string.failures_description)));
        if (state.group.equals("panic")) target.addView(activity.label(activity.getString(R.string.panic_description)));
        for (Settings.Field field : Settings.FIELDS) {
            if (!field.group.equals(state.group)) continue;
            String key = field.key, title = activity.text("f_" + key);
            View control;
            if (field.kind.equals("bool")) {
                MaterialSwitch toggle = new MaterialSwitch(activity); toggle.setText(title); toggle.setChecked(state.draft.optBoolean(key));
                toggle.setMinHeight(activity.dp(64)); target.addView(toggle, new LinearLayout.LayoutParams(-1, -2));
                toggle.setOnCheckedChangeListener((button, checked) -> { put(key, checked); update(); }); control = toggle;
            } else if (key.equals("window_days")) {
                TextView label = activity.label(title); target.addView(label);
                ChipGroup chips = new ChipGroup(activity); chips.setChipSpacingHorizontal(activity.dp(4));
                int first = WeekFields.of(activity.locale()).getFirstDayOfWeek().getValue();
                for (int i = 0; i < 7; i++) {
                    int day = (first - 1 + i) % 7 + 1;
                    Chip chip = new Chip(activity); chip.setCheckable(true); chip.setEnsureMinTouchTargetSize(true);
                    chip.setText(DayOfWeek.of(day).getDisplayName(TextStyle.SHORT, activity.locale()));
                    chip.setContentDescription(DayOfWeek.of(day).getDisplayName(TextStyle.FULL, activity.locale()));
                    chip.setTag("day_" + day); chip.setChecked(Arrays.asList(s(key).split(",")).contains(String.valueOf(day)));
                    chip.setOnCheckedChangeListener((button, checked) -> {
                        List<String> days = new ArrayList<>(); if (!s(key).isEmpty()) days.addAll(Arrays.asList(s(key).split(",")));
                        if (checked) days.add(String.valueOf(day)); else days.remove(String.valueOf(day));
                        put(key, String.join(",", days)); dayError.setVisibility(View.GONE);
                    }); chips.addView(chip);
                }
                target.addView(chips); control = chips;
                dayError = activity.label(""); dayError.setTextColor(MaterialColors.getColor(dayError, androidx.appcompat.R.attr.colorError));
                dayError.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); dayError.setVisibility(View.GONE); target.addView(dayError);
            } else if (key.equals("window_start") || key.equals("window_end")) {
                control = activity.new Row(target, title, activity.time(s(key)), () -> {
                    LocalTime time = LocalTime.parse(s(key));
                    new TimePickerDialog(activity, (picker, hour, minute) -> {
                        put(key, String.format(Locale.ROOT, "%02d:%02d", hour, minute)); update();
                    }, time.getHour(), time.getMinute(), android.text.format.DateFormat.is24HourFormat(activity)).show();
                });
            } else if (key.equals("timezone")) {
                control = activity.new Row(target, title, zoneLabel(), this::zonePicker);
            } else if (field.kind.equals("choice") || field.kind.equals("user") || field.kind.equals("profile") || field.kind.equals("failure_user")) {
                control = activity.new Row(target, title, choiceLabel(field), () -> choose(field));
            } else {
                TextInputEditText edit = activity.input(target, title, s(key), field.kind.equals("number") ? InputType.TYPE_CLASS_NUMBER : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                TextInputLayout box = box(edit); inputs.put(key, box); blocks.put(key, (View)box.getParent());
                if (field.kind.equals("number")) {
                    if (key.endsWith("minutes")) box.setSuffixText(activity.getString(R.string.minutes));
                    else if (key.equals("command_ttl") || key.equals("inbox_interval")) box.setSuffixText(activity.getString(R.string.seconds));
                }
                if (key.equals("inbox_url")) edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
                watch(edit, key); control = edit;
            }
            control.setTag(key); fields.put(key, control); blocks.putIfAbsent(key, control);
            if (key.equals("window_end")) { scheduleNote = activity.label(""); target.addView(scheduleNote); }
        }
        String[] secrets = state.group.equals("panic") ? new String[]{"panic_secret"} : state.group.equals("remote") ? new String[]{"remote_secret", "public_key", "inbox_token"} : new String[0];
        for (String key : secrets) {
            LinearLayout block = new LinearLayout(activity); block.setOrientation(LinearLayout.VERTICAL); target.addView(block);
            TextInputEditText edit = activity.input(block, activity.text("f_" + key), s(key), InputType.TYPE_CLASS_TEXT | (key.equals("public_key") ? InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS : InputType.TYPE_TEXT_VARIATION_PASSWORD));
            TextInputLayout box = box(edit); inputs.put(key, box); fields.put(key, edit); blocks.put(key, block); edit.setTag(key);
            edit.setSaveEnabled(false); box.setSaveFromParentEnabled(false);
            if (!key.equals("public_key")) box.setEndIconMode(TextInputLayout.END_ICON_PASSWORD_TOGGLE);
            else edit.setMinLines(3);
            if (state.original.optBoolean("has_" + key)) {
                Button clear = activity.button(block, R.string.remove_saved, false, () -> {
                    if (state.cleared.remove(key)) { update(); return; }
                    new MaterialAlertDialogBuilder(activity).setTitle(activity.getString(R.string.remove_secret, activity.text("f_" + key)))
                        .setMessage(R.string.remove_secret_message).setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.remove_saved, (d, w) -> { state.cleared.add(key); state.reenter.remove(key); update(); }).show();
                }); clearButtons.put(key, clear);
            }
            watch(edit, key);
        }
        update();
        if (!state.reenter.isEmpty()) { error.setText(R.string.reenter_secrets); error.setVisibility(View.VISIBLE); }
    }
    static TextInputLayout box(TextInputEditText edit) { return (TextInputLayout)edit.getParent().getParent(); }
    void watch(TextInputEditText edit, String key) {
        edit.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                put(key, s.toString()); inputs.get(key).setError(null);
                if (s.length() > 0 && state.reenter.remove(key) && state.reenter.isEmpty()) { error.setVisibility(View.GONE); keepSaved.setVisibility(View.GONE); }
            }
            public void afterTextChanged(Editable value) { }
        });
    }
    String zoneLabel() { return s("timezone").equals("system") ? activity.getString(R.string.system_timezone, ZoneId.systemDefault().getId()) : s("timezone"); }
    String choiceLabel(Settings.Field field) {
        if (field.kind.equals("choice")) return activity.text("choice_" + field.key + "_" + s(field.key));
        return s(field.key).equals("none") ? activity.getString(R.string.no_profile) : activity.identityName(s(field.key));
    }
    void choose(Settings.Field field) {
        List<String> values = new ArrayList<>(), labels = new ArrayList<>();
        if (field.kind.equals("choice")) for (String value : field.choices) {
            values.add(value); labels.add(activity.text("choice_" + field.key + "_" + value));
        } else {
            if (field.kind.equals("profile")) { values.add("none"); labels.add(activity.getString(R.string.no_profile)); }
            JSONArray users = activity.status == null ? new JSONArray() : activity.status.optJSONArray("users");
            if (users != null) for (int i = 0; i < users.length(); i++) {
                JSONObject user = users.optJSONObject(i); String type = user.optString("type");
                if (field.kind.equals("user") && type.startsWith("android.os.usertype.profile.")) continue;
                if (field.kind.equals("profile") && (!type.equals("android.os.usertype.profile.MANAGED") || !user.optString("parent").equals(s("user")))) continue;
                values.add(user.optString("identity")); labels.add(activity.getString(R.string.user_label, user.optString("name"), user.optString("identity")));
            }
            if (!values.contains(s(field.key))) { values.add(s(field.key)); labels.add(activity.getString(R.string.missing_user, s(field.key))); }
        }
        new MaterialAlertDialogBuilder(activity).setTitle(activity.text("f_" + field.key))
            .setSingleChoiceItems(labels.toArray(new String[0]), values.indexOf(s(field.key)), (dialog, which) -> {
                put(field.key, values.get(which)); dialog.dismiss(); update();
            }).setNegativeButton(android.R.string.cancel, null).show();
    }
    void zonePicker() {
        LinearLayout panel = activity.column();
        TextInputEditText search = activity.input(panel, activity.getString(R.string.search_timezone), "", InputType.TYPE_CLASS_TEXT);
        List<String> values = new ArrayList<>(ZoneId.getAvailableZoneIds()); Collections.sort(values); values.add(0, "system");
        List<String> shown = new ArrayList<>(values);
        search.setTag("timezone_search");
        ListView list = new ListView(activity); panel.addView(list, new LinearLayout.LayoutParams(-1, activity.dp(280)));
        TextView empty = activity.label(activity.getString(R.string.no_timezone_results)); panel.addView(empty); list.setEmptyView(empty);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(activity, android.R.layout.simple_list_item_1, new ArrayList<>());
        list.setAdapter(adapter);
        Runnable filter = () -> {
            String query = search.getText().toString().toLowerCase(activity.locale()); shown.clear(); adapter.clear();
            for (String value : values) {
                String label = value.equals("system") ? activity.getString(R.string.system_timezone, ZoneId.systemDefault().getId()) : value;
                if (label.toLowerCase(activity.locale()).contains(query)) { shown.add(value); adapter.add(label); }
            }
        }; filter.run();
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) { filter.run(); }
            public void afterTextChanged(Editable value) { }
        });
        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(activity).setTitle(R.string.f_timezone).setView(panel).setNegativeButton(android.R.string.cancel, null).create();
        list.setOnItemClickListener((parent, view, position, id) -> { put("timezone", shown.get(position)); dialog.dismiss(); update(); }); dialog.show();
    }
    boolean mode(String mode) { return s("sms_mode").equals(mode) || s("sms_mode").equals("both") || s("inbox_mode").equals(mode) || s("inbox_mode").equals("both"); }
    boolean visible(String key) {
        if (key.equals("profile_failure")) return !s("profile").equals("none");
        if (key.equals("device_id") || key.equals("command_ttl")) return mode("signed");
        if (key.equals("inbox_url") || key.equals("inbox_interval")) return !s("inbox_mode").equals("off");
        if (key.equals("public_key")) return mode("signed") || state.original.optBoolean("has_public_key");
        if (key.equals("remote_secret")) return mode("passphrase") || state.original.optBoolean("has_remote_secret");
        if (key.equals("inbox_token")) return !s("inbox_mode").equals("off") || state.original.optBoolean("has_inbox_token");
        return true;
    }
    void update() {
        if (container == null) return;
        for (Settings.Field field : Settings.FIELDS) {
            View view = fields.get(field.key);
            if (view instanceof MainActivity.Row) {
                MainActivity.Row row = (MainActivity.Row)view;
                row.value(field.key.equals("timezone") ? zoneLabel() : field.key.equals("window_start") || field.key.equals("window_end") ? activity.time(s(field.key)) : choiceLabel(field));
                row.error.setVisibility(View.GONE);
            }
        }
        for (String key : blocks.keySet()) blocks.get(key).setVisibility(visible(key) ? View.VISIBLE : View.GONE);
        if (scheduleNote != null) {
            int comparison = s("window_end").compareTo(s("window_start"));
            scheduleNote.setText(comparison == 0 ? R.string.full_day_window : R.string.overnight_window);
            scheduleNote.setVisibility(comparison <= 0 ? View.VISIBLE : View.GONE);
        }
        if (fields.containsKey("panic_secret")) {
            EditText secret = (EditText)fields.get("panic_secret");
            int type = s("panic_type").equals("pin") ? InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD;
            // Change the keyboard, not the input: invalid pasted credentials must be rejected intact.
            secret.setRawInputType(type);
        }
        for (String key : SECRETS) if (inputs.containsKey(key)) {
            boolean cleared = state.cleared.contains(key);
            inputs.get(key).setEnabled(!cleared && !activity.busy);
            inputs.get(key).setHelperText(cleared ? activity.getString(R.string.secret_will_remove) : state.original.optBoolean("has_" + key) ? activity.getString(R.string.secret_saved) : null);
            if (clearButtons.containsKey(key)) clearButtons.get(key).setText(cleared ? R.string.keep_saved : R.string.remove_saved);
        }
        keepSaved.setVisibility(state.reenter.isEmpty() ? View.GONE : View.VISIBLE);
        requirements.removeAllViews();
        boolean running = activity.status != null && activity.status.optString("action").equals("running");
        if (activity.status == null) activity.button(requirements, R.string.start_engine, false, activity::startEngine);
        if (running) requirements.addView(activity.label(activity.getString(R.string.error_action_running)));
        if (activity.save != null) activity.save.setEnabled(activity.status != null && !running && !activity.busy);
        JSONObject hooks = activity.status == null || activity.status.optJSONObject("hooks") == null ? new JSONObject() : activity.status.optJSONObject("hooks");
        boolean hook = state.group.equals("device") && !s("unlock_scope").equals("any") && !hooks.optBoolean("credential")
            || state.group.equals("panic") && state.draft.optBoolean("panic_enabled") && (!hooks.optBoolean("credential") || !hooks.optBoolean("panic"))
            || state.group.equals("failures") && state.draft.optBoolean("fail_enabled") &&
               (s("fail_source").equals("hook") && !hooks.optBoolean("credential") || !s("fail_biometrics").equals("none") &&
                 ((!s("fail_biometrics").equals("face") && !hooks.optBoolean("fingerprint")) || (!s("fail_biometrics").equals("fingerprint") && !hooks.optBoolean("face"))));
        if (hook) requirements.addView(activity.label(activity.getString(R.string.error_hook_unavailable)));
        if (state.group.equals("failures") && s("fail_source").equals("admin")) {
            boolean admin = activity.getSystemService(DevicePolicyManager.class).isAdminActive(new ComponentName(activity, Admin.class));
            requirements.addView(activity.label(activity.getString(R.string.admin_in_user)));
            if (!admin) activity.button(requirements, R.string.device_admin, false, activity::admin);
        }
        if (state.group.equals("remote") && !s("sms_mode").equals("off") && activity.checkSelfPermission(Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED)
            activity.button(requirements, R.string.allow_sms, false, () -> activity.requestPermission(Manifest.permission.RECEIVE_SMS));
        if (state.group.equals("remote") && mode("passphrase")) requirements.addView(activity.label(activity.getString(R.string.passphrase_warning)));
    }
    void busy(boolean busy) { if (container != null) { enabled(container, !busy); if (!busy) update(); } }
    static void enabled(View view, boolean value) {
        view.setEnabled(value); if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)view).getChildCount(); i++) enabled(((ViewGroup)view).getChildAt(i), value);
    }
    boolean dirty() {
        if (!state.cleared.isEmpty() || !state.reenter.isEmpty()) return true;
        for (Settings.Field field : Settings.FIELDS) if (field.group.equals(state.group) && !state.original.optString(field.key).equals(s(field.key))) return true;
        for (String key : SECRETS) if (!s(key).isEmpty()) return true;
        return false;
    }
    JSONObject configuration() throws Exception {
        error.setVisibility(View.GONE); for (TextInputLayout box : inputs.values()) box.setError(null);
        if (!state.reenter.isEmpty()) throw new Invalid(state.reenter.iterator().next(), activity.getString(R.string.reenter_secrets));
        if (activity.status == null) throw new Invalid("", activity.getString(R.string.error_engine_unavailable));
        for (Settings.Field field : Settings.FIELDS)
            if (!state.original.optString(field.key).equals(activity.status.optJSONObject("config").optString(field.key)))
                throw new Invalid("", activity.getString(R.string.settings_changed));
        JSONObject config = copy(state.draft);
        for (Settings.Field field : Settings.FIELDS) if (field.kind.equals("number")) {
            try {
                int value = Integer.parseInt(s(field.key));
                if (value < field.min || value > field.max) throw new NumberFormatException();
                config.put(field.key, value);
            } catch (NumberFormatException invalid) { throw new Invalid(field.key, activity.getString(R.string.number_range, activity.number(field.min), activity.number(field.max))); }
        }
        for (String key : SECRETS) {
            if (state.cleared.contains(key)) config.put(key, "");
            else if (s(key).isEmpty()) config.remove(key);
        }
        try { new Settings(config); }
        catch (DateTimeException invalid) { throw new Invalid("timezone", activity.getString(R.string.error_invalid_timezone)); }
        catch (java.net.URISyntaxException invalid) { throw new Invalid("inbox_url", activity.getString(R.string.error_invalid_inbox_url)); }
        return config;
    }
    void error(String code) {
        update();
        String key = code == null ? "" : code.replaceFirst("^(invalid_|missing_)", "");
        if (code != null && code.equals("missing_public_key") && s("device_id").isEmpty()) key = "device_id";
        if (code != null && (code.equals("invalid_panic_pin") || code.equals("invalid_panic_password"))) key = "panic_secret";
        fieldError(key, activity.message(code));
    }
    void fieldError(String key, String message) {
        View view = fields.get(key);
        if (view == null) {
            error.setText(message); error.setVisibility(View.VISIBLE); activity.scroll.smoothScrollTo(0, 0); return;
        }
        blocks.get(key).setVisibility(View.VISIBLE);
        if (key.equals("window_days")) { dayError.setText(message); dayError.setVisibility(View.VISIBLE); }
        else if (inputs.containsKey(key)) inputs.get(key).setError(message);
        else if (view instanceof MainActivity.Row) { ((MainActivity.Row)view).error.setText(message); ((MainActivity.Row)view).error.setVisibility(View.VISIBLE); }
        view.requestFocus();
        activity.scroll.post(() -> { View block = blocks.get(key); Rect rect = new Rect(); block.getDrawingRect(rect); container.offsetDescendantRectToMyCoords(block, rect); activity.scroll.smoothScrollTo(0, Math.max(0, rect.top - activity.dp(20))); });
    }
}
