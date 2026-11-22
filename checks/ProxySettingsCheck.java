package com.muse.workspace.mobile;

public final class ProxySettingsCheck {
    public static void main(String[] args) {
        ProxySettings https = ProxySettings.parse("https://example.com:8443");
        require("https".equals(https.scheme), "HTTPS 代理必须保留协议类型");
        require(https.rule().equals("https://example.com:8443"), "HTTPS 路由格式错误");

        ProxySettings socks = ProxySettings.parse("socks5://127.0.0.1:1080");
        require("socks5".equals(socks.scheme), "SOCKS5 代理必须保留协议类型");
        require(socks.rule().equals("socks5://127.0.0.1:1080"), "SOCKS5 路由格式错误");

        try {
            ProxySettings.parse("ftp://127.0.0.1:21");
            throw new AssertionError("不支持的代理协议不能被保存");
        } catch (IllegalArgumentException expected) { }
        System.out.println("Proxy protocol settings passed.");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
