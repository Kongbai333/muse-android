package com.muse.workspace.mobile;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.view.View;
import android.webkit.PermissionRequest;
import android.webkit.WebView;

final class WebMicrophone {
    private static final int REQUEST_CODE = 612;
    private static final String AUDIO = PermissionRequest.RESOURCE_AUDIO_CAPTURE;
    private static final String RECORD = Manifest.permission.RECORD_AUDIO;
    private final Activity activity;
    private Pending pending;
    private boolean androidPrompt;

    private record Pending(WebView page, PermissionRequest request) { }

    WebMicrophone(Activity activity) { this.activity = activity; }

    void request(WebView page, PermissionRequest request) {
        String[] allowed = resourcesFor(page, request);
        switch (MicrophonePermissionFlow.decide(allowed.length > 0, hasPermission(), androidPrompt)) {
            case DENY -> request.deny();
            case GRANT -> request.grant(allowed);
            case REQUEST_ANDROID -> {
                pending = new Pending(page, request);
                androidPrompt = true;
                try {
                    activity.requestPermissions(new String[]{RECORD}, REQUEST_CODE);
                } catch (RuntimeException error) {
                    androidPrompt = false;
                    cancelPending();
                    showPermissionHint();
                }
            }
        }
    }

    void onAndroidResult(int code, int[] results) {
        if (code != REQUEST_CODE || !androidPrompt) return;
        androidPrompt = false;
        Pending waiting = pending;
        pending = null;
        if (waiting == null) return;

        PermissionRequest request = waiting.request();
        String[] allowed = resourcesFor(waiting.page(), request);
        if (allowed.length == 0) request.deny();
        else if (results.length == 1 && results[0] == PackageManager.PERMISSION_GRANTED && hasPermission()) {
            request.grant(allowed);
        } else {
            request.deny();
            showPermissionHint();
        }
    }

    void webCanceled(PermissionRequest request) {
        if (pending != null && pending.request() == request) pending = null;
    }

    void cancelPending() {
        Pending waiting = pending;
        pending = null;
        if (waiting != null) waiting.request().deny();
        // 系统弹窗可能仍在等待结果；回调返回前不把它关联到新请求。
    }

    private boolean hasPermission() {
        return activity.checkSelfPermission(RECORD) == PackageManager.PERMISSION_GRANTED;
    }

    private String[] resourcesFor(WebView page, PermissionRequest request) {
        if (activity.isFinishing() || activity.isDestroyed() || !page.isAttachedToWindow()
                || !page.isShown() || page.getWindowVisibility() != View.VISIBLE) return new String[0];
        Uri origin = request.getOrigin();
        return allowedResources(page.getUrl(), origin == null ? null : origin.toString(), request.getResources());
    }

    static String[] allowedResources(String page, String origin, String[] requested) {
        if (StudioDraft.trustedUrl(page) && StudioDraft.trustedUrl(origin) && requested != null) {
            for (String resource : requested) if (AUDIO.equals(resource)) return new String[]{AUDIO};
        }
        return new String[0];
    }

    private void showPermissionHint() {
        MobileUi.notice(activity, "未允许麦克风，可在系统设置中打开 Muse 的麦克风权限。");
    }
}
