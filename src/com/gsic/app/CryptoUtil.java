package com.gsic.app;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * v3.13 Issue 2：Android Keystore AES/GCM 加密。
 * 敏感字段加密后落盘；Keystore 不可用时降级为明文（打日志警告，保证 App 不崩）。
 * 加密串以 "ENC:" 开头，便于识别与迁移。
 */
public class CryptoUtil {

    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String ALIAS = "phone_agent_secure";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String PREFIX = "ENC:";
    private static final int GCM_TAG_LEN = 128;

    public static boolean isEncrypted(String v) {
        return v != null && v.startsWith(PREFIX);
    }

    /** 加密；失败返回原文（降级） */
    public static String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) return plain == null ? "" : plain;
        try {
            SecretKey key = getOrCreateKey();
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] iv = cipher.getIV();
            byte[] ct = cipher.doFinal(
                    plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return PREFIX + Base64.encodeToString(out, Base64.NO_WRAP);
        } catch (Exception e) {
            android.util.Log.w("CryptoUtil",
                    "Keystore 加密失败，降级明文存储", e);
            return plain;
        }
    }

    /** 解密；非 ENC: 开头视为历史明文直接返回（迁移兼容） */
    public static String decrypt(String v) {
        if (v == null || v.isEmpty()) return v == null ? "" : v;
        if (!isEncrypted(v)) return v; // 历史明文
        try {
            byte[] data = Base64.decode(v.substring(PREFIX.length()),
                    Base64.NO_WRAP);
            // GCM IV 固定 12 字节
            byte[] iv = new byte[12];
            byte[] ct = new byte[data.length - 12];
            System.arraycopy(data, 0, iv, 0, 12);
            System.arraycopy(data, 12, ct, 0, ct.length);
            SecretKey key = getOrCreateKey();
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(GCM_TAG_LEN, iv));
            byte[] pt = cipher.doFinal(ct);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            android.util.Log.w("CryptoUtil", "解密失败", e);
            return "";
        }
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
        ks.load(null);
        if (ks.containsAlias(ALIAS)) {
            return (SecretKey) ks.getKey(ALIAS, null);
        }
        KeyGenerator kg = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
        kg.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return kg.generateKey();
    }
}
