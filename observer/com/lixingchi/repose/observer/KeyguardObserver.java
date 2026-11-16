package com.lixingchi.repose.observer;

import android.hardware.biometrics.BiometricSourceType;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import java.util.function.Consumer;

/** Loaded with SystemUI's class loader; one observer receives each failure once. */
public final class KeyguardObserver extends KeyguardUpdateMonitorCallback {
    private Consumer<String> failure;
    public KeyguardObserver(Consumer<String> failure) { initialize(failure); }
    public void initialize(Consumer<String> failure) { this.failure = failure; }
    @Override public void onBiometricAuthFailed(BiometricSourceType type) { failure.accept(type.name()); }
}
