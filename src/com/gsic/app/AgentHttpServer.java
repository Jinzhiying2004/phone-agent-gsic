package com.gsic.app;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 极简 HTTP 服务器（无第三方依赖），供外部控制器（本机/Tailscale）调用。
 * 所有接口都需要 token 鉴权：?token=xxx 或 Header X-Token。
 * v2.42 起只放行本机回环与 Tailscale 网段，其他来源在 accept 后直接断开
 *（8crsk 安全模式；局域网直连不再可用，Tailscale 断线时云端无法直连）。
 */
public class AgentHttpServer {

    public interface Handler {
        String handle(String method, String path, Map<String, String> query,
                      Map<String, String> headers, String body,
                      byte[] rawBody) throws Exception;
    }

    private final int port;
    private final Handler handler;
    private ServerSocket server;
    private ExecutorService pool;
    private volatile boolean running;

    public AgentHttpServer(int port, Handler handler) {
        this.port = port;
        this.handler = handler;
    }

    public void start() throws Exception {
        server = new ServerSocket(port);
        running = true;
        pool = Executors.newCachedThreadPool();
        Thread t = new Thread(this::loop, "agent-http");
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (Exception ignored) {
        }
        if (pool != null) pool.shutdownNow();
    }

    private void loop() {
        while (running) {
            try {
                Socket s = server.accept();
                if (!allowed(s)) {
                    try {
                        s.close();
                    } catch (Exception ignored) {
                    }
                    continue;
                }
                pool.execute(() -> serve(s));
            } catch (Exception e) {
                if (running) {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
    }

    /**
     * 来源 IP 过滤：只放行本机回环（127.0.0.1/::1）与 Tailscale 网段
     *（100.64.0.0/10，含 IPv6 ULA fd7a:115c:a1e0::/48），其余直接断开。
     */
    private static boolean allowed(Socket s) {
        try {
            java.net.InetAddress a = s.getInetAddress();
            if (a.isLoopbackAddress()) return true;
            String ip = a.getHostAddress();
            if (ip == null) return false;
            if (ip.startsWith("fd7a:115c:a1e0:")) return true;
            return isCgnat(ip);
        } catch (Exception e) {
            return false;
        }
    }

    /** 是否为 100.64.0.0/10（Tailscale 专用网段） */
    static boolean isCgnat(String ip) {
        try {
            String[] p = ip.split("\\.");
            if (p.length != 4 || !p[0].equals("100")) return false;
            int b = Integer.parseInt(p[1]);
            return b >= 64 && b <= 127;
        } catch (Exception e) {
            return false;
        }
    }

    private void serve(Socket sock) {
        try (Socket s = sock) {
            s.setSoTimeout(30000);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();

            // 读头部（直到 \r\n\r\n）
            ByteArrayOutputStream headBuf = new ByteArrayOutputStream();
            int b;
            byte[] last4 = new byte[4];
            int n = 0;
            while ((b = in.read()) != -1) {
                headBuf.write(b);
                last4[n % 4] = (byte) b;
                n++;
                if (n >= 4 && last4[n % 4] == '\r' && last4[(n + 1) % 4] == '\n'
                        && last4[(n + 2) % 4] == '\r' && last4[(n + 3) % 4] == '\n') {
                    break;
                }
                if (headBuf.size() > 65536) return; // 头部过大，拒绝
            }
            String head = new String(headBuf.toByteArray(), StandardCharsets.US_ASCII);
            String[] lines = head.split("\r\n");
            if (lines.length == 0) return;
            String[] req = lines[0].split(" ");
            if (req.length < 2) return;
            String method = req[0];
            String rawPath = req[1];

            String path = rawPath;
            Map<String, String> query = new HashMap<>();
            int qm = rawPath.indexOf('?');
            if (qm >= 0) {
                path = rawPath.substring(0, qm);
                for (String kv : rawPath.substring(qm + 1).split("&")) {
                    int eq = kv.indexOf('=');
                    if (eq > 0) {
                        query.put(URLDecoder.decode(kv.substring(0, eq), "UTF-8"),
                                URLDecoder.decode(kv.substring(eq + 1), "UTF-8"));
                    }
                }
            }

            Map<String, String> headers = new HashMap<>();
            for (int i = 1; i < lines.length; i++) {
                int c = lines[i].indexOf(':');
                if (c > 0) {
                    headers.put(lines[i].substring(0, c).trim().toLowerCase(),
                            lines[i].substring(c + 1).trim());
                }
            }

            int contentLen = 0;
            try {
                contentLen = Integer.parseInt(headers.getOrDefault("content-length", "0"));
            } catch (NumberFormatException ignored) {
            }
            byte[] bodyBytes = new byte[Math.min(contentLen, 1 << 20)];
            int read = 0;
            while (read < bodyBytes.length) {
                int r = in.read(bodyBytes, read, bodyBytes.length - read);
                if (r < 0) break;
                read += r;
            }
            String body = new String(bodyBytes, 0, read, StandardCharsets.UTF_8);

            String respBody;
            int code = 200;
            try {
                respBody = handler.handle(method, path, query, headers, body,
                        java.util.Arrays.copyOf(bodyBytes, read));
            } catch (Exception e) {
                code = 500;
                respBody = "{\"ok\":false,\"error\":\"" + escape(e.getMessage()) + "\"}";
            }
            byte[] rb = respBody.getBytes(StandardCharsets.UTF_8);
            String statusLine = code == 200 ? "HTTP/1.1 200 OK\r\n" : "HTTP/1.1 500 Error\r\n";
            String respHead = statusLine
                    + "Content-Type: application/json; charset=utf-8\r\n"
                    + "Content-Length: " + rb.length + "\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(respHead.getBytes(StandardCharsets.US_ASCII));
            out.write(rb);
            out.flush();
        } catch (Exception ignored) {
        }
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ").replace("\r", " ");
    }
}
