package com.lixingchi.repose;

import org.json.JSONObject;
import org.junit.Test;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import static org.junit.Assert.*;

public class CryptoTest {
    static KeyPair keys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC"); generator.initialize(new ECGenParameterSpec("secp256r1")); return generator.generateKeyPair();
    }
    static String pem(PublicKey key) {
        return "-----BEGIN PUBLIC KEY-----\n" + Base64.getEncoder().encodeToString(key.getEncoded()) + "\n-----END PUBLIC KEY-----";
    }
    static String command(KeyPair key, String device, long issued, long expiry, String nonce) throws Exception {
        String body = "R1|" + device + "|" + issued + "|" + expiry + "|" + nonce;
        Signature signature = Signature.getInstance("SHA256withECDSA"); signature.initSign(key.getPrivate()); signature.update(body.getBytes(StandardCharsets.US_ASCII));
        return body + "|" + Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());
    }
    @Test public void verifierPreservesUnicodeWhitespaceAndUsesRandomSalt() throws Exception {
        String input = " 密码 🌙 ", first = Crypto.verifier(input), second = Crypto.verifier(input);
        assertNotEquals(first, second); assertTrue(Crypto.matches(input, first)); assertFalse(Crypto.matches(input.trim(), first));
        assertFalse(Crypto.matches("", first));
    }
    @Test public void acceptsSignedDeviceBoundCommand() throws Exception {
        KeyPair key = keys(); String nonce = "0123456789abcdef0123456789abcdef";
        String command = command(key, "phone", 1000, 1300, nonce);
        assertEquals(nonce, Crypto.verify(command, "phone", pem(key.getPublic()), 1100, 300).nonce);
        for (String invalid : new String[]{command + "\n", command.replace("phone", "other"), command.replace("1300", "1400"), command.replace("R1", "R2")}) {
            try { Crypto.verify(invalid, "phone", pem(key.getPublic()), 1100, 300); fail(); } catch (Exception expected) { }
        }
        try { Crypto.verify(command, "other", pem(key.getPublic()), 1100, 300); fail(); } catch (Exception expected) { }
        try { Crypto.verify(command, "phone", pem(keys().getPublic()), 1100, 300); fail(); } catch (Exception expected) { }
    }
    @Test public void rejectsFutureExpiredAndOverlongLifetime() throws Exception {
        KeyPair key = keys(); String nonce = "0123456789abcdef0123456789abcdef";
        for (long[] times : new long[][]{{1101, 1200}, {1000, 1100}, {1000, 1401}, {1000, 1000}}) {
            try { Crypto.verify(command(key, "phone", times[0], times[1], nonce), "phone", pem(key.getPublic()), 1100, 300); fail(); }
            catch (Exception expected) { }
        }
    }
    @Test public void rejectsReplayAcrossPersistenceAndClockRollback() throws Exception {
        Crypto.Command command = new Crypto.Command("0123456789abcdef0123456789abcdef", 1300);
        JSONObject saved = Crypto.reserve(new JSONObject(), command, 1100);
        try { Crypto.reserve(new JSONObject(saved.toString()), command, 1101); fail(); } catch (IllegalArgumentException expected) { }
        try { Crypto.reserve(saved, new Crypto.Command("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", 1400), 1099); fail(); } catch (IllegalArgumentException expected) { }
        JSONObject next = Crypto.reserve(saved, new Crypto.Command("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", 1500), 1400);
        assertFalse(next.getJSONObject("nonces").has(command.nonce));
    }
    @Test public void rejectsOtherCurves() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC"); generator.initialize(new ECGenParameterSpec("secp384r1"));
        try { Crypto.publicKey(pem(generator.generateKeyPair().getPublic())); fail(); } catch (IllegalArgumentException expected) { }
    }
    @Test public void remoteReplayRemainsConsumedAfterFailedAction() throws Exception {
        EngineTest.Harness h = new EngineTest.Harness(); KeyPair key = keys();
        h.configure("sms_mode", "signed", "device_id", "phone", "public_key", pem(key.getPublic()), "profile", "10:12");
        h.pauseFails = true;
        long now = h.wall().getEpochSecond(); String command = command(key, "phone", now, now + 300, "0123456789abcdef0123456789abcdef");
        assertTrue(h.engine.remote("sms", command)); h.execute(); assertEquals(0, h.reboots);
        h.engine.acknowledge(); h.restart("boot-a");
        try { h.engine.remote("sms", command); fail(); } catch (IllegalArgumentException expected) { }
        assertTrue(h.actions.isEmpty());
    }
}
