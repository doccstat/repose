package com.lixingchi.repose;

import android.content.IntentSender;
import android.content.ComponentName;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.json.JSONObject;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {33, 35})
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class AndroidContractsTest {
    @Test public void namedBinderContractsExist() throws Exception {
        Class<?> users = Class.forName("android.os.IUserManager");
        try { users.getMethod("getUsers", boolean.class, boolean.class, boolean.class); }
        catch (NoSuchMethodException newer) { users.getMethod("getUsers", boolean.class); }
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
    @Test public void panicReturnsARejectedCredentialWithNoAuthenticationToken() throws Exception {
        assertEquals(false, Hooks.credentialFailure(boolean.class));
        Class<?> response = Class.forName("com.android.internal.widget.VerifyCredentialResponse");
        Object denied = Hooks.credentialFailure(response);
        assertEquals(-1, response.getMethod("getResponseCode").invoke(denied));
        assertNull(response.getMethod("getGatekeeperHAT").invoke(denied));
        assertEquals(0L, response.getMethod("getGatekeeperPasswordHandle").invoke(denied));
        try { Hooks.credentialFailure(Object.class); fail(); } catch (NoSuchMethodException expected) { }
    }
    static class Fake extends AndroidDevice {
        Object parent, profile;
        int pauseCalls, listCalls;
        boolean modern, rejectListing, afu, system, requireSystem, acceptPause;
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
        @Override void requestQuietMode(String identity, String parent) throws Exception { enableQuietMode(identity, parent); }
        @Override Object userCall(String name, Class<?>[] types, Object... args) throws Exception {
            switch (name) {
                case "getUsers":
                    listCalls++;
                    for (Object arg : args) assertEquals(true, arg);
                    if (rejectListing) throw new InvocationTargetException(new SecurityException());
                    if (modern && types.length == 3) throw new NoSuchMethodException();
                    assertEquals(modern ? 1 : 3, types.length);
                    return List.of(parent, profile);
                case "isUserUnlocked": return (Integer)args[0] == 10 && afu;
                case "getUserInfo": return ((Integer)args[0]) == 0 ? parent : profile;
                case "getProfileParent": return (Integer)args[0] == 0 ? null : parent;
                case "isQuietModeEnabled": return false;
                case "requestQuietModeEnabled":
                    if (requireSystem) assertTrue(system);
                    assertEquals("android", args[0]); assertEquals(true, args[1]); assertEquals(10, args[2]);
                    assertNull(args[3]); assertEquals(0, args[4]);
                    pauseCalls++; return acceptPause;
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
    @Test public void enumerationContractsServeBothInventoryAndBfu() throws Exception {
        for (boolean modern : new boolean[]{false, true}) {
            Fake d = new Fake(); d.modern = modern;
            assertEquals(2, d.inventory().length());
            assertTrue(d.bfu());
            d.afu = true; assertFalse(d.bfu());
            assertEquals(modern ? 6 : 3, d.listCalls);
        }
    }
    @Test public void enumerationNeverRetriesPermissionOrServiceFailuresAsAnotherApi() throws Exception {
        Fake d = new Fake(); d.rejectListing = true;
        try { d.inventory(); fail(); } catch (InvocationTargetException expected) { }
        assertEquals(1, d.listCalls);
    }
    @Test public void quietModeChangesCallerBeforeAttributedBinderRequest() throws Exception {
        Fake d = new Fake(); d.requireSystem = true; d.acceptPause = true;
        QuietMode.enable(d, "10:12", "0:0", () -> d.system = true);
        assertEquals(1, d.pauseCalls);
    }
    @Test public void quietModeRevalidatesIdentityOnBothSidesOfCallerChange() throws Exception {
        Fake d = new Fake();
        try { QuietMode.enable(d, "10:13", "0:0", () -> d.system = true); fail(); }
        catch (IllegalStateException expected) { assertFalse(d.system); }
        try {
            QuietMode.enable(d, "10:12", "0:0", () -> d.profile = Fake.info(10, 13, "android.os.usertype.profile.MANAGED"));
            fail();
        } catch (IllegalStateException expected) { assertEquals("identity_changed", expected.getMessage()); }
        assertEquals(0, d.pauseCalls);
    }
    @Test public void failedCallerChangeNeverRequestsQuietMode() throws Exception {
        Fake d = new Fake();
        try { QuietMode.enable(d, "10:12", "0:0", () -> { throw new SecurityException(); }); fail(); }
        catch (SecurityException expected) { }
        assertEquals(0, d.pauseCalls);
    }
    @Test public void quietModeRejectionFailsBeforeClaimingPaused() throws Exception {
        Fake d = new Fake();
        try { d.pause("10:12", "0:0"); fail(); } catch (IllegalStateException expected) { assertEquals("profile_pause_rejected", expected.getMessage()); }
        assertEquals(1, d.pauseCalls);
    }
}
