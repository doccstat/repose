package com.lixingchi.repose;

import android.Manifest;
import android.app.*;
import android.app.admin.DevicePolicyManager;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

public final class MainActivity extends AppCompatActivity {
    JSONObject status;
    LinearLayout body;
    TextView summary;
    boolean authenticated;
    String export;
    interface Work { JSONObject run() throws Exception; }
    interface Done { void accept(JSONObject result) throws Exception; }
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (saved != null) export = saved.getString("diagnostic_export");
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        MaterialToolbar toolbar = new MaterialToolbar(this); toolbar.setTitle(R.string.app_name); root.addView(toolbar);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        body = column(); scroll.addView(body); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); return insets;
        });
        setContentView(root);
        summary = label(getString(R.string.connecting)); body.addView(summary);
        KeyguardManager keyguard = getSystemService(KeyguardManager.class);
        Intent confirm = keyguard.createConfirmDeviceCredentialIntent(getString(R.string.app_name), getString(R.string.unlock_settings));
        if (confirm == null) { error(getString(R.string.screen_lock_required)); return; }
        startActivityForResult(confirm, 10);
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out); out.putString("diagnostic_export", export);
    }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); if (authenticated) refresh(); }
    @Override protected void onStop() { super.onStop(); if (getSystemService(KeyguardManager.class).isDeviceLocked()) authenticated = false; }
    @Override protected void onResume() {
        super.onResume();
        if (authenticated && getSystemService(KeyguardManager.class).isDeviceLocked()) { authenticated = false; finish(); }
    }
    int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    LinearLayout column() { LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); layout.setPaddingRelative(dp(20), dp(12), dp(20), dp(20)); return layout; }
    TextView label(String text) { TextView view = new TextView(this); view.setText(text); view.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge); view.setPadding(0, dp(8), 0, dp(8)); return view; }
    MaterialButton button(LinearLayout target, int text, Runnable run) {
        MaterialButton b = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        b.setText(text); b.setMinHeight(dp(48)); b.setOnClickListener(v -> run.run()); target.addView(b, new LinearLayout.LayoutParams(-1, -2)); return b;
    }
    String text(String key) {
        int id = getResources().getIdentifier(key, "string", getPackageName());
        return id == 0 ? key : getString(id);
    }
    void work(Work task, Done done) {
        Client.IO.execute(() -> {
            try {
                JSONObject result = task.run();
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    try { done.accept(result); } catch (Exception error) { error(error.getMessage()); }
                });
            } catch (Exception e) { runOnUiThread(() -> { if (!isFinishing() && !isDestroyed()) error(e.getMessage()); }); }
        });
    }
    void error(String message) {
        String code = message != null && message.matches("[A-Za-z0-9_]+") ? message : "engine_unavailable";
        int resource = getResources().getIdentifier("error_" + code, "string", getPackageName());
        String display = resource == 0 ? getString(R.string.error_generic, code) : getString(resource);
        new MaterialAlertDialogBuilder(this).setTitle(R.string.operation_failed).setMessage(display)
            .setPositiveButton(android.R.string.ok, null).show();
        if (status == null && authenticated) disconnected();
    }
    void disconnected() {
        body.removeAllViews(); body.addView(label(getString(R.string.engine_unavailable)));
        button(body, R.string.start_engine, () -> work(() -> { Client.start(); return Client.call(new JSONObject().put("op", "status")); }, this::render));
        button(body, R.string.refresh, this::refresh);
        button(body, R.string.export_diagnostics, this::diagnostics);
    }
    void refresh() {
        if (!authenticated) return;
        work(() -> Client.call(new JSONObject().put("op", "status")), this::render);
    }
    void render(JSONObject result) throws Exception {
        status = result;
        body.removeAllViews();
        summary = label(getString(R.string.engine_active, result.optString("version"))); body.addView(summary);
        body.addView(label(text("state_" + result.optString("action"))));
        if (!result.optBoolean("armed")) body.addView(label(getString(R.string.waiting_unlock)));
        if (result.optBoolean("snoozed")) body.addView(label(getString(R.string.snoozed)));
        if (result.optBoolean("skipped")) body.addView(label(getString(R.string.skipped)));
        body.addView(label(getString(R.string.failed_count, result.optInt("failures"))));
        JSONObject hooks = result.optJSONObject("hooks");
        if (hooks != null) body.addView(label(getString(R.string.hook_status,
                hooks.optBoolean("credential") ? getString(R.string.available) : getString(R.string.unavailable))));
        for (String group : new String[]{"device", "timer", "window", "failures", "panic", "remote"}) {
            int id = getResources().getIdentifier("group_" + group, "string", getPackageName());
            button(body, id, () -> group(group));
        }
        button(body, R.string.snooze, this::snooze);
        button(body, R.string.skip, () -> confirmControl("skip", R.string.skip_confirmation));
        button(body, R.string.resume, () -> command("resume"));
        if (!result.optString("action").equals("idle")) button(body, R.string.acknowledge, () -> confirmControl("acknowledge", R.string.acknowledge_confirmation));
        button(body, R.string.device_admin, () -> startActivity(new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, new ComponentName(this, Admin.class))));
        button(body, R.string.permissions, () -> requestPermissions(new String[]{Manifest.permission.RECEIVE_SMS, Manifest.permission.POST_NOTIFICATIONS}, 11));
        button(body, R.string.refresh, this::refresh);
        button(body, R.string.export_diagnostics, this::diagnostics);
        Controls.notification(this);
        String control = getIntent().getStringExtra("control");
        getIntent().removeExtra("control");
        if ("snooze".equals(control)) snooze();
        if ("skip".equals(control)) confirmControl("skip", R.string.skip_confirmation);
    }
    void command(String operation) {
        work(() -> { Client.call(new JSONObject().put("op", operation)); return Client.call(new JSONObject().put("op", "status")); }, this::render);
    }
    void confirmControl(String operation, int message) {
        new MaterialAlertDialogBuilder(this).setMessage(message).setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, (d, w) -> command(operation)).show();
    }
    TextInputEditText input(LinearLayout target, String title, String value, int type) {
        TextInputLayout box = new TextInputLayout(this); box.setHint(title);
        TextInputEditText edit = new TextInputEditText(box.getContext()); edit.setInputType(type); edit.setText(value);
        edit.setSelectAllOnFocus(true); box.addView(edit); target.addView(box); return edit;
    }
    void snooze() {
        LinearLayout fields = column();
        TextInputEditText minutes = input(fields, getString(R.string.f_snooze_minutes), String.valueOf(status.optJSONObject("config").optInt("snooze_minutes", 60)), InputType.TYPE_CLASS_NUMBER);
        new MaterialAlertDialogBuilder(this).setTitle(R.string.snooze).setView(fields).setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.snooze, (d, w) -> work(() -> {
                Client.call(new JSONObject().put("op", "snooze").put("minutes", Integer.parseInt(minutes.getText().toString())));
                return Client.call(new JSONObject().put("op", "status"));
            }, this::render)).show();
    }
    void group(String group) {
        try {
            JSONObject config = new JSONObject(status.getJSONObject("config").toString());
            LinearLayout fields = column(); Map<String, View> controls = new LinkedHashMap<>();
            Map<String, List<String>> choices = new HashMap<>();
            if (group.equals("timer") || group.equals("window")) fields.addView(label(getString(R.string.timing_help)));
            if (group.equals("failures")) fields.addView(label(getString(R.string.failures_help)));
            if (group.equals("panic")) fields.addView(label(getString(R.string.panic_help)));
            if (group.equals("remote")) fields.addView(label(getString(R.string.remote_help)));
            for (Settings.Field field : Settings.FIELDS) {
                if (!field.group.equals(group)) continue;
                String title = text("f_" + field.key);
                if (field.kind.equals("bool")) {
                    MaterialSwitch control = new MaterialSwitch(this); control.setText(title); control.setChecked(config.getBoolean(field.key));
                    control.setMinHeight(dp(48)); fields.addView(control); controls.put(field.key, control);
                } else if (field.kind.equals("choice") || field.kind.equals("user") || field.kind.equals("profile") || field.kind.equals("failure_user")) {
                    fields.addView(label(title));
                    List<String> values = new ArrayList<>(), labels = new ArrayList<>();
                    if (field.kind.equals("choice")) {
                        for (String value : field.choices) { values.add(value); labels.add(text("choice_" + field.key + "_" + value)); }
                    } else {
                        if (field.kind.equals("profile")) { values.add("none"); labels.add(getString(R.string.no_profile)); }
                        JSONArray users = status.getJSONArray("users");
                        for (int i = 0; i < users.length(); i++) {
                            JSONObject user = users.getJSONObject(i);
                            boolean profile = user.getString("type").startsWith("android.os.usertype.profile.");
                            if (field.kind.equals("user") && profile || field.kind.equals("profile") && !user.getString("type").equals("android.os.usertype.profile.MANAGED")) continue;
                            values.add(user.getString("identity"));
                            labels.add(getString(R.string.user_label, user.optString("name"), user.getString("identity")));
                        }
                        if (!values.contains(config.getString(field.key))) { values.add(config.getString(field.key)); labels.add(getString(R.string.missing_user, config.getString(field.key))); }
                    }
                    Spinner control = new Spinner(this); control.setContentDescription(title); control.setMinimumHeight(dp(48));
                    control.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
                    control.setSelection(Math.max(0, values.indexOf(config.getString(field.key))));
                    fields.addView(control); choices.put(field.key, values); controls.put(field.key, control);
                } else controls.put(field.key, input(fields, title, config.get(field.key).toString(), field.kind.equals("number") ? InputType.TYPE_CLASS_NUMBER : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));
            }
            Map<String, TextInputEditText> secrets = new LinkedHashMap<>();
            Map<String, MaterialSwitch> clear = new LinkedHashMap<>();
            String[] names = group.equals("panic") ? new String[]{"panic_secret"} : group.equals("remote") ? new String[]{"remote_secret", "public_key", "inbox_token"} : new String[0];
            for (String name : names) {
                String hint = getString(config.optBoolean("has_" + name) ? R.string.secret_replace : R.string.secret_new, text("f_" + name));
                secrets.put(name, input(fields, hint, "", InputType.TYPE_CLASS_TEXT | (name.equals("public_key") ? InputType.TYPE_TEXT_FLAG_MULTI_LINE : InputType.TYPE_TEXT_VARIATION_PASSWORD)));
                if (config.optBoolean("has_" + name)) {
                    MaterialSwitch remove = new MaterialSwitch(this); remove.setText(getString(R.string.secret_clear, text("f_" + name)));
                    remove.setMinHeight(dp(48)); fields.addView(remove); clear.put(name, remove);
                }
            }
            ScrollView scroll = new ScrollView(this); scroll.addView(fields);
            androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this).setTitle(text("group_" + group)).setView(scroll)
                .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.save, null).create();
            dialog.setOnShowListener(shown -> dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                try {
                    for (Map.Entry<String, View> entry : controls.entrySet()) {
                        View control = entry.getValue(); String key = entry.getKey();
                        if (control instanceof MaterialSwitch) config.put(key, ((MaterialSwitch)control).isChecked());
                        else if (control instanceof Spinner) config.put(key, choices.get(key).get(((Spinner)control).getSelectedItemPosition()));
                        else {
                            String value = ((EditText)control).getText().toString();
                            Settings.Field f = Settings.FIELDS.stream().filter(x -> x.key.equals(key)).findFirst().get();
                            config.put(key, f.kind.equals("number") ? Integer.valueOf(value) : value);
                        }
                    }
                    for (Map.Entry<String, TextInputEditText> entry : secrets.entrySet()) {
                        String value = entry.getValue().getText().toString();
                        if (clear.containsKey(entry.getKey()) && clear.get(entry.getKey()).isChecked()) config.put(entry.getKey(), "");
                        else if (!value.isEmpty()) config.put(entry.getKey(), value);
                    }
                    new Settings(config);
                    if (!config.optString("sms_mode").equals("off") && checkSelfPermission(Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED)
                        throw new IllegalStateException("sms_permission_required");
                    work(() -> {
                        Client.call(new JSONObject().put("op", "configure").put("config", config));
                        return Client.call(new JSONObject().put("op", "status"));
                    }, result -> { dialog.dismiss(); render(result); });
                } catch (Exception e) { error(e.getMessage()); }
            }));
            dialog.show();
        } catch (Exception e) { error(e.getMessage()); }
    }
    void diagnostics() {
        work(() -> {
            JSONObject report;
            try { report = Client.call(new JSONObject().put("op", "diagnostics")); }
            catch (Exception unavailable) { report = new JSONObject().put("version", BuildConfig.VERSION_NAME).put("sdk", android.os.Build.VERSION.SDK_INT).put("engine", "unavailable"); }
            report.put("receiver", createDeviceProtectedStorageContext().getSharedPreferences("receiver_status", MODE_PRIVATE).getString("error", "none"));
            return report;
        }, report -> {
            export = report.toString(2);
            startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_TITLE, "repose-diagnostics.json"), 12);
        });
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 10) {
            if (result != RESULT_OK) { finish(); return; }
            authenticated = true; refresh();
        }
        if (request == 12 && result == RESULT_OK && data != null && data.getData() != null && export != null) {
            String content = export; export = null;
            work(() -> {
                try (java.io.OutputStream output = getContentResolver().openOutputStream(data.getData())) {
                    if (output == null) throw new java.io.IOException("export_failed");
                    output.write(content.getBytes(StandardCharsets.UTF_8));
                }
                return new JSONObject();
            }, ignored -> Toast.makeText(this, R.string.exported, Toast.LENGTH_SHORT).show());
        }
    }
}
