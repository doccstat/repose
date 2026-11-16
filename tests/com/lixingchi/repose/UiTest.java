package com.lixingchi.repose;

import android.content.Intent;
import android.content.res.Configuration;
import android.os.LocaleList;
import android.view.View;
import android.widget.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class UiTest {
    @Test public void observerAssetDefinesOnlyTheAdapterNotPlatformStubs() throws Exception {
        byte[] dex;
        try (java.io.InputStream input = RuntimeEnvironment.getApplication().getAssets().open("keyguard-observer/classes.dex")) {
            dex = input.readAllBytes();
        }
        java.nio.ByteBuffer bytes = java.nio.ByteBuffer.wrap(dex).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        assertEquals("dex\n", new String(dex, 0, 4, java.nio.charset.StandardCharsets.US_ASCII));
        assertEquals(1, bytes.getInt(96));
        int type = bytes.getInt(bytes.getInt(100));
        int name = bytes.getInt(bytes.getInt(68) + 4 * type);
        int offset = bytes.getInt(bytes.getInt(60) + 4 * name);
        while ((dex[offset++] & 128) != 0) { }
        int end = offset; while (dex[end] != 0) end++;
        assertEquals("Lcom/lixingchi/repose/observer/KeyguardObserver;",
                new String(dex, offset, end - offset, java.nio.charset.StandardCharsets.US_ASCII));
    }
    @Test public void settingsGroupsBuildAndAllFieldsHaveLabels() throws Exception {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).create().get();
        JSONObject status = new EngineTest.Harness().engine.status().put("ok", true).put("version", "1.0.0")
            .put("hooks", new JSONObject()).put("users", new JSONArray().put(new JSONObject().put("identity", "0:0").put("name", "Owner").put("type", "android.os.usertype.full.SYSTEM")));
        activity.render(status);
        for (Settings.Field field : Settings.FIELDS) {
            assertNotEquals(0, activity.getResources().getIdentifier("f_" + field.key, "string", activity.getPackageName()));
            for (String choice : field.choices)
                assertNotEquals(0, activity.getResources().getIdentifier("choice_" + field.key + "_" + choice, "string", activity.getPackageName()));
        }
        for (String group : new String[]{"device", "timer", "window", "failures", "panic", "remote"}) {
            activity.group(group);
            android.app.Dialog dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog();
            assertNotNull(dialog); assertTrue(dialog.isShowing()); dialog.dismiss();
        }
        activity.finish();
    }
    @Test public void expandedRtlLayoutAndCredentialFieldsRemainVerbatim() throws Exception {
        Configuration config = new Configuration(RuntimeEnvironment.getApplication().getResources().getConfiguration());
        config.setLocales(LocaleList.forLanguageTags("ar-XB")); config.fontScale = 1.8f;
        RuntimeEnvironment.getApplication().getResources().updateConfiguration(config, null);
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).create().get();
        LinearLayout fields = activity.column();
        String value = "  密码 🌙  ";
        EditText input = activity.input(fields, activity.getString(R.string.f_remote_secret), value, android.text.InputType.TYPE_CLASS_TEXT);
        assertEquals(value, input.getText().toString());
        fields.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.AT_MOST));
        fields.layout(0, 0, fields.getMeasuredWidth(), fields.getMeasuredHeight());
        assertTrue(fields.getMeasuredHeight() > 0);
        assertTrue(activity.getString(R.string.group_remote).length() > 0);
        activity.finish();
    }
}
