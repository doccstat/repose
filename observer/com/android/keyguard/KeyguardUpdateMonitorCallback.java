package com.android.keyguard;

import android.hardware.biometrics.BiometricSourceType;

/** Compile-only contract; never packaged. SystemUI supplies the real superclass. */
public abstract class KeyguardUpdateMonitorCallback {
    public void onBiometricAuthFailed(BiometricSourceType type) { }
}
