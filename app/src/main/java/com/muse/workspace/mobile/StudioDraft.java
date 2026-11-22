package com.muse.workspace.mobile;

import java.net.URI;
import java.net.URISyntaxException;

final class StudioDraft {
    static final String HOME = "https://muse.ai/";
    static final String NEW_THREAD = HOME + "thread/new";

    static String format(boolean video, String prompt, String ratio, int seconds) {
        String text = prompt.trim();
        if (text.isEmpty()) return "";
        String aspect = "Auto".equals(ratio) ? "不指定画幅比例" : "画幅比例 " + ratio;
        if (video) {
            return "请直接生成一个全新视频，" + aspect + "，时长约 " + seconds + " 秒。内容：" + text;
        }
        return "请直接生成一张全新图片，" + aspect + "。内容：" + text;
    }

    static boolean webUrl(String url) {
        return parseHttps(url) != null;
    }

    static boolean trustedUrl(String url) {
        URI uri = parseHttps(url);
        if (uri == null || (uri.getPort() != -1 && uri.getPort() != 443)) return false;
        return "muse.ai".equalsIgnoreCase(uri.getHost()) || "www.muse.ai".equalsIgnoreCase(uri.getHost());
    }

    static boolean newThreadUrl(String url) {
        if (!trustedUrl(url)) return false;
        String path = URI.create(url).getRawPath();
        return "/thread/new".equals(path) || "/thread/new/".equals(path);
    }

    static boolean loginPage(String url) {
        if (!trustedUrl(url)) return false;
        String path = URI.create(url).getPath();
        return path != null && path.toLowerCase(java.util.Locale.ROOT).matches(".*/(?:login|signin|sign-in)(?:/.*)?");
    }

    private static URI parseHttps(String url) {
        if (url == null) return null;
        try {
            URI uri = new URI(url);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && uri.getRawUserInfo() == null
                ? uri : null;
        } catch (URISyntaxException ignored) {
            return null;
        }
    }
}
