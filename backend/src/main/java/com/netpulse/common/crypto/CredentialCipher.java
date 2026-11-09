package com.netpulse.common.crypto;

import com.netpulse.common.config.NetPulseProperties;
import com.netpulse.common.error.ApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Encrypts stored secrets (SNMP credentials, webhook URLs) with AES-256-GCM.
 *
 * <p>GCM gives confidentiality and integrity: a modified ciphertext fails to decrypt instead of yielding garbage.
 * A fresh random 96-bit IV per message is required, because reusing an IV with the same key breaks GCM completely.
 * The IV is not secret, so it is stored in front of the ciphertext.
 *
 * <p>The associated data binds a ciphertext to its owner (for example the device id). Copying one device's
 * encrypted credentials onto another row in the database therefore makes decryption fail.
 *
 * <p>Without a key the application still runs; only saving a secret is refused.
 */
@Component
public class CredentialCipher {
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    @Autowired
    public CredentialCipher(NetPulseProperties props) {
        this(props.credentialEncryptionKey());
    }

    CredentialCipher(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            key = null;
            return;
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("CREDENTIAL_ENCRYPTION_KEY must be valid base64");
        }
        if (raw.length != 32) throw new IllegalStateException("CREDENTIAL_ENCRYPTION_KEY must decode to exactly 32 bytes");
        key = new SecretKeySpec(raw, "AES");
    }

    public boolean isConfigured() { return key != null; }

    public String encrypt(String plaintext, String associatedData) {
        requireKey();
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            c.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            byte[] ct = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = Arrays.copyOf(iv, iv.length + ct.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    public String decrypt(String stored, String associatedData) {
        requireKey();
        try {
            byte[] in = Base64.getDecoder().decode(stored);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, in, 0, IV_BYTES));
            c.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            return new String(c.doFinal(in, IV_BYTES, in.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Stored secret could not be decrypted (wrong key or tampered data)");
        }
    }

    private void requireKey() {
        if (key == null) throw ApiException.conflict("Set CREDENTIAL_ENCRYPTION_KEY on the server before saving credentials.");
    }
}
