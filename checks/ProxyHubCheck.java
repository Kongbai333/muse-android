package com.muse.workspace.mobile;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** 本机代理分流器：凭证识别、直连转发、探测记录与注销。 */
public final class ProxyHubCheck {
    public static void main(String[] args) throws Exception {
        try (ServerSocket echo = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
             ProxyHub hub = new ProxyHub()) {
            Thread server = new Thread(() -> {
                while (!echo.isClosed()) {
                    try (Socket client = echo.accept()) {
                        int value = client.getInputStream().read();
                        client.getOutputStream().write(value + 1);
                    } catch (Exception ignored) { }
                }
            });
            server.setDaemon(true);
            server.start();
            String target = "127.0.0.1:" + echo.getLocalPort();

            ProxyHub.Credential a = hub.register("account-a", null);
            ProxyHub.Credential b = hub.register("account-b", null);
            require(a != b && !a.username.equals(b.username), "Each account must get its own local credential");
            require(hub.register("account-a", null) == a, "Re-registering must keep the same credential");

            require(status(hub, "CONNECT " + target + " HTTP/1.1", null).startsWith("HTTP/1.1 407"),
                "Requests without a credential must be challenged");
            require(status(hub, "CONNECT " + target + " HTTP/1.1", "Basic bm9ib2R5OnNlY3JldA==").startsWith("HTTP/1.1 407"),
                "Unknown credentials must be challenged");

            try (Socket socket = new Socket("127.0.0.1", hub.port())) {
                socket.setSoTimeout(5000);
                OutputStream output = socket.getOutputStream();
                output.write(("CONNECT " + target + " HTTP/1.1\r\nHost: " + target + "\r\nProxy-Authorization: "
                    + ProxySettings.basic(a.username, a.secret) + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                output.flush();
                InputStream input = socket.getInputStream();
                String line = readLine(input);
                require(line.startsWith("HTTP/1.1 200"), "A registered direct account must get a tunnel: " + line);
                while (!readLine(input).isEmpty()) { }
                output.write(41);
                output.flush();
                require(input.read() == 42, "The tunnel must relay bytes to the target");
            }

            AtomicReference<String> seen = new AtomicReference<>();
            CountDownLatch probed = new CountDownLatch(1);
            hub.expectProbe("tokenb", (token, account) -> { seen.set(account); probed.countDown(); });
            String probe = status(hub, "CONNECT tokenb" + ProxyHub.PROBE_SUFFIX + ":443 HTTP/1.1", ProxySettings.basic(b.username, b.secret));
            require(probe.startsWith("HTTP/1.1 502"), "Probe hosts must be answered locally");
            require(probed.await(3, TimeUnit.SECONDS) && "account-b".equals(seen.get()), "The probe must report the account whose credential was used");

            hub.unregister("account-b");
            require(status(hub, "CONNECT " + target + " HTTP/1.1", ProxySettings.basic(b.username, b.secret)).startsWith("HTTP/1.1 407"),
                "An unregistered account's credential must stop working");
            require(status(hub, "GET http://example.com/ HTTP/1.1", ProxySettings.basic(a.username, a.secret)).startsWith("HTTP/1.1 405"),
                "Only CONNECT tunnels are allowed");
        }
        System.out.println("Proxy hub: credentials, direct tunnel, probe and unregister checks passed.");
    }

    private static String status(ProxyHub hub, String requestLine, String authorization) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", hub.port())) {
            socket.setSoTimeout(5000);
            OutputStream output = socket.getOutputStream();
            output.write((requestLine + "\r\nHost: x\r\n" + (authorization == null ? "" : "Proxy-Authorization: " + authorization + "\r\n")
                + "\r\n").getBytes(StandardCharsets.US_ASCII));
            output.flush();
            return new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII)).readLine();
        }
    }

    private static String readLine(InputStream input) throws Exception {
        StringBuilder line = new StringBuilder();
        int value;
        while ((value = input.read()) != -1) {
            if (value == '\n') break;
            if (value != '\r') line.append((char) value);
        }
        return line.toString();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
