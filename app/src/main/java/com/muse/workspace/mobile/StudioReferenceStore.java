package com.muse.workspace.mobile;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.util.AtomicFile;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

final class StudioReferenceStore {
    static final int MAX_BYTES = 8 * 1024 * 1024;
    static final int MAX_EDGE = 2048;
    /** Muse 网页每次最多接受 10 个附件。 */
    static final int MAX_REFERENCES = 10;
    private final File directory;

    StudioReferenceStore(Context context) {
        directory = directory(context);
    }

    static File directory(Context context) { return new File(context.getFilesDir(), "studio-references"); }

    /** 第 1 张沿用旧文件名（兼容旧版本），其余为 {会话}-{序号}.jpg。 */
    static File fileFor(File directory, String sessionId, int index) {
        if (sessionId == null || !sessionId.matches("[A-Za-z0-9_-]{1,80}") || index < 0 || index >= MAX_REFERENCES) return null;
        return new File(directory, index == 0 ? sessionId + ".jpg" : sessionId + "-" + index + ".jpg");
    }

    File file(String sessionId) { return file(sessionId, 0); }

    File file(String sessionId, int index) { return fileFor(directory, sessionId, index); }

    /** Switching the generation owner preserves the source draft and reference order. */
    int copySession(String sourceSession, String targetSession) throws IOException {
        int count = count(sourceSession), copied = 0;
        try {
            for (; copied < count; copied++) {
                File source = file(sourceSession, copied), target = file(targetSession, copied);
                if (source == null || target == null) throw new IOException("工作室会话无效");
                java.nio.file.Files.copy(source.toPath(), target.toPath());
            }
            return count;
        } catch (IOException failure) {
            for (int index = 0; index < copied; index++) {
                File target = file(targetSession, index);
                if (target != null) target.delete();
            }
            throw failure;
        }
    }

    /** 从第 1 张起连续存在的参考图数量。 */
    int count(String sessionId) {
        int count = 0;
        while (count < MAX_REFERENCES) {
            File source = file(sessionId, count);
            if (source == null || !source.isFile()) break;
            count++;
        }
        return count;
    }

    File save(ContentResolver resolver, String sessionId, Uri source) throws IOException {
        return save(resolver, sessionId, 0, source);
    }

    File save(ContentResolver resolver, String sessionId, int index, Uri source) throws IOException {
        if (source == null) throw new IOException("没有选择图片");
        if (file(sessionId, index) == null) throw new IOException(index >= MAX_REFERENCES ? "最多添加 " + MAX_REFERENCES + " 张参考图" : "工作室会话无效");
        String mime = resolver.getType(source);
        if (mime != null && !mime.toLowerCase(java.util.Locale.ROOT).startsWith("image/"))
            throw new IOException("请选择图片文件");
        Bitmap bitmap = decode(resolver, source);
        if (bitmap == null) throw new IOException("无法读取这张图片");
        try {
            Bitmap oriented = orient(resolver, source, bitmap);
            if (oriented != bitmap) bitmap.recycle();
            bitmap = oriented;
            bitmap = scale(oriented, MAX_EDGE);
            if (oriented != bitmap) oriented.recycle();
            byte[] encoded = compress(bitmap);
            File target = file(sessionId, index);
            if (target == null || (!directory.isDirectory() && !directory.mkdirs()))
                throw new IOException("无法保存参考图");
            AtomicFile outputFile = new AtomicFile(target);
            FileOutputStream output = null;
            try {
                output = outputFile.startWrite();
                output.write(encoded);
                outputFile.finishWrite(output);
                return target;
            } catch (IOException error) {
                outputFile.failWrite(output);
                throw error;
            }
        } catch (OutOfMemoryError error) {
            throw new IOException("图片过大，无法处理；请换一张图片", error);
        } finally {
            if (!bitmap.isRecycled()) bitmap.recycle();
        }
    }

    Bitmap thumbnail(String sessionId, int edge) { return thumbnail(sessionId, 0, edge); }

    Bitmap thumbnail(String sessionId, int index, int edge) {
        File source = file(sessionId, index);
        if (source == null || !source.isFile()) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(source.getPath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, edge);
        try { return BitmapFactory.decodeFile(source.getPath(), options); }
        catch (RuntimeException | OutOfMemoryError ignored) { return null; }
    }

    void remove(String sessionId) { removeAll(sessionId); }

    /** 删除第 index 张，后面的依次前移，保持连续编号。 */
    void removeAt(String sessionId, int index) {
        File target = file(sessionId, index);
        if (target == null) return;
        new AtomicFile(target).delete();
        for (int next = index + 1; next < MAX_REFERENCES; next++) {
            File source = file(sessionId, next);
            if (source == null || !source.isFile()) break;
            File destination = file(sessionId, next - 1);
            if (destination == null || !source.renameTo(destination)) break;
        }
    }

    void removeAll(String sessionId) {
        for (int index = 0; index < MAX_REFERENCES; index++) {
            File source = file(sessionId, index);
            if (source != null && source.exists()) new AtomicFile(source).delete();
        }
    }

    private static Bitmap decode(ContentResolver resolver, Uri source) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream input = resolver.openInputStream(source)) {
            if (input == null) throw new IOException("无法打开所选图片");
            BitmapFactory.decodeStream(input, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, MAX_EDGE);
        try (InputStream input = resolver.openInputStream(source)) {
            if (input == null) throw new IOException("无法打开所选图片");
            return BitmapFactory.decodeStream(input, null, options);
        }
    }

    private static int sampleSize(int width, int height, int maxEdge) {
        int sample = 1;
        while (Math.max(width / sample, height / sample) > maxEdge && sample <= (1 << 29)) sample *= 2;
        return sample;
    }

    private static Bitmap orient(ContentResolver resolver, Uri source, Bitmap image) {
        int orientation = ExifInterface.ORIENTATION_NORMAL;
        try (InputStream input = resolver.openInputStream(source)) {
            if (input != null) orientation = new ExifInterface(input).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (IOException | RuntimeException ignored) { }
        Matrix transform = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> transform.setScale(-1, 1);
            case ExifInterface.ORIENTATION_ROTATE_180 -> transform.setRotate(180);
            case ExifInterface.ORIENTATION_FLIP_VERTICAL -> transform.setScale(1, -1);
            case ExifInterface.ORIENTATION_TRANSPOSE -> { transform.setRotate(90); transform.postScale(-1, 1); }
            case ExifInterface.ORIENTATION_ROTATE_90 -> transform.setRotate(90);
            case ExifInterface.ORIENTATION_TRANSVERSE -> { transform.setRotate(270); transform.postScale(-1, 1); }
            case ExifInterface.ORIENTATION_ROTATE_270 -> transform.setRotate(270);
            default -> { return image; }
        }
        try { return Bitmap.createBitmap(image, 0, 0, image.getWidth(), image.getHeight(), transform, true); }
        catch (RuntimeException | OutOfMemoryError ignored) { return image; }
    }

    private static Bitmap scale(Bitmap image, int maxEdge) {
        int edge = Math.max(image.getWidth(), image.getHeight());
        if (edge <= maxEdge) return image;
        float ratio = (float) maxEdge / edge;
        return Bitmap.createScaledBitmap(image, Math.max(1, Math.round(image.getWidth() * ratio)),
            Math.max(1, Math.round(image.getHeight() * ratio)), true);
    }

    private static byte[] compress(Bitmap image) throws IOException {
        Bitmap source = image;
        if (image.hasAlpha()) {
            Bitmap flattened = Bitmap.createBitmap(image.getWidth(), image.getHeight(), Bitmap.Config.RGB_565);
            new Canvas(flattened).drawColor(Color.WHITE);
            new Canvas(flattened).drawBitmap(image, 0, 0, null);
            image.recycle();
            image = flattened;
        }
        try {
            int[] qualities = {88, 80, 72, 64, 56};
            for (int quality : qualities) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                if (!image.compress(Bitmap.CompressFormat.JPEG, quality, output)) throw new IOException("图片压缩失败");
                byte[] encoded = output.toByteArray();
                if (encoded.length <= MAX_BYTES) return encoded;
            }
            throw new IOException("图片压缩后仍超过 8 MB，请选择较小的图片");
        } finally {
            if (image != source && !image.isRecycled()) image.recycle();
        }
    }
}
