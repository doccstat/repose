package com.lixingchi.repose;

import android.content.IntentSender;
import android.content.ComponentName;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.json.JSONObject;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class AndroidContractsTest {
    @Test public void namedBinderContractsExist() throws Exception {
        Class<?> users = Class.forName("android.os.IUserManager");
        users.getMethod("getUsers", boolean.class, boolean.class, boolean.class);
        users.getMethod("getUserInfo", int.class);
        users.getMethod("getProfileParent", int.class);
        users.getMethod("isUserUnlocked", int.class);
        users.getMethod("isQuietModeEnabled", int.class);
        users.getMethod("requestQuietModeEnabled", String.class, boolean.class, int.class, IntentSender.class, int.class);
        Class.forName("android.app.IActivityManager").getMethod("isUserRunning", int.class, int.class);
        Class.forName("android.app.admin.IDevicePolicyManager").getMethod("isAdminActive", ComponentName.class, int.class);
        Class<?> trust = Class.forName("android.app.trust.ITrustManager");
        try { trust.getMethod("isDeviceLocked", int.class, int.class); }
        catch (NoSuchMethodException old) { trust.getMethod("isDeviceLocked", int.class); }
    }
    static class Fake extends AndroidDevice {
        Object parent, profile;
        int pauseCalls;
        Fake() throws Exception {
            super(RuntimeEnvironment.getApplication());
            parent = info(0, 0, "android.os.usertype.full.SYSTEM");
            profile = info(10, 12, "android.os.usertype.profile.MANAGED");
        }
        static Object info(int id, int serial, String type) throws Exception {
            Class<?> cls = Class.forName("android.content.pm.UserInfo");
            Object object = cls.getConstructor(int.class, String.class, int.class).newInstance(id, "name", 0);
            cls.getField("serialNumber").setInt(object, serial);
            cls.getField("userType").set(object, type);
            return object;
        }
        @Override Object userCall(String name, Class<?>[] types, Object... args) throws Exception {
            switch (name) {
                case "getUserInfo": return ((Integer)args[0]) == 0 ? parent : profile;
                case "getProfileParent": return parent;
                case "isQuietModeEnabled": return false;
                case "requestQuietModeEnabled": pauseCalls++; return false;
                default: throw new AssertionError(name);
            }
        }
    }
    @Test public void profileMutationRejectsReusedIdsWrongTypeAndWrongParent() throws Exception {
        Fake d = new Fake();
        d.validate(new Settings(new JSONObject().put("profile", "10:12")));
        for (Object bad : new Object[]{Fake.info(10, 13, "android.os.usertype.profile.MANAGED"), Fake.info(10, 12, "android.os.usertype.profile.CLONE")}) {
            d.profile = bad;
            try { d.pause("10:12", "0:0"); fail(); } catch (IllegalStateException expected) { }
            assertEquals(0, d.pauseCalls);
        }
        d.profile = Fake.info(10, 12, "android.os.usertype.profile.MANAGED"); d.parent = Fake.info(1, 1, "android.os.usertype.full.SECONDARY");
        try { d.pause("10:12", "0:0"); fail(); } catch (IllegalStateException expected) { }
        assertEquals(0, d.pauseCalls);
    }
    @Test public void quietModeRejectionFailsBeforeClaimingPaused() throws Exception {
        Fake d = new Fake();
        try { d.pause("10:12", "0:0"); fail(); } catch (IllegalStateException expected) { assertEquals("profile_pause_rejected", expected.getMessage()); }
        assertEquals(1, d.pauseCalls);
    }
}
