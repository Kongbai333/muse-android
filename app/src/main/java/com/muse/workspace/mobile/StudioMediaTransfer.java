package com.muse.workspace.mobile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

final class StudioMediaTransfer implements AutoCloseable {
    private final File target, partial;
    private final int total;
    private final FileOutputStream output;
    private int offset;
    private boolean finished;

    StudioMediaTransfer(File target, int total) throws IOException {
        if (total <= 0 || total > 128 * 1024 * 1024) throw new IOException("媒体大小不受支持");
        this.target = target;
        this.total = total;
        partial = new File(target.getPath() + ".part");
        output = new FileOutputStream(partial);
    }

    int offset() { return offset; }
    int total() { return total; }
    File partial() { return partial; }

    void write(int from, byte[] bytes, boolean last) throws IOException {
        if (finished || from != offset || bytes.length == 0 || bytes.length > 65536
            || bytes.length > total - offset || last != (offset + bytes.length == total)) {
            throw new IOException("生成文件传输不完整");
        }
        output.write(bytes);
        offset += bytes.length;
        if (last) { output.close(); finished = true; }
    }

    void commit() throws IOException {
        if (!finished || !partial.renameTo(target)) throw new IOException("无法保存生成文件");
    }

    @Override public void close() {
        try { output.close(); } catch (IOException ignored) { }
        partial.delete();
    }
}
