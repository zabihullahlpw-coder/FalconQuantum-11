package com.falcon.quantum;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.security.KeyStore;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Device-bound encrypted credential storage, Android API 24+.
 * A random AES-GCM nonce is generated for every write; the environment name
 * is authenticated as associated data. Plaintext legacy preferences are
 * removed only after the new encrypted record is committed to disk.
 *
 * Android Keystore keys are deliberately not exported or cloud backed up.
 * A restored/corrupt record is never silently treated as valid credentials.
 */
public final class SecureKeys {
    private static final String VAULT = "falcon_secure_credentials_v1";
    private static final String LEGACY = "falcon_keys";
    private static final String ALIAS = "falcon_quantum_aes_credentials_v1";
    private static final int VERSION = 1;
    private SecureKeys() {}

    private static void checkEnvironment(String env) {
        if (!"DEMO".equals(env) && !"LIVE".equals(env))
            throw new IllegalArgumentException("Credentials require DEMO or LIVE environment");
    }

    private static SecretKey deviceKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        KeyStore.Entry existing = ks.getEntry(ALIAS, null);
        if (existing instanceof KeyStore.SecretKeyEntry)
            return ((KeyStore.SecretKeyEntry) existing).getSecretKey();
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setKeySize(256)
                .build());
        return kg.generateKey();
    }

    private static String encrypt(String env, String key, String secret) throws Exception {
        ByteArrayOutputStream plain = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(plain);
        out.writeUTF(key);
        out.writeUTF(secret);
        out.close();
        byte[] raw = plain.toByteArray();
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, deviceKey());
            c.updateAAD(env.getBytes("UTF-8"));
            byte[] iv = c.getIV(), encrypted = c.doFinal(raw);
            ByteArrayOutputStream record = new ByteArrayOutputStream();
            record.write(VERSION);
            record.write(iv.length);
            record.write(iv);
            record.write(encrypted);
            return Base64.encodeToString(record.toByteArray(), Base64.NO_WRAP);
        } finally {
            Arrays.fill(raw, (byte) 0);
        }
    }

    private static String[] decrypt(String env, String blob) throws Exception {
        byte[] record = Base64.decode(blob, Base64.DEFAULT);
        if (record.length < 2 || (record[0] & 255) != VERSION)
            throw new IllegalStateException("Credential format version not supported");
        int ivLength = record[1] & 255;
        if (ivLength != 12 || record.length <= 2 + ivLength + 16)
            throw new IllegalStateException("Credential record is invalid");
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, deviceKey(),
                new GCMParameterSpec(128, record, 2, ivLength));
        c.updateAAD(env.getBytes("UTF-8"));
        byte[] raw = c.doFinal(record, 2 + ivLength, record.length - 2 - ivLength);
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw));
            String key = in.readUTF(), secret = in.readUTF();
            if (in.available() != 0) throw new IllegalStateException("Credential trailing data");
            return new String[] { key, secret };
        } finally {
            Arrays.fill(raw, (byte) 0);
        }
    }

    private static void purgeLegacy(Context ctx, String env) {
        boolean cleared = ctx.getSharedPreferences(LEGACY, Context.MODE_PRIVATE).edit()
                .remove(env + "_key").remove(env + "_secret").commit();
        if (!cleared) throw new IllegalStateException("Failed to remove old credential storage");
    }

    public static synchronized void save(Context ctx, String env, String key, String secret) {
        checkEnvironment(env);
        if (key == null || secret == null || key.length() == 0 || secret.length() == 0)
            throw new IllegalArgumentException("Enter both API key and secret, or use CLEAR KEYS");
        try {
            String encrypted = encrypt(env, key, secret);
            if (!ctx.getSharedPreferences(VAULT, Context.MODE_PRIVATE).edit()
                    .putString(env, encrypted).commit())
                throw new IllegalStateException("Encrypted credentials were not saved");
            purgeLegacy(ctx, env);
        } catch (Exception error) {
            throw new IllegalStateException("Android secure credential storage unavailable", error);
        }
    }

    public static synchronized String[] load(Context ctx, String env) {
        checkEnvironment(env);
        SharedPreferences p = ctx.getSharedPreferences(VAULT, Context.MODE_PRIVATE);
        String blob = p.getString(env, null);
        if (blob != null) {
            try {
                String[] result = decrypt(env, blob);
                purgeLegacy(ctx, env); // Also clean up interrupted previous migration.
                return result;
            } catch (Exception error) {
                throw new IllegalStateException("Saved keys cannot be decrypted; re-enter credentials", error);
            }
        }
        SharedPreferences legacy = ctx.getSharedPreferences(LEGACY, Context.MODE_PRIVATE);
        String key = legacy.getString(env + "_key", "");
        String secret = legacy.getString(env + "_secret", "");
        if (key.length() == 0 && secret.length() == 0) return new String[] { "", "" };
        if (key.length() == 0 || secret.length() == 0) {
            purgeLegacy(ctx, env); // An incomplete pair cannot be used and must not remain in plaintext.
            return new String[] { "", "" };
        }
        save(ctx, env, key, secret);
        return new String[] { key, secret };
    }

    /** One-time disposal of retired desktop-link data; trading keys remain intact. */
    public static synchronized void purgeRetiredDesktopLink(Context ctx) {
        SharedPreferences state = ctx.getSharedPreferences("falcon_prefs", Context.MODE_PRIVATE);
        if (state.getBoolean("desktop_link_retired_11002", false)) return;
        ctx.getSharedPreferences(VAULT, Context.MODE_PRIVATE).edit()
                .remove("pc_link").apply();
        state.edit().remove("server_url")
                .putBoolean("desktop_link_retired_11002", true).apply();
    }

    public static synchronized void clear(Context ctx, String env) {
        checkEnvironment(env);
        if (!ctx.getSharedPreferences(VAULT, Context.MODE_PRIVATE).edit().remove(env).commit())
            throw new IllegalStateException("Could not clear saved credentials");
        purgeLegacy(ctx, env);
    }
}
