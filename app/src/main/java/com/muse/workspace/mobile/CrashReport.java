package com.muse.workspace.mobile;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 闪退记录：未捕获异常写入应用私有目录，由 {@link CrashActivity} 显示。 */
final class CrashReport implements Thread.UncaughtExceptionHandler {
    private static final String FILE = "last-crash.txt";
    private static final int LIMIT = 60_000;
    private static boolean installed;
    private final Context context;
    private final Thread.UncaughtExceptionHandler previous;

    private CrashReport(Context context, Thread.UncaughtExceptionHandler previous) {
        this.context = context;
        this.previous = previous;
    }

    static synchronized void install(Context context) {
        if (installed) return;
        installed = true;
        Context app = context.getApplicationContext() == null ? context : context.getApplicationContext();
        Thread.setDefaultUncaughtExceptionHandler(new CrashReport(app, Thread.getDefaultUncaughtExceptionHandler()));
    }

    private static File file(Context context) {
        Context app = context.getApplicationContext() == null ? context : context.getApplicationContext();
        return new File(app.getFilesDir(), FILE);
    }

    static boolean pending(Context context) {
        try { return file(context).isFile(); } catch (Throwable ignored) { return false; }
    }

    /** 记录一次异常（启动时捕获的错误也走这里）。 */
    static void save(Context context, Thread thread, Throwable error) {
        try (FileOutputStream output = new FileOutputStream(file(context))) {
            String text = describe(context, thread, error);
            if (text.length() > LIMIT) text = text.substring(0, LIMIT);
            output.write(text.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        } catch (Throwable ignored) { }
    }

    /** 读取并删除上次的记录；没有时返回 null。 */
    static String take(Context context) {
        File file = file(context);
        if (!file.isFile()) return null;
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] data = new byte[(int) Math.min(file.length(), LIMIT * 4L)];
            int read = 0;
            while (read < data.length) {
                int size = input.read(data, read, data.length - read);
                if (size < 0) break;
                read += size;
            }
            return new String(data, 0, read, StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            return null;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    @Override public void uncaughtException(Thread thread, Throwable error) {
        save(context, thread, error);
        if (previous != null) previous.uncaughtException(thread, error);
        else {
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(10);
        }
    }

    private static String version(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return info.versionName + " (" + (Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode) + ")";
        } catch (Throwable ignored) {
            return "未知";
        }
    }

    private static String describe(Context context, Thread thread, Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        StringBuilder text = new StringBuilder();
        text.append("时间：").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date())).append('\n');
        text.append("版本：").append(version(context)).append('\n');
        text.append("设备：").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append(" · Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        text.append("线程：").append(thread == null ? "?" : thread.getName()).append("\n\n");
        text.append("根因：").append(root).append('\n');
        StackTraceElement[] frames = root.getStackTrace();
        for (int index = 0; index < Math.min(frames.length, 18); index++) text.append("  at ").append(frames[index]).append('\n');
        text.append("\n完整堆栈：\n");
        StringWriter full = new StringWriter();
        error.printStackTrace(new PrintWriter(full));
        text.append(full);
        return text.toString();
    }
}
