package com.lixingchi.repose;

import android.hardware.biometrics.BiometricSourceType;
import com.lixingchi.repose.observer.KeyguardObserver;
import java.util.*;
import java.util.function.Consumer;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

public class ObserverTest {
    @Test public void oneObserverForwardsDistinctAttemptsOfTheSameKind() {
        List<String> events = new ArrayList<>();
        KeyguardObserver observer;
        try { observer = (KeyguardObserver)ObserverFactory.create(KeyguardObserver.class, events::add); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        observer.onBiometricAuthFailed(BiometricSourceType.FINGERPRINT);
        observer.onBiometricAuthFailed(BiometricSourceType.FINGERPRINT);
        observer.onBiometricAuthFailed(BiometricSourceType.FACE);
        assertEquals(List.of("FINGERPRINT", "FINGERPRINT", "FACE"), events);
    }
    @Test public void constructorErasureStillAllowsTheStatelessCallback() throws Exception {
        ClassLoader loader = new ClassLoader(ObserverTest.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.equals(KeyguardObserver.class.getName()) && !name.equals("com.android.keyguard.KeyguardUpdateMonitorCallback"))
                    return super.loadClass(name, resolve);
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    try (InputStream input = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                        byte[] bytes = input.readAllBytes();
                        if (name.endsWith("KeyguardUpdateMonitorCallback")) bytes = eraseConstructor(bytes);
                        loaded = defineClass(name, bytes, 0, bytes.length);
                    } catch (IOException error) { throw new ClassNotFoundException(name, error); }
                }
                if (resolve) resolveClass(loaded);
                return loaded;
            }
        };
        Class<?> type = loader.loadClass(KeyguardObserver.class.getName());
        assertEquals(0, type.getSuperclass().getDeclaredConstructors().length);
        List<String> events = new ArrayList<>();
        Object observer = ObserverFactory.create(type, events::add);
        type.getMethod("onBiometricAuthFailed", BiometricSourceType.class).invoke(observer, BiometricSourceType.FACE);
        assertEquals(List.of("FACE"), events);
    }
    public static class ParameterizedBase { public ParameterizedBase(int value) { } }
    public static class ParameterizedObserver extends ParameterizedBase {
        public ParameterizedObserver(Consumer<String> failure) { super(1); }
    }
    @Test public void anUnknownConstructorContractIsNotBypassed() throws Exception {
        try { ObserverFactory.create(ParameterizedObserver.class, event -> fail()); fail(); }
        catch (NoSuchMethodException expected) { }
    }
    static byte[] eraseConstructor(byte[] input) throws IOException {
        ByteBuffer bytes = ByteBuffer.wrap(input); bytes.position(8);
        String[] constants = new String[Short.toUnsignedInt(bytes.getShort())];
        for (int i = 1; i < constants.length; i++) {
            int tag = Byte.toUnsignedInt(bytes.get());
            int length;
            switch (tag) {
                case 1:
                    byte[] text = new byte[Short.toUnsignedInt(bytes.getShort())]; bytes.get(text);
                    constants[i] = new String(text, StandardCharsets.UTF_8); continue;
                case 3: case 4: case 9: case 10: case 11: case 12: case 17: case 18: length = 4; break;
                case 5: case 6: length = 8; i++; break;
                case 7: case 8: case 16: case 19: case 20: length = 2; break;
                case 15: length = 3; break;
                default: throw new AssertionError(tag);
            }
            bytes.position(bytes.position() + length);
        }
        bytes.position(bytes.position() + 6);
        int interfaces = Short.toUnsignedInt(bytes.getShort()); bytes.position(bytes.position() + 2 * interfaces);
        int fields = Short.toUnsignedInt(bytes.getShort());
        for (int i = 0; i < fields; i++) { bytes.position(bytes.position() + 6); skipAttributes(bytes); }
        int countOffset = bytes.position(), count = Short.toUnsignedInt(bytes.getShort());
        ByteArrayOutputStream output = new ByteArrayOutputStream(); output.write(input, 0, countOffset);
        new DataOutputStream(output).writeShort(count - 1);
        int erased = 0;
        for (int i = 0; i < count; i++) {
            int start = bytes.position(); bytes.getShort();
            String name = constants[Short.toUnsignedInt(bytes.getShort())]; bytes.getShort(); skipAttributes(bytes);
            if (name.equals("<init>")) erased++;
            else output.write(input, start, bytes.position() - start);
        }
        assertEquals(1, erased);
        output.write(input, bytes.position(), input.length - bytes.position());
        return output.toByteArray();
    }
    static void skipAttributes(ByteBuffer bytes) {
        int count = Short.toUnsignedInt(bytes.getShort());
        for (int i = 0; i < count; i++) { bytes.getShort(); int length = bytes.getInt(); bytes.position(bytes.position() + length); }
    }
}
