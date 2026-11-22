package com.muse.workspace.mobile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.Properties;

/** Host JCA checks only; all inputs are public synthetic fixtures, no network or Android data. */
public final class AccountTransferCryptoCheck {
    private static int checks;
    public static void main(String[] args) throws Exception {
        Path root = Path.of("checks/fixtures/account-transfer-v1");
        Path output = args.length > 0 ? Path.of(args[0]) : Path.of("app/build/transferChecks");
        Files.createDirectories(output);
        byte[] plain = Files.readAllBytes(root.resolve("payload.json"));
        for (String name : new String[]{"ascii", "unicode"}) {
            Properties vector = new Properties();
            try (var input = Files.newBufferedReader(root.resolve(name + ".properties"), StandardCharsets.UTF_8)) { vector.load(input); }
            char[] phrase = new String(Base64.getDecoder().decode(vector.getProperty("passphrase_utf8_base64")), StandardCharsets.UTF_8).toCharArray();
            byte[] expected = Files.readAllBytes(root.resolve(name + ".muse-account"));
            byte[] salt = hex(vector.getProperty("salt_hex")), nonce = hex(vector.getProperty("nonce_hex"));
            require(hex(AccountTransferCrypto.deriveKey(phrase, salt)).equals(vector.getProperty("derived_key_hex")), name + " UTF-8 PBKDF2 bytes");
            require(Arrays.equals(plain, AccountTransferCrypto.decrypt(expected, phrase)), name + " independent vector decrypt");
            require(Arrays.equals(expected, AccountTransferCrypto.encryptFixture(plain, phrase, salt, nonce)), name + " independent vector encrypt");
            require(hex(MessageDigest.getInstance("SHA-256").digest(expected)).equals(vector.getProperty("envelope_sha256")), name + " envelope hash");
            byte[] random = AccountTransferCrypto.encrypt(plain, phrase);
            require(!Arrays.equals(expected, random), name + " production salt/nonce random");
            require(Arrays.equals(plain, AccountTransferCrypto.decrypt(random, phrase)), name + " random roundtrip");
            Files.write(output.resolve("java-" + name + ".muse-account"), random);
            reject(() -> AccountTransferCrypto.decrypt(expected, "SYNTHETIC-wrong-pass".toCharArray()), "wrong passphrase");
            byte[] tampered = expected.clone(); tampered[tampered.length - 8] ^= 1;
            reject(() -> AccountTransferCrypto.decrypt(tampered, phrase), "tampered ciphertext/tag");
            for (String invalid : new String[]{
                    new String(expected, StandardCharsets.US_ASCII).replace("600000", "2147483647"),
                    new String(expected, StandardCharsets.US_ASCII).replace("\"version\":1", "\"version\":2"),
                    new String(expected, StandardCharsets.US_ASCII).replace("AES-256-GCM", "AES-128-CBC"),
                    new String(expected, StandardCharsets.US_ASCII).replace("\"version\":1", "\"version\":1,\"version\":1"),
                    " " + new String(expected, StandardCharsets.US_ASCII),
                    new String(expected, StandardCharsets.US_ASCII) + "\n"}) {
                reject(() -> AccountTransferCrypto.decrypt(invalid.getBytes(StandardCharsets.UTF_8), phrase), "noncanonical/header/cost");
            }
            Arrays.fill(phrase, '\0');
        }
        reject(() -> AccountTransferCrypto.encrypt(new byte[0], "SYNTHETIC-valid-pass".toCharArray()), "empty payload");
        reject(() -> AccountTransferCrypto.encrypt(new byte[AccountTransferCrypto.MAX_PLAINTEXT_BYTES + 1], "SYNTHETIC-valid-pass".toCharArray()), "oversized plaintext");
        reject(() -> AccountTransferCrypto.decrypt(new byte[AccountTransferCrypto.MAX_FILE_BYTES + 1], "SYNTHETIC-valid-pass".toCharArray()), "oversized envelope");
        reject(() -> AccountTransferCrypto.encrypt(plain, "short".toCharArray()), "short passphrase");
        reject(() -> AccountTransferCrypto.encrypt(plain, new char[]{'S','Y','N','T','H','E','T','I','C','\ud800'}), "unpaired surrogate");
        require(new String(plain, StandardCharsets.UTF_8).contains("SYNTHETIC_SESSION==%2F%3D%25+ONLY"), "raw Cookie marker fixture");
        if (args.length > 1) {
            Properties vector = new Properties();
            try (var in = Files.newBufferedReader(root.resolve("unicode.properties"), StandardCharsets.UTF_8)) { vector.load(in); }
            char[] phrase = new String(Base64.getDecoder().decode(vector.getProperty("passphrase_utf8_base64")), StandardCharsets.UTF_8).toCharArray();
            byte[] foreign = AccountTransferCrypto.decrypt(Files.readAllBytes(Path.of(args[1])), phrase);
            require(Arrays.equals(plain, foreign), "Rust-generated Unicode envelope consumed by real Java JCA");
            Arrays.fill(phrase, '\0'); Arrays.fill(foreign, (byte)0);
        }
        System.out.println("Account transfer JCA: " + checks + " checks passed; synthetic only; fresh Java envelopes in " + output);
    }
    private interface Throwing { void run() throws Exception; }
    private static void reject(Throwing call, String reason) throws Exception {
        try { call.run(); } catch (AccountTransferCrypto.TransferException expected) { checks++; return; }
        throw new AssertionError("Accepted " + reason);
    }
    private static void require(boolean value, String reason) { if (!value) throw new AssertionError(reason); checks++; }
    private static byte[] hex(String value) { byte[] out = new byte[value.length()/2]; for (int i=0;i<out.length;i++) out[i]=(byte)Integer.parseInt(value.substring(i*2,i*2+2),16); return out; }
    private static String hex(byte[] value) { StringBuilder out=new StringBuilder(); for(byte item:value)out.append(String.format("%02x",item&255)); return out.toString(); }
}
