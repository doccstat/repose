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
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.*;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.util.*;
import org.json.*;

public final class MainActivity extends AppCompatActivity {
    JSONObject status;
    LinearLayout body, footer;
    ScrollView scroll;
    MaterialToolbar toolbar;
    LinearProgressIndicator progress;
    MaterialButton save;
    SettingsForm form;
    String page = "home", export;
    boolean authenticated, authenticating, busy;
    interface Work { JSONObject run() throws Exception; }
    interface Done { void accept(JSONObject result) throws Exception; }
    interface Backend { JSONObject call(JSONObject request) throws Exception; }
    Backend backend = Client::call;
    static final class Retained {
        SettingsForm.State draft;
        boolean authenticated;
    }

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        FrameLayout frame = new FrameLayout(this);
        LinearLayout root = new LinearLayout(this) {
            @Override protected void onMeasure(int width, int height) {
                super.onMeasure(View.MeasureSpec.makeMeasureSpec(Math.min(View.MeasureSpec.getSize(width), dp(720)), View.MeasureSpec.EXACTLY), height);
            }
        };
        root.setOrientation(LinearLayout.VERTICAL);
        frame.addView(root, new FrameLayout.LayoutParams(-1, -1, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        toolbar = new MaterialToolbar(this); root.addView(toolbar);
        progress = new LinearProgressIndicator(this); progress.setIndeterminate(true);
        progress.setVisibility(View.INVISIBLE); root.addView(progress, new LinearLayout.LayoutParams(-1, dp(4)));
        scroll = new ScrollView(this); scroll.setFillViewport(true);
        body = column(); body.setSaveFromParentEnabled(false); scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        footer = column(); footer.setVisibility(View.GONE); root.addView(footer);
        ViewCompat.setOnApplyWindowInsetsListener(frame, (view, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); return insets;
        });
        setContentView(frame);
        SettingsForm.State draft = null;
        if (saved != null) {
            page = saved.getString("page", "home"); export = saved.getString("diagnostic_export");
            authenticating = saved.getBoolean("authenticating");
            try { if (saved.containsKey("draft")) draft = SettingsForm.State.restore(new JSONObject(saved.getString("draft"))); }
            catch (JSONException invalid) { page = "home"; }
        }
        Retained retained = (Retained)getLastCustomNonConfigurationInstance();
        if (retained != null) { authenticated = retained.authenticated; draft = retained.draft; }
        if (draft != null) form = new SettingsForm(this, draft);
        navigation();
        body.addView(label(getString(R.string.connecting)));
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { back(); }
        });
    }
    @Override public Object onRetainCustomNonConfigurationInstance() {
        Retained retained = new Retained(); retained.authenticated = authenticated;
        if (form != null) { form.state.scroll = scroll.getScrollY(); retained.draft = form.state; }
        return retained;
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("page", page); out.putString("diagnostic_export", export);
        out.putBoolean("authenticating", authenticating);
        if (form != null) { form.state.scroll = scroll.getScrollY(); out.putString("draft", form.state.saved().toString()); }
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent);
        if (authenticated && form == null) refresh();
    }
    @Override protected void onStop() {
        super.onStop();
        if (!isChangingConfigurations()) {
            authenticated = false; body.setVisibility(View.INVISIBLE); footer.setVisibility(View.GONE);
        }
    }
    @Override protected void onResume() {
        super.onResume();
        if (authenticated && !getSystemService(KeyguardManager.class).isDeviceLocked()) refresh();
        else authenticate();
    }
    void authenticate() {
        authenticated = false; body.setVisibility(View.INVISIBLE); footer.setVisibility(View.GONE);
        if (authenticating) return;
        Intent intent = getSystemService(KeyguardManager.class).createConfirmDeviceCredentialIntent(getString(R.string.app_name), getString(R.string.unlock_settings));
        if (intent == null) {
            body.removeAllViews(); body.setVisibility(View.VISIBLE);
            heading(body, getString(R.string.screen_lock_title)); body.addView(label(getString(R.string.screen_lock_required)));
            button(body, R.string.open_security_settings, true, () -> startActivity(new Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS)));
            return;
        }
        authenticating = true; startActivityForResult(intent, 10);
    }
    int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    Locale locale() { return getResources().getConfiguration().getLocales().get(0); }
    String number(int value) { return NumberFormat.getIntegerInstance(locale()).format(value); }
    String duration(int minutes) {
        android.icu.text.MeasureFormat format = android.icu.text.MeasureFormat.getInstance(locale(), android.icu.text.MeasureFormat.FormatWidth.WIDE);
        List<android.icu.util.Measure> measures = new ArrayList<>();
        if (minutes >= 60) measures.add(new android.icu.util.Measure(minutes / 60, android.icu.util.MeasureUnit.HOUR));
        if (minutes % 60 != 0 || measures.isEmpty()) measures.add(new android.icu.util.Measure(minutes % 60, android.icu.util.MeasureUnit.MINUTE));
        return format.formatMeasures(measures.toArray(new android.icu.util.Measure[0]));
    }
    String time(String value) {
        java.time.LocalTime time = java.time.LocalTime.parse(value);
        Calendar clock = Calendar.getInstance(TimeZone.getTimeZone("UTC")); clock.clear();
        clock.set(Calendar.HOUR_OF_DAY, time.getHour()); clock.set(Calendar.MINUTE, time.getMinute());
        java.text.DateFormat format = android.text.format.DateFormat.getTimeFormat(this); format.setTimeZone(clock.getTimeZone());
        return format.format(clock.getTime());
    }
    LinearLayout column() {
        LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL);
        view.setPaddingRelative(dp(20), dp(8), dp(20), dp(20)); return view;
    }
    TextView label(String text) {
        TextView view = new TextView(this); view.setText(text);
        view.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
        view.setPadding(0, dp(8), 0, dp(8)); return view;
    }
    void heading(LinearLayout target, String text) {
        TextView view = label(text); view.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_HeadlineMedium);
        ViewCompat.setAccessibilityHeading(view, true); target.addView(view);
    }
    void section(LinearLayout target, int title) {
        TextView view = label(getString(title)); view.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall);
        view.setTextColor(MaterialColors.getColor(view, androidx.appcompat.R.attr.colorPrimary));
        view.setPaddingRelative(0, dp(24), 0, dp(8)); ViewCompat.setAccessibilityHeading(view, true); target.addView(view);
    }
    MaterialButton button(LinearLayout target, int text, boolean primary, Runnable run) {
        MaterialButton view = new MaterialButton(this, null, primary ? com.google.android.material.R.attr.materialButtonStyle : androidx.appcompat.R.attr.borderlessButtonStyle);
        view.setText(text); view.setMinHeight(dp(48)); view.setOnClickListener(v -> { if (!busy) run.run(); });
        target.addView(view, new LinearLayout.LayoutParams(-1, -2)); return view;
    }
    final class Row extends LinearLayout {
        final TextView value, error;
        Row(LinearLayout target, String title, String summary, Runnable action) {
            super(MainActivity.this); setOrientation(HORIZONTAL); setGravity(Gravity.CENTER_VERTICAL);
            setMinimumHeight(dp(72)); setPaddingRelative(0, dp(8), 0, dp(8));
            LinearLayout labels = new LinearLayout(MainActivity.this); labels.setOrientation(VERTICAL);
            TextView name = label(title); name.setPadding(0, 0, 0, 0); labels.addView(name);
            value = label(summary); value.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
            value.setTextColor(MaterialColors.getColor(value, com.google.android.material.R.attr.colorOnSurfaceVariant));
            value.setPadding(0, dp(4), 0, 0); labels.addView(value); value(summary);
            error = label(""); error.setTextColor(MaterialColors.getColor(error, androidx.appcompat.R.attr.colorError));
            error.setVisibility(GONE); labels.addView(error);
            addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
            if (action != null) {
                ImageView chevron = new ImageView(MainActivity.this); chevron.setImageResource(R.drawable.ic_chevron);
                chevron.setImageTintList(android.content.res.ColorStateList.valueOf(MaterialColors.getColor(value, com.google.android.material.R.attr.colorOnSurfaceVariant)));
                chevron.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
                LinearLayout.LayoutParams icon = new LinearLayout.LayoutParams(dp(24), dp(24)); icon.setMarginStart(dp(12)); addView(chevron, icon);
                android.util.TypedValue ripple = new android.util.TypedValue(); getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
                setBackgroundResource(ripple.resourceId); setOnClickListener(v -> { if (!busy) action.run(); }); setFocusable(true);
            }
            ViewCompat.setScreenReaderFocusable(this, true); target.addView(this, new LinearLayout.LayoutParams(-1, -2));
        }
        void value(String text) { value.setText(text); value.setVisibility(text.isEmpty() ? GONE : VISIBLE); }
        @Override public void onInitializeAccessibilityNodeInfo(android.view.accessibility.AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info); if (isClickable()) info.setClassName(Button.class.getName());
        }
    }
    TextInputEditText input(LinearLayout target, String title, String value, int type) {
        LinearLayout field = new LinearLayout(this); field.setOrientation(LinearLayout.VERTICAL);
        TextView name = label(title); name.setPadding(0, 0, 0, dp(8)); field.addView(name);
        TextInputLayout box = new TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle); box.setHintEnabled(false);
        TextInputEditText edit = new TextInputEditText(box.getContext());
        // Numeric keyboards must not silently filter invalid pasted input.
        edit.setInputType(type == InputType.TYPE_CLASS_NUMBER ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS : type);
        edit.setRawInputType(type); edit.setText(value);
        edit.setId(View.generateViewId()); name.setLabelFor(edit.getId()); edit.setMinHeight(dp(56)); box.addView(edit); field.addView(box);
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2); layout.topMargin = dp(12); layout.bottomMargin = dp(4);
        target.addView(field, layout); return edit;
    }
    String text(String key) {
        int id = getResources().getIdentifier(key, "string", getPackageName());
        return id == 0 ? key : getString(id);
    }
    void navigation() {
        toolbar.setTitle(page.equals("home") ? getString(R.string.app_name) : page.equals("support") ? getString(R.string.status_and_access) : text("group_" + page));
        androidx.appcompat.graphics.drawable.DrawerArrowDrawable back = new androidx.appcompat.graphics.drawable.DrawerArrowDrawable(this); back.setProgress(1f);
        toolbar.setNavigationIcon(page.equals("home") ? null : back);
        toolbar.setNavigationContentDescription(R.string.back); toolbar.setNavigationOnClickListener(v -> back());
        toolbar.getMenu().clear();
        toolbar.getMenu().add(R.string.refresh).setOnMenuItemClickListener(item -> { refresh(); return true; });
        if (form != null) toolbar.getMenu().add(R.string.how_it_works).setOnMenuItemClickListener(item -> {
            new MaterialAlertDialogBuilder(this).setTitle(R.string.how_it_works).setMessage(text(page.equals("timer") || page.equals("window") ? "timing_help" : page + "_help"))
                .setPositiveButton(android.R.string.ok, null).show(); return true;
        });
        toolbar.getMenu().add(R.string.export_diagnostics).setOnMenuItemClickListener(item -> { diagnostics(); return true; });
    }
    void back() {
        if (busy) return;
        if (form != null && form.dirty()) {
            new MaterialAlertDialogBuilder(this).setTitle(R.string.discard_title).setMessage(R.string.discard_message)
                .setNegativeButton(R.string.keep_editing, null).setPositiveButton(R.string.discard, (d, w) -> home()).show();
        } else if (!page.equals("home")) home(); else finish();
    }
    void home() {
        form = null; page = "home"; navigation(); footer.setVisibility(View.GONE);
        if (authenticated) { if (status != null) renderHome(); else disconnected(); }
    }
    void busy(boolean value) {
        busy = value; progress.setVisibility(value ? View.VISIBLE : View.INVISIBLE);
        if (save != null) save.setEnabled(!value);
        if (form != null) form.busy(value);
    }
    void work(Work task, Done done) {
        if (busy) return;
        busy(true);
        Client.IO.execute(() -> {
            try {
                JSONObject result = task.run();
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    busy(false);
                    if (!authenticated) return;
                    try { done.accept(result); } catch (Exception e) { error(e.getMessage()); }
                });
            } catch (Exception e) { runOnUiThread(() -> { if (!isFinishing() && !isDestroyed()) { busy(false); error(e.getMessage()); } }); }
        });
    }
    String message(String code) {
        if (code == null || !code.matches("[A-Za-z0-9_]+")) code = "engine_unavailable";
        int resource = getResources().getIdentifier("error_" + code, "string", getPackageName());
        return resource == 0 ? getString(R.string.error_generic, code) : getString(resource);
    }
    void error(String code) {
        if (!authenticated) return;
        if (form != null) { if (form.container == null) showForm(); form.error(code); }
        else {
            if (status == null) disconnected();
            TextView error = label(message(code)); error.setTextColor(MaterialColors.getColor(error, androidx.appcompat.R.attr.colorError));
            error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); body.addView(error, 0);
        }
    }
    void disconnected() {
        body.removeAllViews(); body.setVisibility(View.VISIBLE); footer.setVisibility(View.GONE);
        heading(body, getString(R.string.engine_offline)); body.addView(label(getString(R.string.engine_unavailable)));
        button(body, R.string.start_engine, true, this::startEngine);
        button(body, R.string.refresh, false, this::refresh);
    }
    void startEngine() {
        if (authenticated) work(() -> { Client.start(); return backend.call(new JSONObject().put("op", "status")); }, this::render);
    }
    void refresh() {
        if (authenticated && !busy) {
            status = null;
            work(() -> backend.call(new JSONObject().put("op", "status")), this::render);
        }
    }
    void render(JSONObject result) throws Exception {
        status = result;
        if (!authenticated) return;
        body.setVisibility(View.VISIBLE);
        if (form != null) {
            if (form.container == null) showForm(); else form.update();
            footer.setVisibility(View.VISIBLE);
        } else if (page.equals("support")) support(); else renderHome();
        if (authenticated) Controls.notification(this, result);
        if (form == null && authenticated) {
            String control = getIntent().getStringExtra("control"); getIntent().removeExtra("control");
            JSONObject config = result.getJSONObject("config");
            if ("snooze".equals(control) && Controls.timers(config)) snooze();
            if ("skip".equals(control) && config.optBoolean("window_enabled")) confirmControl("skip", R.string.skip_confirmation, R.string.skip);
            if ("resume".equals(control) && Controls.timers(config)) confirmControl("resume", R.string.resume_confirmation, R.string.resume);
        }
    }
    void renderHome() {
        page = "home"; navigation(); body.removeAllViews(); scroll.scrollTo(0, 0); footer.setVisibility(View.GONE);
        JSONObject c = status.optJSONObject("config");
        boolean timed = Controls.timers(c), security = Controls.security(c);
        String action = status.optString("action", "idle");
        heading(body, Controls.summary(this, status));
        if (!timed && !security && action.equals("idle")) body.addView(label(getString(R.string.choose_rule)));
        if (security && timed && !status.optBoolean("armed")) body.addView(label(getString(R.string.timer_waiting_unlock)));
        if (status.optBoolean("snoozed") && security) body.addView(label(getString(R.string.snoozed)));
        if (status.optBoolean("skipped")) body.addView(label(getString(R.string.skipped)));
        if (action.equals("failed") || action.equals("reboot_requested")) button(body, R.string.acknowledge, true, () -> confirmControl("acknowledge", R.string.acknowledge_confirmation, R.string.acknowledge));
        if (timed && action.equals("idle")) {
            if (status.optBoolean("snoozed") || status.optBoolean("skipped")) button(body, R.string.resume, false, () -> confirmControl("resume", R.string.resume_confirmation, R.string.resume));
            else button(body, R.string.snooze, false, this::snooze);
            if (c.optBoolean("window_enabled") && !status.optBoolean("skipped")) button(body, R.string.skip, false, () -> confirmControl("skip", R.string.skip_confirmation, R.string.skip));
        }
        section(body, R.string.reboot_action);
        new Row(body, getString(R.string.group_device), c.optString("profile").equals("none") ? getString(R.string.reboot_only)
                : getString(R.string.pause_then_reboot, identityName(c.optString("profile"))), () -> group("device"));
        section(body, R.string.timers);
        new Row(body, getString(R.string.group_timer), c.optBoolean("timed_enabled") ? getString(R.string.duration_without_unlock, duration(c.optInt("timed_minutes"))) : getString(R.string.off), () -> group("timer"));
        new Row(body, getString(R.string.group_window), c.optBoolean("window_enabled") ? getString(R.string.schedule_summary, time(c.optString("window_start")), time(c.optString("window_end")), duration(c.optInt("window_minutes"))) : getString(R.string.off), () -> group("window"));
        section(body, R.string.security_triggers);
        new Row(body, getString(R.string.group_failures), c.optBoolean("fail_enabled") ? getString(R.string.attempts_progress, number(status.optInt("failures")), number(c.optInt("fail_limit"))) : getString(R.string.off), () -> group("failures"));
        new Row(body, getString(R.string.group_panic), c.optBoolean("panic_enabled") ? text("choice_panic_type_" + c.optString("panic_type")) : getString(R.string.off), () -> group("panic"));
        new Row(body, getString(R.string.group_remote), c.optString("sms_mode").equals("off") && c.optString("inbox_mode").equals("off") ? getString(R.string.off)
                : getString(R.string.remote_summary, text("choice_sms_mode_" + c.optString("sms_mode")), text("choice_inbox_mode_" + c.optString("inbox_mode"))), () -> group("remote"));
        section(body, R.string.app_settings);
        new Row(body, getString(R.string.status_and_access), "", this::support);
    }
    String identityName(String identity) {
        JSONArray users = status == null ? null : status.optJSONArray("users");
        if (users != null) for (int i = 0; i < users.length(); i++) {
            JSONObject user = users.optJSONObject(i);
            if (identity.equals(user.optString("identity"))) return user.optString("name");
        }
        return getString(R.string.missing_user, identity);
    }
    void group(String name) {
        if (!authenticated || busy || status == null) return;
        try { form = new SettingsForm(this, new SettingsForm.State(name, status.getJSONObject("config"))); page = name; showForm(); }
        catch (Exception e) { error(e.getMessage()); }
    }
    void showForm() {
        body.removeAllViews(); footer.removeAllViews(); navigation(); scroll.scrollTo(0, 0);
        form.build(body);
        int position = form.state.scroll; scroll.post(() -> scroll.scrollTo(0, position));
        save = button(footer, R.string.save_changes, true, this::save);
        form.update(); footer.setVisibility(View.VISIBLE);
    }
    void save() {
        if (!authenticated || form == null || busy) return;
        try {
            JSONObject config = form.configuration();
            if (!config.optString("sms_mode").equals("off") && checkSelfPermission(Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) {
                form.error("sms_permission_required"); return;
            }
            work(() -> backend.call(new JSONObject().put("op", "configure").put("config", config)), ignored -> {
                form = null; page = "home"; status = null; navigation(); footer.setVisibility(View.GONE);
                body.removeAllViews(); body.addView(label(getString(R.string.connecting)));
                Snackbar.make(findViewById(android.R.id.content), R.string.saved, Snackbar.LENGTH_SHORT).show(); refresh();
            });
        } catch (SettingsForm.Invalid invalid) { form.fieldError(invalid.key, invalid.getMessage()); }
        catch (Exception e) { form.error(e.getMessage()); }
    }
    void support() {
        if (!authenticated) return;
        page = "support"; navigation(); footer.setVisibility(View.GONE); body.removeAllViews(); scroll.scrollTo(0, 0);
        section(body, R.string.engine);
        new Row(body, getString(R.string.engine), status == null ? getString(R.string.unavailable) : getString(R.string.engine_active, status.optString("version")), null);
        JSONObject hooks = status == null ? new JSONObject() : status.optJSONObject("hooks");
        for (String key : new String[]{"credential", "panic", "fingerprint", "face"})
            new Row(body, text("capability_" + key), getString(hooks != null && hooks.optBoolean(key) ? R.string.available : R.string.unavailable), null);
        section(body, R.string.permissions);
        boolean notifications = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        new Row(body, getString(R.string.notifications), getString(notifications ? R.string.allowed : R.string.not_allowed), () -> {
            if (notifications) startActivity(new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName()));
            else requestPermission(Manifest.permission.POST_NOTIFICATIONS);
        });
        button(body, R.string.export_diagnostics, false, this::diagnostics);
    }
    void requestPermission(String permission) {
        requestPermissions(new String[]{permission}, 11);
    }
    void admin() {
        startActivity(new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, new ComponentName(this, Admin.class)));
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (form != null) form.update(); else if (page.equals("support")) support();
        if (results.length > 0 && results[0] != PackageManager.PERMISSION_GRANTED)
            Snackbar.make(findViewById(android.R.id.content), R.string.permission_not_granted, Snackbar.LENGTH_INDEFINITE)
                .setAction(R.string.open_app_settings, view -> startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + getPackageName())))).show();
    }
    void command(String operation) {
        if (!authenticated) return;
        work(() -> { backend.call(new JSONObject().put("op", operation)); return backend.call(new JSONObject().put("op", "status")); }, this::render);
    }
    void confirmControl(String operation, int message, int action) {
        new MaterialAlertDialogBuilder(this).setTitle(action).setMessage(message).setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(action, (d, w) -> command(operation)).show();
    }
    void snooze() {
        if (!authenticated || status == null) return;
        LinearLayout fields = column();
        TextInputEditText minutes = input(fields, getString(R.string.f_snooze_minutes), String.valueOf(status.optJSONObject("config").optInt("snooze_minutes", 60)), InputType.TYPE_CLASS_NUMBER);
        TextInputLayout box = (TextInputLayout)minutes.getParent().getParent(); box.setSuffixText(getString(R.string.minutes));
        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this).setTitle(R.string.snooze).setView(fields)
                .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.snooze, null).create();
        dialog.setOnShowListener(d -> dialog.getButton(-1).setOnClickListener(v -> {
            try {
                int value = Integer.parseInt(minutes.getText().toString());
                if (value < 1 || value > 525600) throw new NumberFormatException();
                dialog.dismiss(); work(() -> { backend.call(new JSONObject().put("op", "snooze").put("minutes", value)); return backend.call(new JSONObject().put("op", "status")); }, this::render);
            } catch (NumberFormatException invalid) { box.setError(getString(R.string.number_range, number(1), number(525600))); }
        })); dialog.show();
    }
    void diagnostics() {
        if (!authenticated) return;
        work(() -> {
            JSONObject report;
            try { report = backend.call(new JSONObject().put("op", "diagnostics")); }
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
            authenticating = false;
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
            }, ignored -> Snackbar.make(findViewById(android.R.id.content), R.string.exported, Snackbar.LENGTH_SHORT).show());
        }
    }
}
