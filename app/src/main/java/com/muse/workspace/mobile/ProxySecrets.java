package com.muse.workspace.mobile;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class ProxySecrets {
    private static final String ALIAS = "muse.proxy.aes.v1";
    private static final String PROVIDER = "AndroidKeyStore";

    static String seal(String accountId, String text) throws GeneralSecurityException, IOException {
        if (text.isEmpty()) return "";
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key(true));
        cipher.updateAAD(accountId.getBytes(StandardCharsets.UTF_8));
        Base64.Encoder encode = Base64.getEncoder();
        return encode.encodeToString(cipher.getIV()) + "." + encode.encodeToString(cipher.doFinal(text.getBytes(StandardCharsets.UTF_8)));
    }

    static String open(String accountId, String stored) throws GeneralSecurityException, IOException {
        if (stored.isEmpty()) return "";
        String[] fields = stored.split("\\.", 2);
        if (fields.length != 2) throw new GeneralSecurityException("无效的代理凭据");
        Base64.Decoder decode = Base64.getDecoder();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(false), new GCMParameterSpec(128, decode.decode(fields[0])));
        cipher.updateAAD(accountId.getBytes(StandardCharsets.UTF_8));
        return new String(cipher.doFinal(decode.decode(fields[1])), StandardCharsets.UTF_8);
    }

    private static synchronized SecretKey key(boolean create) throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance(PROVIDER);
        store.load(null);
        if (store.getKey(ALIAS, null) instanceof SecretKey secret) return secret;
        if (!create) throw new GeneralSecurityException("找不到代理加密密钥");
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER);
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build());
        return generator.generateKey();
    }
}
