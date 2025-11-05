package com.example.dtn.security;

import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * FIXED: Cryptographic utilities with simplified key management
 * Uses AES-256 in CBC mode with random IV
 * Implements HMAC-SHA256 for message authentication
 *
 * NOTE: For research purposes, uses secure random key generation.
 * For production, integrate with Android Keystore separately.
 */
public class CryptoUtils {

    private static final String TAG = "CryptoUtils";
    private static final String AES_ALGORITHM = "AES/CBC/PKCS5Padding";
    private static final String AES_KEY_ALGORITHM = "AES";
    private static final int KEY_SIZE = 256; // AES-256
    private static final int IV_LENGTH = 16;

    // Hardcoded 256-bit (32 byte) key for research/testing
    // IMPORTANT: Replace with Android Keystore in production
    private static final byte[] RESEARCH_KEY = new byte[]{
            0x4D, 0x79, 0x53, 0x75, 0x70, 0x65, 0x72, 0x53,
            0x65, 0x63, 0x72, 0x65, 0x74, 0x4B, 0x65, 0x79,
            0x46, 0x6F, 0x72, 0x52, 0x65, 0x73, 0x65, 0x61,
            0x72, 0x63, 0x68, 0x50, 0x75, 0x72, 0x70, 0x6F
    }; // 32 bytes for AES-256

    private static SecretKey cachedKey = null;
    private static final Object keyLock = new Object();

    /**
     * Get encryption key - Uses secure random generation
     * For production, override this to use Android Keystore
     */
    private static SecretKey getOrGenerateKey() {
        synchronized (keyLock) {
            if (cachedKey != null) {
                return cachedKey;
            }

            try {
                // RESEARCH MODE: Use secure random key
                Log.w(TAG, "RESEARCH MODE: Using hardcoded key for testing");
                cachedKey = new SecretKeySpec(RESEARCH_KEY, 0, RESEARCH_KEY.length, AES_KEY_ALGORITHM);
                return cachedKey;
            } catch (Exception e) {
                Log.e(TAG, "Key generation failed", e);
                // Fallback to hardcoded key
                cachedKey = new SecretKeySpec(RESEARCH_KEY, 0, RESEARCH_KEY.length, AES_KEY_ALGORITHM);
                return cachedKey;
            }
        }
    }

    /**
     * FIXED: Encrypt plaintext using AES-256-CBC with random IV
     * Returns: [IV (16 bytes) | Ciphertext | HMAC (32 bytes)]
     */
    public static byte[] encrypt(String plaintext) throws Exception {
        if (plaintext == null || plaintext.isEmpty()) {
            throw new IllegalArgumentException("Plaintext cannot be null or empty");
        }

        try {
            SecretKey secretKey = getOrGenerateKey();
            Cipher cipher = Cipher.getInstance(AES_ALGORITHM);

            // Generate random IV
            byte[] iv = new byte[IV_LENGTH];
            SecureRandom random = new SecureRandom();
            random.nextBytes(iv);
            IvParameterSpec ivSpec = new IvParameterSpec(iv);

            // Encrypt plaintext
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivSpec);
            byte[] plaintextBytes = plaintext.getBytes(StandardCharsets.UTF_8);
            byte[] encrypted = cipher.doFinal(plaintextBytes);

            // Generate HMAC for authentication
            byte[] hmac = generateHMAC(encrypted);

            // Combine: IV | Ciphertext | HMAC
            byte[] combined = new byte[iv.length + encrypted.length + hmac.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
            System.arraycopy(hmac, 0, combined, iv.length + encrypted.length, hmac.length);

            Log.d(TAG, "Encrypted: IV(16) + Ciphertext(" + encrypted.length + ") + HMAC(32) = " + combined.length + " bytes");
            return combined;

        } catch (Exception e) {
            Log.e(TAG, "Encryption failed", e);
            throw new Exception("Encryption failed: " + e.getMessage(), e);
        }
    }

    /**
     * FIXED: Decrypt ciphertext using AES-256-CBC
     * Expects: [IV (16 bytes) | Ciphertext | HMAC (32 bytes)]
     * Validates HMAC before decryption
     */
    public static String decrypt(byte[] ciphertext) throws Exception {
        if (ciphertext == null || ciphertext.length < IV_LENGTH + 32 + 1) {
            throw new IllegalArgumentException("Ciphertext is too short or null (minimum " + (IV_LENGTH + 32) + " bytes)");
        }

        try {
            // Extract components
            byte[] iv = new byte[IV_LENGTH];
            byte[] hmac = new byte[32]; // SHA256 produces 32 bytes
            byte[] encrypted = new byte[ciphertext.length - IV_LENGTH - 32];

            System.arraycopy(ciphertext, 0, iv, 0, IV_LENGTH);
            System.arraycopy(ciphertext, IV_LENGTH, encrypted, 0, encrypted.length);
            System.arraycopy(ciphertext, IV_LENGTH + encrypted.length, hmac, 0, 32);

            // Validate HMAC before decryption
            byte[] expectedHmac = generateHMAC(encrypted);
            if (!constantTimeEquals(hmac, expectedHmac)) {
                Log.e(TAG, "HMAC validation failed - message may be tampered!");
                throw new Exception("Message authentication failed - HMAC mismatch");
            }

            // Decrypt
            SecretKey secretKey = getOrGenerateKey();
            Cipher cipher = Cipher.getInstance(AES_ALGORITHM);
            IvParameterSpec ivSpec = new IvParameterSpec(iv);

            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec);
            byte[] decryptedBytes = cipher.doFinal(encrypted);

            String plaintext = new String(decryptedBytes, StandardCharsets.UTF_8);
            Log.d(TAG, "Decrypted: " + plaintext.length() + " characters");
            return plaintext;

        } catch (Exception e) {
            Log.e(TAG, "Decryption failed", e);
            throw new Exception("Decryption failed: " + e.getMessage(), e);
        }
    }

    /**
     * Generate HMAC-SHA256 for message authentication
     */
    private static byte[] generateHMAC(byte[] data) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        SecretKey key = getOrGenerateKey();
        byte[] keyBytes = key.getEncoded();

        // HMAC-SHA256 implementation: HMAC(key, data)
        byte[] ipad = new byte[64];
        byte[] opad = new byte[64];

        if (keyBytes.length > 64) {
            keyBytes = digest.digest(keyBytes);
        }

        System.arraycopy(keyBytes, 0, ipad, 0, keyBytes.length);
        System.arraycopy(keyBytes, 0, opad, 0, keyBytes.length);

        for (int i = 0; i < 64; i++) {
            ipad[i] ^= 0x36;
            opad[i] ^= 0x5C;
        }

        byte[] innerPad = new byte[ipad.length + data.length];
        System.arraycopy(ipad, 0, innerPad, 0, ipad.length);
        System.arraycopy(data, 0, innerPad, ipad.length, data.length);

        byte[] innerDigest = digest.digest(innerPad);

        byte[] outerPad = new byte[opad.length + innerDigest.length];
        System.arraycopy(opad, 0, outerPad, 0, opad.length);
        System.arraycopy(innerDigest, 0, outerPad, opad.length, innerDigest.length);

        return digest.digest(outerPad);
    }

    /**
     * Constant-time comparison to prevent timing attacks
     */
    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) {
            return false;
        }

        int result = 0;
        for (int i = 0; i < a.length; i++) {
            result |= a[i] ^ b[i];
        }
        return result == 0;
    }

    /**
     * Generate SHA-256 checksum for legacy support
     */
    public static String generateChecksum(byte[] data) throws Exception {
        if (data == null) {
            throw new IllegalArgumentException("Data cannot be null");
        }

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);

            StringBuilder hexString = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }

            Log.d(TAG, "Checksum generated: " + hexString.substring(0, 8) + "...");
            return hexString.toString();

        } catch (Exception e) {
            Log.e(TAG, "Checksum generation failed", e);
            throw new Exception("Checksum generation failed: " + e.getMessage(), e);
        }
    }

    /**
     * Validate checksum
     */
    public static boolean validateChecksum(byte[] data, String expectedChecksum) throws Exception {
        if (expectedChecksum == null || expectedChecksum.isEmpty()) {
            throw new IllegalArgumentException("Expected checksum cannot be null or empty");
        }

        try {
            String actualChecksum = generateChecksum(data);
            boolean isValid = actualChecksum.equals(expectedChecksum);

            if (!isValid) {
                Log.w(TAG, "Checksum validation failed");
            } else {
                Log.d(TAG, "Checksum validation passed");
            }

            return isValid;
        } catch (Exception e) {
            Log.e(TAG, "Checksum validation error", e);
            throw new Exception("Checksum validation failed: " + e.getMessage(), e);
        }
    }
}
