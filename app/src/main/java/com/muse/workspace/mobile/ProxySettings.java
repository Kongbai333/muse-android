package com.muse.workspace.mobile;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

final class ProxySettings {
    final String scheme;
    final String host;
    final int port;
    final String username;
    final String secret;

    private ProxySettings(String scheme, String host, int port, String username, String secret) {
        this.scheme = scheme;
        this.host = host;
        this.port = port;
        this.username = username;
        this.secret = secret;
    }

    static ProxySettings create(String host, String rawPort, String username, String secret) {
        return create("http", host, rawPort, username, secret);
    }

    static ProxySettings create(String scheme, String host, String rawPort, String username, String secret) {
        String protocol = normalizeScheme(scheme);
        int port;
        try {
            port = Integer.parseInt(rawPort.trim());
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("端口必须为 1–65535。");
        }
        if (port < 1 || port > 65535) throw new IllegalArgumentException("端口必须为 1–65535。");
        String server = unbracket(host.trim());
        URI parsed = endpoint(authority(server, port));
        if (parsed == null || parsed.getRawUserInfo() != null || parsed.getPort() != port
            || !server.equalsIgnoreCase(unbracket(parsed.getHost()))) {
            throw new IllegalArgumentException("请填写有效的服务器地址，或粘贴完整代理地址。");
        }
        if (username.isEmpty() && !secret.isEmpty()) throw new IllegalArgumentException("填写密码时也需要填写用户名。");
        if (username.indexOf(':') >= 0 || username.length() > 1024 || secret.length() > 4096
            || username.chars().anyMatch(Character::isISOControl) || secret.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("代理用户名或密码格式无效。");
        }
        return new ProxySettings(protocol, server, port, username, secret);
    }

    static ProxySettings parse(String input) {
        String url = input.trim();
        if (!url.contains("://")) url = "http://" + url;
        URI parsed;
        try {
            parsed = URI.create(url);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("代理地址格式无效。");
        }
        String scheme = normalizeScheme(parsed.getScheme());
        if (parsed.getHost() == null || parsed.getRawQuery() != null || parsed.getRawFragment() != null
            || !(parsed.getRawPath().isEmpty() || "/".equals(parsed.getRawPath()))) {
            throw new IllegalArgumentException("代理地址格式无效。");
        }
        String raw = parsed.getRawUserInfo();
        String[] userInfo = raw == null ? new String[0] : raw.split(":", 2);
        int defaultPort = "socks5".equals(scheme) ? 1080 : "https".equals(scheme) ? 443 : 80;
        return create(scheme, parsed.getHost(), Integer.toString(parsed.getPort() == -1 ? defaultPort : parsed.getPort()),
            userInfo.length > 0 ? decode(userInfo[0]) : "", userInfo.length == 2 ? decode(userInfo[1]) : "");
    }

    String rule() { return scheme + "://" + authority(host, port); }
    boolean hasAuthentication() { return !username.isEmpty(); }

    String label() {
        return switch (scheme) {
            case "https" -> "HTTPS";
            case "socks5" -> "SOCKS5";
            default -> "HTTP";
        };
    }

    static String basic(String username, String secret) {
        return "Basic " + Base64.getEncoder().encodeToString((username + ":" + secret).getBytes(StandardCharsets.UTF_8));
    }

    static boolean isAuthority(String target) {
        URI parsed = endpoint(target);
        return parsed != null && parsed.getRawUserInfo() == null && parsed.getPort() > 0 && parsed.getPort() <= 65535;
    }

    private static URI endpoint(String authority) {
        if (authority.length() > 280 || authority.indexOf('%') >= 0) return null;
        try {
            URI parsed = URI.create("http://" + authority);
            return parsed.getHost() != null && parsed.getRawPath().isEmpty() && parsed.getRawQuery() == null
                && parsed.getRawFragment() == null ? parsed : null;
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    private static String authority(String host, int port) {
        return (host.contains(":") ? "[" + host + "]" : host) + ":" + port;
    }

    private static String normalizeScheme(String scheme) {
        String protocol = scheme == null ? "" : scheme.trim().toLowerCase(java.util.Locale.ROOT);
        if (!"http".equals(protocol) && !"https".equals(protocol) && !"socks5".equals(protocol)) {
            throw new IllegalArgumentException("代理协议仅支持 HTTP、HTTPS 或 SOCKS5。");
        }
        return protocol;
    }

    private static String unbracket(String host) {
        return host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
    }

    private static String decode(String raw) {
        try {
            return URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8");
        } catch (UnsupportedEncodingException | IllegalArgumentException error) {
            throw new IllegalArgumentException("代理认证编码无效。");
        }
    }
}
