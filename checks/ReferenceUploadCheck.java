package com.muse.workspace.mobile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

public final class ReferenceUploadCheck {
    public static void main(String[] args) throws Exception {
        Path file = Files.createTempFile(Path.of(args[0]), "reference-", ".jpg");
        byte[] expected = new byte[24 * 1024 + 7];
        for (int index = 0; index < expected.length; index++) expected[index] = (byte) (index % 251);
        Files.write(file, expected);
        ReferenceUpload upload = new ReferenceUpload(file.toFile(), "reference.jpg", "image/jpeg");
        byte[] first = upload.chunk();
        require(first.length == 24 * 1024 && !upload.eof(), "First reference chunk must be bounded and non-final");
        upload.ack(24 * 1024);
        byte[] second = upload.chunk();
        require(second.length == 7 && upload.eof(), "Final reference chunk must contain the remaining bytes");
        upload.ack(expected.length);
        require(upload.complete() && upload.offset() == expected.length, "Reference upload must finish at file length");
        byte[] joined = new byte[first.length + second.length];
        System.arraycopy(first, 0, joined, 0, first.length);
        System.arraycopy(second, 0, joined, first.length, second.length);
        require(Arrays.equals(expected, joined), "Reference chunks must preserve file bytes");
        try {
            upload.ack(expected.length);
            throw new AssertionError("An extra chunk acknowledgement must be rejected");
        } catch (java.io.IOException expectedFailure) { }
        Files.deleteIfExists(file);
        System.out.println("Reference upload chunk contract passed.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
