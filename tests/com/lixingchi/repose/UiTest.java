package com.lixingchi.repose;

import android.app.*;
import android.content.DialogInterface;
import android.content.res.Configuration;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.chip.Chip;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputLayout;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowDialog;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class UiTest {
    MainActivity activity;
    ActivityController<MainActivity> controller;
    final List<JSONObject> sent = new ArrayList<>();
    JSONObject status() throws Exception {
        return new EngineTest.Harness().engine.status().put("ok", true).put("version", "1.0.0")
            .put("hooks", new JSONObject()).put("users", new JSONArray()
                .put(new JSONObject().put("identity", "0:0").put("name", "Owner").put("type", "android.os.usertype.full.SYSTEM").put("parent", "none"))
                .put(new JSONObject().put("identity", "10:42").put("name", "Work").put("type", "android.os.usertype.profile.MANAGED").put("parent", "0:0"))
                .put(new JSONObject().put("identity", "11:43").put("name", "Other work").put("type", "android.os.usertype.profile.MANAGED").put("parent", "12:44")));
    }
    MainActivity open(JSONObject initial) throws Exception {
        controller = Robolectric.buildActivity(MainActivity.class).create(); activity = controller.get(); activity.authenticated = true;
        activity.backend = request -> { sent.add(request); return initial; };
        activity.render(initial); return activity;
    }
    @After public void close() throws Exception {
        drain(); if (activity != null) activity.finish();
        for (Dialog dialog : ShadowDialog.getShownDialogs()) dialog.dismiss();
    }
    void drain() throws Exception {
        for (int i = 0; i < 3; i++) { Client.IO.submit(() -> {}).get(10, TimeUnit.SECONDS); Shadows.shadowOf(Looper.getMainLooper()).idle(); }
    }
    List<View> views(View view) {
        List<View> result = new ArrayList<>(); result.add(view);
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)view).getChildCount(); i++) result.addAll(views(((ViewGroup)view).getChildAt(i)));
        return result;
    }
    String text(View root) {
        StringBuilder result = new StringBuilder();
        for (View view : views(root)) if (view instanceof TextView && view.getVisibility() == View.VISIBLE) result.append(((TextView)view).getText()).append('\n');
        return result.toString();
    }
    @Test public void observerAssetDefinesOnlyTheAdapterNotPlatformStubs() throws Exception {
        byte[] dex;
        try (java.io.InputStream input = RuntimeEnvironment.getApplication().getAssets().open("keyguard-observer/classes.dex")) { dex = input.readAllBytes(); }
        java.nio.ByteBuffer bytes = java.nio.ByteBuffer.wrap(dex).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        assertEquals("dex\n", new String(dex, 0, 4, java.nio.charset.StandardCharsets.US_ASCII));
        assertEquals(1, bytes.getInt(96));
        int type = bytes.getInt(bytes.getInt(100)); int name = bytes.getInt(bytes.getInt(68) + 4 * type); int offset = bytes.getInt(bytes.getInt(60) + 4 * name);
        while ((dex[offset++] & 128) != 0) { }
        int end = offset; while (dex[end] != 0) end++;
        assertEquals("Lcom/lixingchi/repose/observer/KeyguardObserver;", new String(dex, offset, end - offset, java.nio.charset.StandardCharsets.US_ASCII));
    }
    @Test public void homeDistinguishesDisabledWaitingActiveAndFailed() throws Exception {
        JSONObject status = status(); open(status);
        assertTrue(text(activity.body).contains(activity.getString(R.string.protection_off)));
        assertFalse(text(activity.body).contains(activity.getString(R.string.snooze)));
        assertEquals(7, views(activity.body).stream().filter(v -> v instanceof MainActivity.Row).count());
        status.getJSONObject("config").put("timed_enabled", true);
        activity.render(status); assertTrue(text(activity.body).contains(activity.getString(R.string.waiting_unlock)));
        status.put("armed", true); activity.render(status);
        assertTrue(text(activity.body).contains(activity.getString(R.string.protection_on)));
        assertTrue(text(activity.body).contains(activity.getString(R.string.snooze)));
        status.put("action", "failed"); activity.render(status);
        assertTrue(text(activity.body).contains(activity.getString(R.string.acknowledge)));
        status.put("action", "running"); activity.render(status);
        assertFalse(text(activity.body).contains(activity.getString(R.string.acknowledge)));
    }
    @Test public void allFieldsLiveOnFullScreensAndEditingDoesNotSendRequests() throws Exception {
        open(status());
        for (String group : new String[]{"device", "timer", "window", "failures", "panic", "remote"}) {
            activity.group(group);
            assertEquals(activity.text("group_" + group), activity.toolbar.getTitle());
            assertEquals(View.VISIBLE, activity.footer.getVisibility());
            for (Settings.Field field : Settings.FIELDS) if (field.group.equals(group)) {
                assertNotNull(field.key, activity.form.fields.get(field.key));
                assertNotEquals("f_" + field.key, activity.text("f_" + field.key));
                for (String choice : field.choices) assertNotEquals("choice_" + field.key + "_" + choice, activity.text("choice_" + field.key + "_" + choice));
            }
            assertTrue(views(activity.body).stream().noneMatch(v -> v instanceof Spinner));
            assertFalse(activity.form.dirty()); activity.back();
        }
        assertTrue(sent.isEmpty());
    }
    @Test public void saveIsExplicitAndInvalidNumbersStayAtTheField() throws Exception {
        JSONObject status = status(); open(status); activity.group("timer");
        EditText duration = (EditText)activity.form.fields.get("timed_minutes");
        duration.getText().replace(0, duration.length(), "1a2"); assertEquals("1a2", duration.getText().toString());
        activity.save(); assertTrue(sent.isEmpty()); assertNotNull(activity.form.inputs.get("timed_minutes").getError());
        duration.setText("0"); assertTrue(activity.form.dirty()); activity.save();
        assertTrue(sent.isEmpty()); assertNotNull(activity.form.inputs.get("timed_minutes").getError());
        assertEquals("0", duration.getText().toString());
        duration.setText("245"); activity.save(); drain();
        assertEquals("configure", sent.get(0).getString("op"));
        JSONObject configured = sent.get(0).getJSONObject("config");
        assertEquals(245, configured.getInt("timed_minutes")); assertFalse(configured.getBoolean("timed_enabled"));
        for (Settings.Field field : Settings.FIELDS) if (!field.key.equals("timed_minutes")) assertEquals(field.key, status.getJSONObject("config").get(field.key), configured.get(field.key));
        assertNull(activity.form); assertEquals("home", activity.page);
    }
    @Test public void backOffersDiscardWithoutApplyingAndRefreshKeepsDraft() throws Exception {
        JSONObject status = status(); open(status); activity.group("window");
        EditText minutes = (EditText)activity.form.fields.get("window_minutes"); minutes.setText("243");
        activity.render(status); assertEquals("243", minutes.getText().toString());
        activity.back(); AlertDialog dialog = (AlertDialog)ShadowDialog.getLatestDialog();
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick(); drain(); assertEquals("243", activity.form.s("window_minutes"));
        activity.back(); ((AlertDialog)ShadowDialog.getLatestDialog()).getButton(DialogInterface.BUTTON_POSITIVE).performClick(); drain();
        assertNull(activity.form); assertTrue(sent.isEmpty());
    }
    @Test public void saveFailureKeepsValuesAndPointsToSecret() throws Exception {
        open(status()); activity.group("panic");
        ((MaterialSwitch)activity.form.fields.get("panic_enabled")).setChecked(true);
        EditText input = (EditText)activity.form.fields.get("panic_secret"); input.setText("1234");
        activity.backend = request -> { throw new IllegalArgumentException("invalid_panic_pin"); };
        activity.save(); drain();
        assertEquals("1234", input.getText().toString()); assertNotNull(activity.form.inputs.get("panic_secret").getError());
        assertTrue(activity.save.isEnabled()); assertEquals(View.INVISIBLE, activity.progress.getVisibility());
    }
    @Test public void scheduleUsesTimeAndWeekdayPickersWithoutChangingOtherValues() throws Exception {
        JSONObject status = status(); status.getJSONObject("config").put("window_days", "7,3,1"); open(status); activity.group("window");
        assertEquals("7,3,1", activity.form.configuration().getString("window_days"));
        activity.form.fields.get("window_start").performClick();
        TimePickerDialog dialog = (TimePickerDialog)ShadowDialog.getLatestDialog();
        dialog.updateTime(23, 15); dialog.getButton(-1).performClick();
        assertEquals("23:15", activity.form.s("window_start")); assertEquals("06:00", activity.form.s("window_end"));
        android.provider.Settings.System.putString(activity.getContentResolver(), android.provider.Settings.System.TIME_12_24, "24");
        assertEquals("23:15", activity.time(activity.form.s("window_start")));
        android.provider.Settings.System.putString(activity.getContentResolver(), android.provider.Settings.System.TIME_12_24, "12");
        String display = activity.time(activity.form.s("window_start")); assertTrue(display.contains("11:15")); assertTrue(display.contains("PM"));
        Chip monday = activity.body.findViewWithTag("day_1");
        assertTrue(monday.isCheckedIconVisible()); assertNotNull(monday.getCheckedIcon()); monday.setChecked(false);
        assertEquals("7,3", activity.form.s("window_days"));
        ((Chip)activity.body.findViewWithTag("day_7")).setChecked(false); ((Chip)activity.body.findViewWithTag("day_3")).setChecked(false);
        activity.save(); assertTrue(sent.isEmpty()); assertEquals(View.VISIBLE, activity.form.dayError.getVisibility());
        assertEquals(activity.getString(R.string.error_invalid_window_days), activity.form.dayError.getText());
        monday.setChecked(true); assertEquals("1", activity.form.configuration().getString("window_days"));
        activity.form.fields.get("timezone").performClick(); AlertDialog zone = (AlertDialog)ShadowDialog.getLatestDialog();
        EditText search = zone.getWindow().getDecorView().findViewWithTag("timezone_search");
        ListView matches = (ListView)views(zone.getWindow().getDecorView()).stream().filter(v -> v instanceof ListView).findFirst().get();
        search.setText("not-a-time-zone"); assertEquals(0, matches.getAdapter().getCount()); assertEquals("system", activity.form.s("timezone"));
        search.setText("Europe/Paris"); assertEquals(1, matches.getAdapter().getCount()); matches.performItemClick(null, 0, 0);
        assertEquals("Europe/Paris", activity.form.configuration().getString("timezone")); assertTrue(sent.isEmpty());
    }
    @Test public void profileChoicesAreScopedWithoutSilentlyReplacingStoredSelection() throws Exception {
        JSONObject status = status(); status.getJSONObject("config").put("profile", "10:42"); open(status); activity.group("device");
        activity.form.fields.get("profile").performClick(); AlertDialog dialog = (AlertDialog)ShadowDialog.getLatestDialog();
        ListAdapter choices = dialog.getListView().getAdapter(); assertEquals(2, choices.getCount());
        assertTrue(choices.getItem(1).toString().contains("Work")); assertFalse(choices.getItem(1).toString().contains("Other")); dialog.dismiss();
        activity.form.put("user", "12:44"); activity.form.update();
        assertEquals("10:42", activity.form.configuration().getString("profile"));
    }
    @Test public void remoteModeShowsOnlyRelevantFieldsAndKeepsExactDraft() throws Exception {
        open(status()); activity.group("remote");
        assertEquals(View.GONE, activity.form.blocks.get("inbox_url").getVisibility());
        assertEquals(View.GONE, activity.form.blocks.get("device_id").getVisibility());
        activity.form.put("inbox_mode", "passphrase"); activity.form.update();
        assertEquals(View.VISIBLE, activity.form.blocks.get("inbox_url").getVisibility());
        assertEquals(View.VISIBLE, activity.form.blocks.get("remote_secret").getVisibility());
        String secret = "  密码 🌙  "; ((EditText)activity.form.fields.get("remote_secret")).setText(secret);
        ((EditText)activity.form.fields.get("inbox_url")).setText("https://example.com/commands/test");
        activity.form.put("inbox_mode", "off"); activity.form.update(); activity.form.put("inbox_mode", "both"); activity.form.update();
        assertEquals(secret, activity.form.configuration().getString("remote_secret"));
        assertEquals(View.VISIBLE, activity.form.blocks.get("public_key").getVisibility());
        assertTrue(sent.isEmpty());
    }
    @Test public void credentialsRequireExplicitRemovalAndCanBeKept() throws Exception {
        JSONObject status = status(); status.getJSONObject("config").put("has_remote_secret", true); open(status); activity.group("remote");
        assertFalse(activity.form.configuration().has("remote_secret"));
        activity.form.clearButtons.get("remote_secret").performClick();
        ((AlertDialog)ShadowDialog.getLatestDialog()).getButton(-1).performClick(); drain();
        assertEquals("", activity.form.configuration().getString("remote_secret"));
        activity.form.clearButtons.get("remote_secret").performClick(); assertFalse(activity.form.configuration().has("remote_secret"));
        assertTrue(sent.isEmpty());
    }
    @Test public void recreationRetainsDraftInMemoryButNeverSavesRawCredentials() throws Exception {
        open(status()); activity.group("remote");
        activity.form.put("inbox_mode", "both"); activity.form.update();
        ((EditText)activity.form.fields.get("device_id")).setText("my-device");
        ((EditText)activity.form.fields.get("remote_secret")).setText("  密码 🌙  ");
        MainActivity.Retained retained = (MainActivity.Retained)activity.onRetainCustomNonConfigurationInstance();
        assertEquals("  密码 🌙  ", retained.draft.draft.getString("remote_secret"));
        Bundle saved = new Bundle(); controller.saveInstanceState(saved);
        assertFalse(saved.getString("draft").contains("密码"));
        SettingsForm.State restored = SettingsForm.State.restore(new JSONObject(saved.getString("draft")));
        assertEquals("my-device", restored.draft.getString("device_id")); assertTrue(restored.reenter.contains("remote_secret")); assertFalse(restored.draft.has("remote_secret"));
        assertFalse(activity.body.isSaveFromParentEnabled());
    }
    @Test public void processRestoredSecretsMustBeReenteredOrExplicitlyKept() throws Exception {
        open(status()); activity.group("panic");
        ((EditText)activity.form.fields.get("panic_secret")).setText("12a34");
        assertEquals("12a34", activity.form.configuration().getString("panic_secret"));
        SettingsForm.State restored = SettingsForm.State.restore(activity.form.state.saved());
        activity.form = new SettingsForm(activity, restored); activity.showForm();
        activity.save(); assertTrue(sent.isEmpty()); assertNotNull(activity.form.inputs.get("panic_secret").getError());
        activity.form.keepSaved.performClick(); assertFalse(activity.form.configuration().has("panic_secret"));
    }
    @Test public void refreshedExternalChangesDoNotGetSilentlyOverwritten() throws Exception {
        JSONObject status = status(); open(status); activity.group("timer");
        ((EditText)activity.form.fields.get("timed_minutes")).setText("45");
        JSONObject changed = new JSONObject(status.toString()); changed.getJSONObject("config").put("profile_failure", "reboot");
        activity.render(changed); activity.save();
        assertTrue(sent.isEmpty()); assertEquals("45", activity.form.s("timed_minutes"));
        assertEquals(activity.getString(R.string.settings_changed), activity.form.error.getText());
    }
    @Test public void settingsPermissionsAreContextualAndIndependent() throws Exception {
        open(status()); activity.support();
        String content = text(activity.body);
        assertTrue(content.contains(activity.getString(R.string.notifications)));
        assertFalse(content.contains(activity.getString(R.string.allow_sms)));
        activity.requestPermission(android.Manifest.permission.RECEIVE_SMS);
        assertArrayEquals(new String[]{android.Manifest.permission.RECEIVE_SMS}, Shadows.shadowOf(activity).getLastRequestedPermission().requestedPermissions);
    }
    @Test public void disconnectedRefreshCannotOverwriteSettings() throws Exception {
        open(status()); activity.group("timer"); ((EditText)activity.form.fields.get("timed_minutes")).setText("246");
        activity.status = null; activity.form.update(); assertFalse(activity.save.isEnabled());
        activity.save(); assertTrue(sent.isEmpty()); assertEquals(activity.getString(R.string.error_engine_unavailable), activity.form.error.getText());
        assertEquals("246", ((EditText)activity.form.fields.get("timed_minutes")).getText().toString());
    }
    @Test public void notificationsUseOnlyEnabledTimerActions() throws Exception {
        JSONObject status = status(); open(status);
        Shadows.shadowOf((android.app.Application)activity.getApplication()).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS);
        NotificationManager manager = activity.getSystemService(NotificationManager.class);
        Controls.notification(activity, status); assertNull(Shadows.shadowOf(manager).getNotification(1));
        status.getJSONObject("config").put("timed_enabled", true); Controls.notification(activity, status);
        assertEquals(1, Shadows.shadowOf(manager).getNotification(1).actions.length);
        status.getJSONObject("config").put("window_enabled", true); Controls.notification(activity, status);
        assertEquals(2, Shadows.shadowOf(manager).getNotification(1).actions.length);
        status.put("snoozed", true); Controls.notification(activity, status);
        assertEquals(activity.getString(R.string.resume), Shadows.shadowOf(manager).getNotification(1).actions[0].title);
        status.getJSONObject("config").put("timed_enabled", false).put("window_enabled", false); Controls.notification(activity, status);
        assertNull(Shadows.shadowOf(manager).getNotification(1));
    }
    @Test @Config(qualifiers = "w840dp-h900dp-night") public void expandedDarkWindowKeepsReadableWidthAndAccessibleRows() throws Exception {
        open(status());
        View decor = activity.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(activity.dp(840), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(activity.dp(900), View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, decor.getMeasuredWidth(), decor.getMeasuredHeight());
        assertTrue(activity.toolbar.getMeasuredWidth() <= activity.dp(720));
        for (View row : views(activity.body)) if (row instanceof MainActivity.Row) {
            android.view.accessibility.AccessibilityNodeInfo info = row.createAccessibilityNodeInfo();
            assertEquals(Button.class.getName(), info.getClassName()); assertTrue(row.isClickable());
            assertTrue(androidx.core.view.ViewCompat.isScreenReaderFocusable(row));
        }
    }
    @Test public void actualActivityRestoreKeepsNonsecretDraftAndRequiresAuthentication() throws Exception {
        JSONObject status = status(); open(status); activity.group("window");
        ((EditText)activity.form.fields.get("window_minutes")).setText("267");
        Bundle bundle = new Bundle(); controller.saveInstanceState(bundle); activity.finish();
        controller = Robolectric.buildActivity(MainActivity.class).create(bundle); activity = controller.get();
        assertFalse(activity.authenticated); assertEquals("window", activity.page);
        activity.authenticated = true; activity.render(status);
        assertEquals("267", ((EditText)activity.form.fields.get("window_minutes")).getText().toString());
        assertEquals(View.VISIBLE, activity.footer.getVisibility()); assertTrue(sent.isEmpty());
    }
    @Test public void lockedAsyncResultCannotRevealSettings() throws Exception {
        open(status()); activity.onStop(); activity.render(status());
        assertFalse(activity.authenticated); assertEquals(View.INVISIBLE, activity.body.getVisibility());
    }
    @Test public void largeTextAndRtlLayoutsHaveNoEllipsizedLabelsOrHorizontalOverflow() throws Exception {
        Configuration config = new Configuration(RuntimeEnvironment.getApplication().getResources().getConfiguration());
        config.setLocales(LocaleList.forLanguageTags("ar-XB")); config.fontScale = 1.8f; config.setLayoutDirection(new Locale("ar"));
        RuntimeEnvironment.getApplication().getResources().updateConfiguration(config, null);
        open(status());
        for (String group : new String[]{"device", "timer", "window", "failures", "panic", "remote"}) {
            activity.group(group);
            int width = activity.dp(320);
            activity.body.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
            activity.body.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            activity.body.layout(0, 0, width, activity.body.getMeasuredHeight());
            assertTrue(activity.body.getMeasuredHeight() > 0);
            for (View view : views(activity.body)) if (view.getVisibility() == View.VISIBLE) {
                assertTrue(group + ": " + view.getClass(), view.getMeasuredWidth() <= width);
                if (view instanceof TextView && ((TextView)view).getLayout() != null) {
                    android.text.Layout layout = ((TextView)view).getLayout();
                    for (int line = 0; line < layout.getLineCount(); line++) assertEquals(group, 0, layout.getEllipsisCount(line));
                }
                if (view instanceof MainActivity.Row) assertTrue(view.getMinimumHeight() >= activity.dp(48));
                if (view instanceof TextInputLayout) {
                    TextInputLayout input = (TextInputLayout)view; assertFalse(input.isHintEnabled());
                    assertTrue(views(activity.body).stream().anyMatch(label -> label.getLabelFor() == input.getEditText().getId()));
                }
            }
        }
    }
}
