package com.muse.workspace.mobile;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class ProxyCheck {
    private static final InetAddress LOCAL = InetAddress.getLoopbackAddress();

    public static void main(String[] args) throws Exception {
        socks5Authentication();
        try (ServerSocket fixture = new ServerSocket(0, 2, LOCAL)) {
            fixture.setSoTimeout(5000);
            String encodedFixture = "p" + "%2B" + "a" + "%40" + "ss";
            ProxySettings settings = ProxySettings.parse("http://fixture:" + encodedFixture + "@127.0.0.1:" + fixture.getLocalPort());
            require(settings.rule().equals("http://127.0.0.1:" + fixture.getLocalPort()), "Credentials leaked into rule");
            require(settings.secret.equals("p+a@ss"), "Proxy URL did not decode credentials");
            try (LoopbackProxy bridge = new LoopbackProxy(settings)) {
                try (Socket rejected = browserSocket(bridge)) {
                    write(rejected, "CONNECT muse.ai:443 HTTP/1.1\r\nHost: muse.ai:443\r\n\r\n");
                    require(head(rejected).startsWith("HTTP/1.1 407"), "Anonymous request was not challenged");
                }
                CompletableFuture<Void> exchange = CompletableFuture.runAsync(() -> {
                    try (Socket upstream = fixture.accept()) {
                        upstream.setSoTimeout(5000);
                        String request = head(upstream);
                        String credential = basic("fixture", "p+a@ss");
                        require(request.startsWith("CONNECT muse.ai:443 HTTP/1.1\r\n"), "Wrong upstream destination");
                        require(request.contains("Proxy-Authorization: " + credential + "\r\n"), "Wrong upstream authentication");
                        write(upstream, "HTTP/1.1 200 Connection Established\r\n\r\n");
                        require(upstream.getInputStream().read() == 17, "Tunnel did not carry outgoing bytes");
                        upstream.getOutputStream().write(42);
                    } catch (Exception error) {
                        throw new IllegalStateException(error);
                    }
                });
                try (Socket accepted = browserSocket(bridge)) {
                    write(accepted, "CONNECT muse.ai:443 HTTP/1.1\r\nHost: muse.ai:443\r\nProxy-Authorization: "
                        + basic(bridge.username, bridge.secret) + "\r\n\r\n");
                    require(head(accepted).startsWith("HTTP/1.1 200"), "Proxy tunnel did not open");
                    accepted.getOutputStream().write(17);
                    require(accepted.getInputStream().read() == 42, "Tunnel did not carry incoming bytes");
                }
                exchange.get(5, TimeUnit.SECONDS);
            }
        }
        System.out.println("Authenticated Muse proxy tunnel passed.");
    }

    private static void socks5Authentication() throws Exception {
        try (ServerSocket fixture = new ServerSocket(0, 2, LOCAL)) {
            fixture.setSoTimeout(5000);
            ProxySettings settings = ProxySettings.create("socks5", "127.0.0.1",
                Integer.toString(fixture.getLocalPort()), "fixture", "passphrase");
            CompletableFuture<Void> exchange = CompletableFuture.runAsync(() -> {
                try (Socket upstream = fixture.accept()) {
                    upstream.setSoTimeout(5000);
                    InputStream input = upstream.getInputStream();
                    OutputStream output = upstream.getOutputStream();
                    require(java.util.Arrays.equals(readExactly(input, 4), new byte[]{5, 2, 0, 2}),
                        "SOCKS5 client did not offer authenticated negotiation");
                    output.write(new byte[]{5, 2}); output.flush();
                    require(input.read() == 1, "Wrong SOCKS5 authentication version");
                    int usernameLength = input.read();
                    String username = new String(readExactly(input, usernameLength), StandardCharsets.UTF_8);
                    int passwordLength = input.read();
                    String passphrase = new String(readExactly(input, passwordLength), StandardCharsets.UTF_8);
                    require(username.equals("fixture") && passphrase.equals("passphrase"), "SOCKS5 credentials changed");
                    output.write(new byte[]{1, 0}); output.flush();

                    require(java.util.Arrays.equals(readExactly(input, 4), new byte[]{5, 1, 0, 3}),
                        "SOCKS5 client did not request a domain-name tunnel");
                    int hostLength = input.read();
                    String host = new String(readExactly(input, hostLength), StandardCharsets.US_ASCII);
                    int port = (input.read() << 8) | input.read();
                    require(host.equals("muse.ai") && port == 443, "SOCKS5 target changed");
                    output.write(new byte[]{5, 0, 0, 1, 127, 0, 0, 1, 0, 1}); output.flush();
                } catch (Exception error) {
                    throw new IllegalStateException(error);
                }
            });
            LoopbackProxy.probe(settings);
            exchange.get(5, TimeUnit.SECONDS);
        }
        System.out.println("Authenticated SOCKS5 proxy handshake passed.");
    }

    private static byte[] readExactly(InputStream input, int length) throws Exception {
        byte[] bytes = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = input.read(bytes, offset, length - offset);
            if (read < 0) throw new AssertionError("SOCKS5 server received incomplete request");
            offset += read;
        }
        return bytes;
    }

    private static Socket browserSocket(LoopbackProxy bridge) throws Exception {
        Socket socket = new Socket("127.0.0.1", bridge.port());
        socket.setSoTimeout(5000);
        return socket;
    }

    private static String basic(String user, String pass) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
    }

    private static void write(Socket socket, String message) throws Exception {
        OutputStream stream = socket.getOutputStream();
        stream.write(message.getBytes(StandardCharsets.US_ASCII));
        stream.flush();
    }

    private static String head(Socket socket) throws Exception {
        InputStream input = socket.getInputStream();
        StringBuilder head = new StringBuilder();
        while (head.length() < 16384) {
            int value = input.read();
            if (value == -1) break;
            head.append((char) value);
            int end = head.length();
            if (end >= 4 && head.substring(end - 4).equals("\r\n\r\n")) return head.toString();
        }
        throw new AssertionError("Incomplete proxy header");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
