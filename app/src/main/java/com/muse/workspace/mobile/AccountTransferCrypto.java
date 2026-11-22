package com.muse.workspace.mobile;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/** Portable v1 encryption only. No Android storage, logging, network or custom crypto. */
final class AccountTransferCrypto {
    static final int MAX_PLAINTEXT_BYTES = 4 * 1024 * 1024;
    static final int MAX_FILE_BYTES = 6 * 1024 * 1024;
    private static final int ITERATIONS = 600000;
    private static final byte[] AAD = "muse-account-transfer:v1:PBKDF2-HMAC-SHA256:600000:AES-256-GCM".getBytes(StandardCharsets.US_ASCII);
    private static final String PREFIX = "{\"format\":\"muse-account-transfer\",\"version\":1,\"kdf\":\"PBKDF2-HMAC-SHA256\",\"iterations\":600000,\"salt\":\"";
    private static final String MIDDLE = "\",\"cipher\":\"AES-256-GCM\",\"nonce\":\"";
    private static final String BODY = "\",\"ciphertext\":\"";
    private static final String END = "\"}";

    static final class TransferException extends Exception {
        final String code;
        TransferException(String code) { super(code); this.code = code; }
    }

    static byte[] encrypt(byte[] plaintext, char[] passphrase) throws TransferException {
        byte[] salt = new byte[16], nonce = new byte[12];
        SecureRandom random = new SecureRandom();
        random.nextBytes(salt); random.nextBytes(nonce);
        return encryptFixture(plaintext, passphrase, salt, nonce);
    }

    /** Package-private deterministic primitive for public fixture checks only; UI uses encrypt. */
    static byte[] encryptFixture(byte[] plaintext, char[] passphrase, byte[] salt, byte[] nonce) throws TransferException {
        if (plaintext == null || plaintext.length == 0 || plaintext.length > MAX_PLAINTEXT_BYTES) throw new TransferException("payload");
        if (salt == null || salt.length != 16 || nonce == null || nonce.length != 12) throw new TransferException("format");
        byte[] key = deriveKey(passphrase, salt);
        byte[] encrypted = null;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(AAD);
            encrypted = cipher.doFinal(plaintext);
            Base64.Encoder base64 = Base64.getEncoder();
            return (PREFIX + base64.encodeToString(salt) + MIDDLE + base64.encodeToString(nonce)
                + BODY + base64.encodeToString(encrypted) + END).getBytes(StandardCharsets.US_ASCII);
        } catch (GeneralSecurityException error) {
            throw new TransferException("crypto");
        } finally {
            Arrays.fill(key, (byte) 0);
            if (encrypted != null) Arrays.fill(encrypted, (byte) 0);
        }
    }

    static byte[] decrypt(byte[] envelope, char[] passphrase) throws TransferException {
        if (envelope == null || envelope.length == 0 || envelope.length > MAX_FILE_BYTES) throw new TransferException("format");
        // A fixed grammar, not a permissive JSON parser: all parameters are rejected before KDF.
        String text = new String(envelope, StandardCharsets.US_ASCII);
        int saltStart = PREFIX.length(), saltEnd = saltStart + 24;
        int nonceStart = saltEnd + MIDDLE.length(), nonceEnd = nonceStart + 16;
        int bodyStart = nonceEnd + BODY.length();
        if (text.length() <= bodyStart + END.length() || !text.startsWith(PREFIX)
                || !text.startsWith(MIDDLE, saltEnd) || !text.startsWith(BODY, nonceEnd)
                || !text.endsWith(END)) throw new TransferException("format");
        byte[] salt = decode(text.substring(saltStart, saltEnd));
        byte[] nonce = decode(text.substring(nonceStart, nonceEnd));
        byte[] ciphertext = decode(text.substring(bodyStart, text.length() - END.length()));
        if (salt.length != 16 || nonce.length != 12 || ciphertext.length < 17
                || ciphertext.length > MAX_PLAINTEXT_BYTES + 16) throw new TransferException("format");
        byte[] key = deriveKey(passphrase, salt);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(AAD);
            return cipher.doFinal(ciphertext);
        } catch (AEADBadTagException error) {
            throw new TransferException("authentication");
        } catch (GeneralSecurityException error) {
            // Authentication and malformed ciphertext are deliberately not distinguished to users.
            throw new TransferException("authentication");
        } finally {
            Arrays.fill(key, (byte) 0); Arrays.fill(ciphertext, (byte) 0);
        }
    }

    static byte[] deriveKey(char[] passphrase, byte[] salt) throws TransferException {
        validatePassphrase(passphrase);
        if (salt == null || salt.length != 16) throw new TransferException("format");
        PBEKeySpec spec = new PBEKeySpec(passphrase, salt, ITERATIONS, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException error) {
            throw new TransferException("crypto");
        } finally { spec.clearPassword(); }
    }

    static void validatePassphrase(char[] passphrase) throws TransferException {
        if (passphrase == null || passphrase.length > 1024) throw new TransferException("password");
        ByteBuffer encoded = null;
        try {
            encoded = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(passphrase));
            if (encoded.remaining() < 8 || encoded.remaining() > 1024) throw new TransferException("password");
        } catch (CharacterCodingException error) {
            throw new TransferException("password");
        } finally {
            if (encoded != null && encoded.hasArray()) Arrays.fill(encoded.array(), (byte) 0);
        }
    }

    private static byte[] decode(String text) throws TransferException {
        try {
            byte[] decoded = Base64.getDecoder().decode(text);
            if (!Base64.getEncoder().encodeToString(decoded).equals(text)) throw new TransferException("format");
            return decoded;
        } catch (IllegalArgumentException error) { throw new TransferException("format"); }
    }
}
