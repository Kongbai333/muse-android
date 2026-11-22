package com.muse.workspace.mobile;

import androidx.webkit.ProxyConfig;

public final class ProxyRouteConfigCheck {
    public static void main(String[] args) {
        String[] routes = {"http://proxy.example:8080", "https://proxy.example:443",
            "socks5://proxy.example:1080", "http://127.0.0.1:49152"};
        for (String route : routes) {
            ProxyConfig config = ProxyRouteConfig.build(route);
            require(!config.getProxyRules().isEmpty(), "Missing proxy route");
            boolean http = false, https = false;
            for (ProxyConfig.ProxyRule rule : config.getProxyRules()) {
                require(route.equals(rule.getUrl()), "WebView received an invalid proxy address: " + rule.getUrl());
                String filter = rule.getSchemeFilter();
                require("*".equals(filter) || "http".equals(filter) || "https".equals(filter),
                    "WebView received an invalid scheme filter: " + filter);
                http |= "*".equals(filter) || "http".equals(filter);
                https |= "*".equals(filter) || "https".equals(filter);
            }
            require(http && https, "Proxy must cover both HTTP and HTTPS pages");
        }
        System.out.println("AndroidX WebView proxy configuration passed for all proxy protocols and loopback routing.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
