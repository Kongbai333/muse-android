package com.muse.workspace.mobile;

import android.os.Handler;
import android.os.Looper;
import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;
import androidx.webkit.WebViewFeature;
import java.util.function.Consumer;

final class WebProxyRoute {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Request pending;
    private static boolean applying;

    private static final class Request {
        final String rule;
        final Consumer<String> done;
        Runnable timeout;
        boolean reported;

        Request(String rule, Consumer<String> done) { this.rule = rule; this.done = done; }
    }

    static boolean supported() {
        try {
            return WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE);
        } catch (RuntimeException error) {
            return false;
        }
    }

    // WebView 在同一进程共用代理，等待当前设置生效后才打开目标账号。
    static void apply(String rule, Consumer<String> done) {
        if (pending != null) MAIN.removeCallbacks(pending.timeout);
        Request request = new Request(rule, done);
        request.timeout = () -> report(request, "应用代理设置超时，请重新打开 Muse 后重试。");
        pending = request;
        MAIN.postDelayed(request.timeout, 8000);
        advance();
    }

    private static void advance() {
        if (applying || pending == null) return;
        Request request = pending;
        pending = null;
        if (request.reported) return;
        if (!supported()) {
            report(request, request.rule == null ? null : "当前 WebView 不支持代理设置，请更新 Android System WebView 或 Chrome。");
            return;
        }
        applying = true;
        try {
            ProxyController controller = ProxyController.getInstance();
            Runnable applied = () -> complete(request, null);
            if (request.rule == null) {
                controller.clearProxyOverride(task -> MAIN.post(task), applied);
            } else {
                ProxyConfig config = ProxyRouteConfig.build(request.rule);
                controller.setProxyOverride(config, task -> MAIN.post(task), applied);
            }
        } catch (RuntimeException error) {
            complete(request, "无法应用代理设置，请检查地址或更新 WebView。");
        }
    }

    private static void complete(Request request, String error) {
        applying = false;
        report(request, error);
        advance();
    }

    private static void report(Request request, String error) {
        MAIN.removeCallbacks(request.timeout);
        if (request.reported) return;
        request.reported = true;
        request.done.accept(error);
    }
}
