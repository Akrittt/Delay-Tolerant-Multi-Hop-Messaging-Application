package com.example.dtn.security;

import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Cryptographic utilities for message encryption and integrity verification.
 * Uses AES-128 in CBC mode with PKCS5 padding for encryption.
 * Uses SHA-256 for checksum generation.
 *
 * IMPORTANT: This implementation uses a hardcoded key for research/testing purposes only.
 * In production, use Android Keystore or secure key derivation.
 */
public class CryptoUtils {

    private static final String TAG = "CryptoUtils";

    // IMPORTANT: In a real app, never hardcode keys. This is for project simplicity.
    private static final String AES_ALGORITHM = "AES/CBC/PKCS5Padding"; // FIXED: Specify CBC mode explicitly
    private static final String AES_KEY_ALGORITHM = "AES";
    private static final int IV_LENGTH = 16; // 128 bits for AES

    // Hardcoded 128-bit (16 byte) key - "MySuperSecretKey" in hex
    private static final byte[] KEY = new byte[]{
            0x4D, 0x79, 0x53, 0x75, 0x70, 0x65, 0x72, 0x53,
            0x65, 0x63, 0x72, 0x65, 0x74, 0x4B, 0x65, 0x79
    };

    /**
     * Encrypt plaintext using AES-128-CBC with random IV.
     * The IV is prepended to the ciphertext for transmission.
     *
     * @param plaintext The text to encrypt
     * @return Byte array containing [IV (16 bytes) | Ciphertext]
     * @throws Exception If encryption fails
     */
    public static byte[] encrypt(String plaintext) throws Exception {
        if (plaintext == null || plaintext.isEmpty()) {
            throw new IllegalArgumentException("Plaintext cannot be null or empty");
        }

        try {
            SecretKeySpec secretKey = new SecretKeySpec(KEY, AES_KEY_ALGORITHM);
            Cipher cipher = Cipher.getInstance(AES_ALGORITHM);

            // Generate random IV using system-seeded SecureRandom
            byte[] iv = new byte[IV_LENGTH];
            SecureRandom random = new SecureRandom(); // Uses system entropy automatically
            random.nextBytes(iv);
            IvParameterSpec ivSpec = new IvParameterSpec(iv);

            // Encrypt the plaintext
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivSpec);
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            // Prepend IV to ciphertext for transmission
            byte[] combined = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);

            Log.d(TAG, "Encrypted message - IV + Ciphertext length: " + combined.length + " bytes");
            return combined;
        } catch (Exception e) {
            Log.e(TAG, "Encryption failed", e);
            throw new Exception("Encryption failed: " + e.getMessage(), e);
        }
    }

    /**
     * Decrypt ciphertext using AES-128-CBC.
     * Expects the IV to be prepended to the ciphertext.
     *
     * @param ciphertext Byte array containing [IV (16 bytes) | Ciphertext]
     * @return Decrypted plaintext string
     * @throws Exception If decryption fails
     */
    public static String decrypt(byte[] ciphertext) throws Exception {
        if (ciphertext == null || ciphertext.length < IV_LENGTH + 1) {
            throw new IllegalArgumentException("Ciphertext is too short or null");
        }

        try {
            SecretKeySpec secretKey = new SecretKeySpec(KEY, AES_KEY_ALGORITHM);
            Cipher cipher = Cipher.getInstance(AES_ALGORITHM);

            // Extract IV from first 16 bytes
            byte[] iv = new byte[IV_LENGTH];
            byte[] encrypted = new byte[ciphertext.length - IV_LENGTH];
            System.arraycopy(ciphertext, 0, iv, 0, IV_LENGTH);
            System.arraycopy(ciphertext, IV_LENGTH, encrypted, 0, encrypted.length);

            // Decrypt the ciphertext
            IvParameterSpec ivSpec = new IvParameterSpec(iv);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec);
            byte[] decryptedBytes = cipher.doFinal(encrypted);

            String plaintext = new String(decryptedBytes, StandardCharsets.UTF_8);
            Log.d(TAG, "Decrypted message - Plaintext length: " + plaintext.length() + " characters");
            return plaintext;
        } catch (Exception e) {
            Log.e(TAG, "Decryption failed", e);
            throw new Exception("Decryption failed: " + e.getMessage(), e);
        }
    }

    /**
     * Generate SHA-256 checksum for data integrity verification.
     *
     * @param data The data to hash
     * @return Hex string representation of the SHA-256 hash
     * @throws Exception If checksum generation fails
     */
    public static String generateChecksum(byte[] data) throws Exception {
        if (data == null) {
            throw new IllegalArgumentException("Data cannot be null");
        }

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);

            // Convert byte array to hex string
            StringBuilder hexString = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }

            return hexString.toString();
        } catch (Exception e) {
            Log.e(TAG, "Checksum generation failed", e);
            throw new Exception("Checksum generation failed: " + e.getMessage(), e);
        }
    }

    /**
     * Validate data integrity by comparing checksums.
     *
     * @param data The data to validate
     * @param expectedChecksum The expected SHA-256 checksum
     * @return true if checksums match, false otherwise
     * @throws Exception If validation fails
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
            }

            return isValid;
        } catch (Exception e) {
            Log.e(TAG, "Checksum validation failed", e);
            throw new Exception("Checksum validation failed: " + e.getMessage(), e);
        }
    }
}
