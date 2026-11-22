package com.muse.workspace.mobile;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

final class ReferenceUpload {
    static final int CHUNK = 24 * 1024;
    final File file;
    final String name;
    final String mime;
    private final long length;
    private int offset;
    private byte[] pending;

    ReferenceUpload(File file, String name, String mime) throws IOException {
        if (file == null || !file.isFile() || !file.canRead()) throw new IOException("参考图文件不可读");
        long size = file.length();
        if (size <= 0 || size > 8 * 1024 * 1024L) throw new IOException("参考图大小不受支持");
        this.file = file;
        this.name = name == null || name.isEmpty() ? "reference.jpg" : name;
        this.mime = mime == null || mime.isEmpty() ? "image/jpeg" : mime;
        length = size;
    }

    int offset() { return offset; }
    int length() { return (int) length; }
    boolean complete() { return offset == length; }
    boolean eof() { return offset + (pending == null ? 0 : pending.length) == length; }

    byte[] chunk() throws IOException {
        if (pending != null) return pending;
        int size = (int) Math.min(CHUNK, length - offset);
        pending = new byte[size];
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            input.seek(offset);
            input.readFully(pending);
        }
        return pending;
    }

    void ack(int acknowledgedOffset) throws IOException {
        if (pending == null || pending.length == 0) throw new IOException("参考图分块状态无效");
        if (acknowledgedOffset != offset + pending.length) throw new IOException("参考图分块确认位置无效");
        offset += pending.length;
        pending = null;
    }
}
