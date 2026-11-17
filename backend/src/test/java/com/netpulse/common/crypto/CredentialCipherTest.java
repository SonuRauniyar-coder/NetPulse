package com.netpulse.common.crypto;

import com.netpulse.common.error.ApiException;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CredentialCipherTest {
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private final CredentialCipher cipher = new CredentialCipher(KEY);

    @Test void roundTrip() {
        assertThat(cipher.decrypt(cipher.encrypt("s3cret", "device-1"), "device-1")).isEqualTo("s3cret");
    }

    @Test void ciphertextHidesThePlaintextAndUsesAFreshIv() {
        String a = cipher.encrypt("s3cret", "d");
        assertThat(a).doesNotContain("s3cret").isNotEqualTo(cipher.encrypt("s3cret", "d"));
    }

    @Test void tamperingIsDetected() {
        byte[] raw = Base64.getDecoder().decode(cipher.encrypt("s3cret", "d"));
        raw[raw.length - 1] ^= 1;
        assertThatThrownBy(() -> cipher.decrypt(Base64.getEncoder().encodeToString(raw), "d")).isInstanceOf(IllegalStateException.class);
    }

    @Test void ciphertextIsBoundToItsOwner() {
        String forA = cipher.encrypt("s3cret", "device-a");
        assertThatThrownBy(() -> cipher.decrypt(forA, "device-b")).isInstanceOf(IllegalStateException.class);
    }

    @Test void wrongKeyFails() {
        String ct = cipher.encrypt("s3cret", "d");
        byte[] other = new byte[32];
        other[0] = 1;
        assertThatThrownBy(() -> new CredentialCipher(Base64.getEncoder().encodeToString(other)).decrypt(ct, "d")).isInstanceOf(IllegalStateException.class);
    }

    @Test void missingKeyRefusesToStoreSecrets() {
        CredentialCipher none = new CredentialCipher("");
        assertThat(none.isConfigured()).isFalse();
        assertThatThrownBy(() -> none.encrypt("x", "d")).isInstanceOf(ApiException.class);
    }

    @Test void badKeyLengthFailsAtStartup() {
        assertThatThrownBy(() -> new CredentialCipher(Base64.getEncoder().encodeToString(new byte[16]))).isInstanceOf(IllegalStateException.class);
    }
}
