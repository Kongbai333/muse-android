package com.muse.workspace.mobile;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.function.BiConsumer;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * 本机代理分流器：WebView 的全局代理固定指向这里，每个账号的网页用自己的本机凭证连接，
 * 分流器按凭证把请求转到该账号自己的上游代理（或直连）。这样不同代理的账号可以同时在线。
 *
 * <p>凭证只在本机有效（随机生成，不含真实代理密码）；未出示有效凭证的请求一律返回 407。
 * 探测用的主机名（{@link #PROBE_SUFFIX}）只在这里应答，从不连接外网。</p>
 */
final class ProxyHub implements AutoCloseable {
    static final String PROBE_SUFFIX = ".probe.muse.ai";
    private static final int CONNECT_TIMEOUT = 10_000;
    private static final int IDLE_TIMEOUT = 5 * 60_000;

    static final class Credential {
        final String accountId, username, secret;
        final byte[] header;
        volatile ProxySettings upstream;

        Credential(String accountId, ProxySettings upstream) {
            this.accountId = accountId;
            this.upstream = upstream;
            username = "muse-" + UUID.randomUUID();
            secret = UUID.randomUUID().toString();
            header = ProxySettings.basic(username, secret).getBytes(StandardCharsets.US_ASCII);
        }
    }

    private final ServerSocket listener;
    private final ExecutorService workers = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "MuseProxyHub");
        thread.setDaemon(true);
        return thread;
    });
    private final Semaphore permits = new Semaphore(192);
    private final Map<String, Credential> byAccount = new ConcurrentHashMap<>();
    private final Map<Socket, String> sockets = new HashMap<>();
    private final Map<String, BiConsumer<String, String>> probes = new ConcurrentHashMap<>();
    final String realm = "MuseHub-" + UUID.randomUUID();
    private volatile boolean closed;

    ProxyHub() throws IOException {
        listener = new ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"));
        workers.execute(this::accept);
    }

    int port() { return listener.getLocalPort(); }
    String rule() { return "http://127.0.0.1:" + port(); }

    boolean matchesChallenge(String host, String receivedRealm) {
        return !closed && realm.equals(receivedRealm)
            && ("127.0.0.1".equals(host) || ("127.0.0.1:" + port()).equals(host));
    }

    /** 登记（或更新）账号的上游；凭证在分流器存续期间保持不变。上游变了会断开该账号的现有连接。 */
    Credential register(String accountId, ProxySettings upstream) {
        Credential known = byAccount.get(accountId);
        if (known == null) {
            Credential created = new Credential(accountId, upstream);
            Credential raced = byAccount.putIfAbsent(accountId, created);
            return raced == null ? created : raced;
        }
        if (!same(known.upstream, upstream)) {
            known.upstream = upstream;
            dropAccount(accountId);
        }
        return known;
    }

    boolean registered(String accountId) { return byAccount.containsKey(accountId); }

    /** 账号移除或改了代理：注销凭证并断开它的连接。 */
    void unregister(String accountId) {
        if (byAccount.remove(accountId) != null) dropAccount(accountId);
    }

    /** 等待某个探测主机名被请求，回调 (token, 实际出示凭证所属账号；未知为 null)。 */
    void expectProbe(String token, BiConsumer<String, String> seen) { probes.put(token, seen); }
    void cancelProbe(String token) { probes.remove(token); }

    private static boolean same(ProxySettings a, ProxySettings b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return a.scheme.equals(b.scheme) && a.host.equalsIgnoreCase(b.host) && a.port == b.port
            && a.username.equals(b.username) && a.secret.equals(b.secret);
    }

    private void accept() {
        while (!closed) {
            Socket incoming;
            try { incoming = listener.accept(); }
            catch (IOException error) { return; }
            if (!permits.tryAcquire()) { LoopbackProxy.closeQuietly(incoming); continue; }
            if (!track(incoming, null)) { permits.release(); continue; }
            try {
                workers.execute(() -> {
                    try { handle(incoming); } catch (IOException ignored) { }
                    finally { release(incoming); permits.release(); }
                });
            } catch (RejectedExecutionException error) {
                release(incoming);
                permits.release();
            }
        }
    }

    private Credential identify(String presented) {
        if (presented == null) return null;
        byte[] value = presented.getBytes(StandardCharsets.US_ASCII);
        for (Credential credential : byAccount.values()) {
            if (MessageDigest.isEqual(credential.header, value)) return credential;
        }
        return null;
    }

    private void handle(Socket browser) throws IOException {
        browser.setSoTimeout(CONNECT_TIMEOUT);
        InputStream requestStream = new BufferedInputStream(browser.getInputStream());
        String[] headers = LoopbackProxy.readHead(requestStream).split("\r\n");
        String presented = null;
        for (int index = 1; index < headers.length; index++) {
            String header = headers[index];
            int split = header.indexOf(':');
            if (split > 0 && header.substring(0, split).equalsIgnoreCase("Proxy-Authorization")) {
                if (presented != null) return;
                presented = header.substring(split + 1).trim();
            }
        }
        String[] request = headers[0].split(" ");
        Credential credential = identify(presented);
        if (credential == null) {
            LoopbackProxy.response(browser, "407 Proxy Authentication Required", "Proxy-Authenticate: Basic realm=\"" + realm + "\"\r\n");
            return;
        }
        if (request.length != 3 || !"CONNECT".equals(request[0]) || !ProxySettings.isAuthority(request[1])) {
            LoopbackProxy.response(browser, "405 Method Not Allowed", "");
            return;
        }
        String target = request[1];
        String host = target.substring(0, target.lastIndexOf(':')).toLowerCase(Locale.ROOT);
        if (host.endsWith(PROBE_SUFFIX)) {
            // 隔离检测：记录这个探测是由哪个账号的凭证发出的，不连接外网。
            String token = host.substring(0, host.length() - PROBE_SUFFIX.length());
            BiConsumer<String, String> seen = probes.remove(token);
            if (seen != null) seen.accept(token, credential.accountId);
            LoopbackProxy.response(browser, "502 Bad Gateway", "");
            return;
        }
        // 认证通过后才归属账号，方便上游变更时只断开这个账号的连接。
        synchronized (this) { if (sockets.containsKey(browser)) sockets.put(browser, credential.accountId); }
        Socket tunnel;
        try {
            tunnel = open(credential.upstream, target);
        } catch (IOException error) {
            LoopbackProxy.response(browser, "502 Bad Gateway", "");
            return;
        }
        if (!track(tunnel, credential.accountId)) return;
        try {
            LoopbackProxy.write(browser, "HTTP/1.1 200 Connection Established\r\n\r\n");
            browser.setSoTimeout(IDLE_TIMEOUT);
            tunnel.setSoTimeout(IDLE_TIMEOUT);
            try {
                workers.execute(() -> LoopbackProxy.relay(requestStream, tunnel, browser));
                LoopbackProxy.relay(tunnel.getInputStream(), browser, tunnel);
            } catch (RejectedExecutionException ignored) { }
        } finally {
            release(tunnel);
        }
    }

    /** 打开到目标的连接：有上游时经上游代理，否则直连。 */
    static Socket open(ProxySettings upstream, String target) throws IOException {
        if (upstream != null) return LoopbackProxy.openUpstream(upstream, target);
        int split = target.lastIndexOf(':');
        String host = target.substring(0, split);
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        int port = Integer.parseInt(target.substring(split + 1));
        Socket socket = new Socket(Proxy.NO_PROXY);
        try {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT);
            socket.setSoTimeout(IDLE_TIMEOUT);
            return socket;
        } catch (IOException | RuntimeException error) {
            LoopbackProxy.closeQuietly(socket);
            throw error;
        }
    }

    /** 经指定路由查询出口 IP（依次尝试几个公共服务）。 */
    static String exitAddress(ProxySettings upstream) throws IOException {
        String[][] services = {{"api.ipify.org", "/"}, {"icanhazip.com", "/"}, {"ifconfig.me", "/ip"}};
        IOException last = null;
        for (String[] service : services) {
            try { return fetchText(upstream, service[0], service[1]); }
            catch (IOException error) { last = error; }
        }
        throw last == null ? new IOException("无法获取出口 IP") : last;
    }

    private static String fetchText(ProxySettings upstream, String host, String path) throws IOException {
        Socket raw = open(upstream, host + ":443");
        try {
            raw.setSoTimeout(CONNECT_TIMEOUT);
            SSLSocket secure = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(raw, host, 443, true);
            SSLParameters parameters = secure.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            secure.setSSLParameters(parameters);
            secure.startHandshake();
            OutputStream output = secure.getOutputStream();
            output.write(("GET " + path + " HTTP/1.1\r\nHost: " + host + "\r\nUser-Agent: curl/8.0\r\nAccept: text/plain\r\n"
                + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            output.flush();
            InputStream input = secure.getInputStream();
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            byte[] buffer = new byte[2048];
            int read;
            while (body.size() < 16_384 && (read = input.read(buffer)) != -1) body.write(buffer, 0, read);
            String text = new String(body.toByteArray(), StandardCharsets.ISO_8859_1);
            int split = text.indexOf("\r\n\r\n");
            if (split < 0 || !text.startsWith("HTTP/1.1 200") && !text.startsWith("HTTP/1.0 200")) throw new IOException("出口 IP 服务没有返回结果");
            String payload = text.substring(split + 4);
            java.util.regex.Matcher match = java.util.regex.Pattern
                .compile("(\\d{1,3}(?:\\.\\d{1,3}){3}|[0-9a-fA-F]{0,4}(?::[0-9a-fA-F]{0,4}){2,7})").matcher(payload);
            if (!match.find()) throw new IOException("出口 IP 服务返回了无法识别的内容");
            return match.group(1);
        } finally {
            LoopbackProxy.closeQuietly(raw);
        }
    }

    private synchronized boolean track(Socket socket, String accountId) {
        if (closed) { LoopbackProxy.closeQuietly(socket); return false; }
        sockets.put(socket, accountId);
        return true;
    }

    private synchronized void release(Socket socket) {
        sockets.remove(socket);
        LoopbackProxy.closeQuietly(socket);
    }

    private synchronized void dropAccount(String accountId) {
        List<Socket> victims = new ArrayList<>();
        for (Map.Entry<Socket, String> entry : sockets.entrySet()) if (accountId.equals(entry.getValue())) victims.add(entry.getKey());
        for (Socket socket : victims) { sockets.remove(socket); LoopbackProxy.closeQuietly(socket); }
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        LoopbackProxy.closeQuietly(listener);
        Set<Socket> all = new HashSet<>(sockets.keySet());
        sockets.clear();
        for (Socket socket : all) LoopbackProxy.closeQuietly(socket);
        probes.clear();
        byAccount.clear();
        workers.shutdownNow();
    }
}
