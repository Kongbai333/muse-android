package com.muse.workspace.mobile;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.IDN;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.security.MessageDigest;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;

final class LoopbackProxy implements AutoCloseable {
    static final class Rejected extends IOException {
        Rejected(String message) { super(message); }
    }

    private static final int CONNECT_TIMEOUT = 10_000;
    private static final int IDLE_TIMEOUT = 5 * 60_000;
    private static final int MAX_HEAD = 16_384;
    private final ProxySettings upstream;
    private final ServerSocket listener;
    private final ExecutorService workers = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "MuseProxy");
        thread.setDaemon(true);
        return thread;
    });
    private final Set<Socket> sockets = new HashSet<>();
    private final Semaphore permits = new Semaphore(32);
    private volatile boolean closed;
    final String realm = "MuseMobile-" + UUID.randomUUID();
    final String username = UUID.randomUUID().toString();
    final String secret = UUID.randomUUID().toString();
    private final byte[] localAuthorization = ProxySettings.basic(username, secret).getBytes(StandardCharsets.US_ASCII);

    LoopbackProxy(ProxySettings upstream) throws IOException {
        this.upstream = upstream;
        listener = new ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"));
        workers.execute(this::accept);
    }

    int port() { return listener.getLocalPort(); }
    String rule() { return "http://127.0.0.1:" + port(); }

    boolean matchesChallenge(String host, String receivedRealm) {
        return !closed && realm.equals(receivedRealm)
            && ("127.0.0.1".equals(host) || ("127.0.0.1:" + port()).equals(host));
    }

    static void probe(ProxySettings settings) throws IOException {
        try (Socket socket = openUpstream(settings, "muse.ai:443")) { }
    }

    private void accept() {
        while (!closed) {
            Socket incoming;
            try {
                incoming = listener.accept();
            } catch (IOException error) {
                return;
            }
            if (!permits.tryAcquire()) { closeQuietly(incoming); continue; }
            if (!track(incoming)) { permits.release(); continue; }
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

    private void handle(Socket browser) throws IOException {
        browser.setSoTimeout(CONNECT_TIMEOUT);
        InputStream requestStream = new BufferedInputStream(browser.getInputStream());
        String[] headers = readHead(requestStream).split("\r\n");
        String presented = null;
        for (int index = 1; index < headers.length; index++) {
            String header = headers[index];
            int split = header.indexOf(':');
            if (split > 0 && header.substring(0, split).equalsIgnoreCase("Proxy-Authorization")) {
                if (presented != null) return;
                presented = header.substring(split + 1).trim();
            }
        }
        if (presented == null || !MessageDigest.isEqual(localAuthorization, presented.getBytes(StandardCharsets.US_ASCII))) {
            response(browser, "407 Proxy Authentication Required", "Proxy-Authenticate: Basic realm=\"" + realm + "\"\r\n");
            return;
        }
        String[] request = headers[0].split(" ");
        if (request.length != 3 || !"CONNECT".equals(request[0]) || !ProxySettings.isAuthority(request[1])) {
            response(browser, "405 Method Not Allowed", "");
            return;
        }
        Socket tunnel;
        try {
            tunnel = openUpstream(upstream, request[1]);
        } catch (IOException error) {
            response(browser, "502 Bad Gateway", "");
            return;
        }
        if (!track(tunnel)) return;
        try {
            write(browser, "HTTP/1.1 200 Connection Established\r\n\r\n");
            browser.setSoTimeout(IDLE_TIMEOUT);
            tunnel.setSoTimeout(IDLE_TIMEOUT);
            try {
                workers.execute(() -> relay(requestStream, tunnel, browser));
                relay(tunnel.getInputStream(), browser, tunnel);
            } catch (RejectedExecutionException ignored) { }
        } finally {
            release(tunnel);
        }
    }

    static Socket openUpstream(ProxySettings proxy, String target) throws IOException {
        Socket socket = new Socket(Proxy.NO_PROXY);
        try {
            socket.connect(new InetSocketAddress(proxy.host, proxy.port), CONNECT_TIMEOUT);
            socket.setSoTimeout(CONNECT_TIMEOUT);
            if ("https".equals(proxy.scheme)) {
                SSLSocket secure = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                    .createSocket(socket, proxy.host, proxy.port, true);
                SSLParameters parameters = secure.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                secure.setSSLParameters(parameters);
                secure.startHandshake();
                socket = secure;
            }
            if ("socks5".equals(proxy.scheme)) socksConnect(socket, proxy, target);
            else httpConnect(socket, proxy, target);
            socket.setSoTimeout(IDLE_TIMEOUT);
            return socket;
        } catch (IOException | RuntimeException error) {
            closeQuietly(socket);
            throw error;
        }
    }

    private static void httpConnect(Socket socket, ProxySettings proxy, String target) throws IOException {
        String auth = proxy.hasAuthentication()
            ? "Proxy-Authorization: " + ProxySettings.basic(proxy.username, proxy.secret) + "\r\n" : "";
        write(socket, "CONNECT " + target + " HTTP/1.1\r\nHost: " + target + "\r\n" + auth + "\r\n");
        InputStream input = new BufferedInputStream(socket.getInputStream());
        String status = readHead(input).split("\r\n", 2)[0];
        if (status.matches("HTTP/1\\.[01] 407(?: .*)?")) throw new Rejected("代理拒绝认证，请检查用户名和密码。");
        if (!status.matches("HTTP/1\\.[01] 2[0-9]{2}(?: .*)?")) throw new Rejected("代理未能连接 Muse。");
    }

    private static void socksConnect(Socket socket, ProxySettings proxy, String target) throws IOException {
        URI destination;
        try { destination = URI.create("http://" + target); }
        catch (IllegalArgumentException error) { throw new Rejected("代理目标地址无效。"); }
        String hostname = destination.getHost();
        int port = destination.getPort();
        if (hostname == null || port < 1 || port > 65535) throw new Rejected("代理目标地址无效。");

        byte[] username = proxy.username.getBytes(StandardCharsets.UTF_8);
        byte[] passphrase = proxy.secret.getBytes(StandardCharsets.UTF_8);
        if (username.length > 255 || passphrase.length > 255) throw new Rejected("SOCKS5 用户名或密码过长。");
        OutputStream output = socket.getOutputStream();
        InputStream input = socket.getInputStream();
        output.write(proxy.hasAuthentication() ? new byte[]{5, 2, 0, 2} : new byte[]{5, 1, 0});
        output.flush();
        byte[] selection = readExact(input, 2);
        if (selection[0] != 5 || (selection[1] != 0 && selection[1] != 2))
            throw new Rejected("SOCKS5 代理未接受当前认证方式。");
        if (selection[1] == 2) {
            if (!proxy.hasAuthentication()) throw new Rejected("SOCKS5 代理需要用户名和密码。");
            output.write(1); output.write(username.length); output.write(username);
            output.write(passphrase.length); output.write(passphrase); output.flush();
            byte[] authentication = readExact(input, 2);
            if (authentication[0] != 1 || authentication[1] != 0)
                throw new Rejected("SOCKS5 代理认证失败，请检查用户名和密码。");
        } else if (proxy.hasAuthentication()) {
            throw new Rejected("SOCKS5 代理未启用用户名密码认证。");
        }

        byte[] address = socksAddress(hostname);
        output.write(new byte[]{5, 1, 0});
        output.write(address);
        output.write(port >>> 8);
        output.write(port);
        output.flush();
        byte[] result = readExact(input, 4);
        if (result[0] != 5 || result[1] != 0) throw new Rejected("SOCKS5 代理无法连接目标服务器。");
        int addressLength = switch (result[3] & 0xff) {
            case 1 -> 4;
            case 3 -> readExact(input, 1)[0] & 0xff;
            case 4 -> 16;
            default -> throw new Rejected("SOCKS5 代理返回无效的地址。");
        };
        readExact(input, addressLength + 2);
    }

    private static byte[] socksAddress(String hostname) throws IOException {
        if (hostname.indexOf(':') >= 0) return join((byte) 4, InetAddress.getByName(hostname).getAddress());
        String ascii = IDN.toASCII(hostname, IDN.USE_STD3_ASCII_RULES);
        byte[] encoded = ascii.getBytes(StandardCharsets.US_ASCII);
        if (encoded.length > 255) throw new Rejected("SOCKS5 目标域名过长。");
        return join((byte) 3, new byte[]{(byte) encoded.length}, encoded);
    }

    private static byte[] join(byte type, byte[]... values) {
        int length = 1;
        for (byte[] value : values) length += value.length;
        byte[] result = new byte[length];
        result[0] = type;
        int offset = 1;
        for (byte[] value : values) {
            System.arraycopy(value, 0, result, offset, value.length);
            offset += value.length;
        }
        return result;
    }

    private static byte[] readExact(InputStream input, int length) throws IOException {
        byte[] result = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = input.read(result, offset, length - offset);
            if (count < 0) throw new IOException("SOCKS5 代理返回了不完整的数据。");
            offset += count;
        }
        return result;
    }

    static String readHead(InputStream input) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        long deadline = System.nanoTime() + CONNECT_TIMEOUT * 1_000_000L;
        int tail = 0;
        while (head.size() < MAX_HEAD && System.nanoTime() < deadline) {
            int value = input.read();
            if (value == -1) break;
            head.write(value);
            tail = (tail << 8) | value;
            if (tail == 0x0d0a0d0a) return new String(head.toByteArray(), StandardCharsets.ISO_8859_1);
        }
        throw new IOException("代理没有返回完整响应。");
    }

    static void response(Socket socket, String status, String headers) throws IOException {
        write(socket, "HTTP/1.1 " + status + "\r\n" + headers + "Content-Length: 0\r\nConnection: close\r\n\r\n");
    }

    static void write(Socket socket, String message) throws IOException {
        OutputStream output = socket.getOutputStream();
        output.write(message.getBytes(StandardCharsets.US_ASCII));
        output.flush();
    }

    static void relay(InputStream input, Socket destination, Socket source) {
        try {
            OutputStream output = destination.getOutputStream();
            byte[] buffer = new byte[16_384];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            destination.shutdownOutput();
        } catch (IOException error) {
            closeQuietly(destination);
            closeQuietly(source);
        }
    }

    private synchronized boolean track(Socket socket) {
        if (closed) { closeQuietly(socket); return false; }
        sockets.add(socket);
        return true;
    }

    private synchronized void release(Socket socket) {
        sockets.remove(socket);
        closeQuietly(socket);
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        closeQuietly(listener);
        sockets.forEach(LoopbackProxy::closeQuietly);
        sockets.clear();
        workers.shutdownNow();
    }

    static void closeQuietly(Closeable resource) {
        try { resource.close(); } catch (IOException ignored) { }
    }
}
