package com.lixingchi.repose;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.ECPublicKey;
import java.security.spec.*;
import java.util.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.json.JSONObject;

public final class Crypto {
    private static final Base64.Encoder ENCODE = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODE = Base64.getUrlDecoder();
    private static final int ITERATIONS = 210000;
    public static String verifier(String secret) throws Exception {
        if (secret.isEmpty() || secret.length() > 256) throw new IllegalArgumentException("invalid_secret");
        byte[] salt = new byte[16]; new SecureRandom().nextBytes(salt);
        return ENCODE.encodeToString(salt) + "." + ENCODE.encodeToString(derive(secret, salt));
    }
    private static byte[] derive(String secret, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(secret.toCharArray(), salt, ITERATIONS, 256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        finally { spec.clearPassword(); }
    }
    public static boolean matches(String secret, String verifier) throws Exception {
        if (secret.length() > 256 || verifier.isEmpty()) return false;
        String[] p = verifier.split("\\.", -1);
        if (p.length != 2) throw new IllegalArgumentException("invalid_verifier");
        return MessageDigest.isEqual(DECODE.decode(p[1]), derive(secret, DECODE.decode(p[0])));
    }
    public static PublicKey publicKey(String pem) throws Exception {
        String body = pem.replace("-----BEGIN PUBLIC KEY-----", "").replace("-----END PUBLIC KEY-----", "").replaceAll("\\s", "");
        PublicKey key = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(body)));
        AlgorithmParameters p = AlgorithmParameters.getInstance("EC");
        p.init(new ECGenParameterSpec("secp256r1"));
        ECParameterSpec expected = p.getParameterSpec(ECParameterSpec.class), actual = ((ECPublicKey)key).getParams();
        if (!actual.getCurve().equals(expected.getCurve()) || !actual.getGenerator().equals(expected.getGenerator())
                || !actual.getOrder().equals(expected.getOrder()) || actual.getCofactor() != expected.getCofactor())
            throw new IllegalArgumentException("invalid_public_key");
        return key;
    }
    public static final class Command {
        public final String nonce;
        public final long expires;
        Command(String nonce, long expires) { this.nonce = nonce; this.expires = expires; }
    }
    public static Command verify(String message, String device, String key, long now, long ttl) throws Exception {
        if (message.length() > 1024) throw new IllegalArgumentException("invalid_command");
        String[] p = message.split("\\|", -1);
        if (p.length != 6 || !p[0].equals("R1") || device.isEmpty() || !p[1].equals(device)
                || !p[2].matches("[0-9]{1,12}") || !p[3].matches("[0-9]{1,12}")
                || !p[4].matches("[0-9a-f]{32}") || !p[5].matches("[A-Za-z0-9_-]{80,100}"))
            throw new IllegalArgumentException("invalid_command");
        long issued = Long.parseLong(p[2]), expires = Long.parseLong(p[3]);
        if (issued > now || expires <= now || expires <= issued || expires - issued > ttl)
            throw new IllegalArgumentException("expired_command");
        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(publicKey(key));
        verifier.update(message.substring(0, message.lastIndexOf('|')).getBytes(StandardCharsets.US_ASCII));
        if (!verifier.verify(DECODE.decode(p[5]))) throw new IllegalArgumentException("invalid_signature");
        return new Command(p[4], expires);
    }
    /** Reserve before performing any action; caller must durably commit the returned ledger. */
    public static JSONObject reserve(JSONObject ledger, Command command, long now) throws Exception {
        if (now < ledger.optLong("wall", 0)) throw new IllegalArgumentException("clock_rollback");
        JSONObject old = ledger.optJSONObject("nonces"), next = new JSONObject();
        if (old != null) {
            Iterator<String> keys = old.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                long expiry = old.getLong(key);
                if (expiry > now) next.put(key, expiry);
            }
        }
        if (next.has(command.nonce)) throw new IllegalArgumentException("replayed_command");
        if (next.length() >= 4096) throw new IllegalArgumentException("replay_store_full");
        next.put(command.nonce, command.expires);
        return new JSONObject().put("wall", now).put("nonces", next);
    }
    private Crypto() { }
}
