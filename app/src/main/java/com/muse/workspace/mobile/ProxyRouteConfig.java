package com.muse.workspace.mobile;

import androidx.webkit.ProxyConfig;

final class ProxyRouteConfig {
    static ProxyConfig build(String rule) {
        return new ProxyConfig.Builder()
            .addProxyRule(rule)
            .removeImplicitRules().build();
    }
}
