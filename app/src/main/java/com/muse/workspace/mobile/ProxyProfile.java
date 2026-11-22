package com.muse.workspace.mobile;

final class ProxyProfile {
    final String id;
    String name;
    String scheme;
    String host;
    int port;
    String credential;

    ProxyProfile(String id, String name, ProxySettings settings, String credential) {
        this.id = id;
        this.name = name;
        scheme = settings.scheme;
        host = settings.host;
        port = settings.port;
        this.credential = credential;
    }

    ProxySettings settings() throws java.security.GeneralSecurityException, java.io.IOException {
        String credentials = ProxySecrets.open(id, credential);
        int separator = credentials.indexOf(':');
        String username = separator < 0 ? "" : credentials.substring(0, separator);
        String secret = separator < 0 ? "" : credentials.substring(separator + 1);
        return ProxySettings.create(scheme, host, Integer.toString(port), username, secret);
    }

    String endpoint() {
        String address = host.indexOf(':') >= 0 ? "[" + host + "]" : host;
        return scheme.toUpperCase(java.util.Locale.ROOT) + " · " + address + ":" + port;
    }
}
