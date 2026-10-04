package com.avdbmini.android;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class MiniServer implements Closeable {
    private static final String VERSION = "2.0-gecko";
    private static final Pattern CHARSET = Pattern.compile("charset\\s*=\\s*([^;\\s]+)", Pattern.CASE_INSENSITIVE);

    private final Context context;
    private final ExecutorService workers = Executors.newCachedThreadPool();
    private ServerSocket server;
    private volatile boolean running;
    private byte[] indexBytes;

    MiniServer(Context context) {
        this.context = context.getApplicationContext();
    }

    String start() throws Exception {
        indexBytes = readAll(context.getAssets().open("index.html"));
        server = new ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"));
        running = true;
        Thread acceptThread = new Thread(this::acceptLoop, "avdb-mini-http");
        acceptThread.setDaemon(true);
        acceptThread.start();
        return "http://127.0.0.1:" + server.getLocalPort();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = server.accept();
                workers.execute(() -> handle(socket));
            } catch (Exception e) {
                if (running) { /* ignore one failed connection */ }
            }
        }
    }

    private void handle(Socket socket) {
        try (Socket s = socket;
             InputStream rawIn = new BufferedInputStream(s.getInputStream());
             OutputStream out = new BufferedOutputStream(s.getOutputStream())) {
            String requestLine = readLine(rawIn);
            if (requestLine == null || requestLine.isEmpty()) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2 || !"GET".equals(parts[0])) {
                writeJson(out, 405, new JSONObject().put("error", "method not allowed").toString());
                return;
            }
            // Consume headers.
            String line;
            while ((line = readLine(rawIn)) != null && !line.isEmpty()) {}

            String target = parts[1];
            int qpos = target.indexOf('?');
            String path = qpos >= 0 ? target.substring(0, qpos) : target;
            Map<String,String> q = parseQuery(qpos >= 0 ? target.substring(qpos + 1) : "");

            if ("/".equals(path)) {
                write(out, 200, "text/html; charset=utf-8", indexBytes);
            } else if ("/health".equals(path)) {
                writeJson(out, 200, new JSONObject().put("ok", true).put("version", VERSION).put("engine", "GeckoView").toString());
            } else if ("/api/browse".equals(path)) {
                int t = clamp(intValue(q.get("t"), 0), 0, 7);
                if (t < 1) { writeError(out, 400, "无效分类"); return; }
                LinkedHashMap<String,String> p = new LinkedHashMap<>();
                p.put("ac", "detail"); p.put("t", String.valueOf(t));
                p.put("pg", String.valueOf(clamp(intValue(q.get("pg"),1),1,100000)));
                p.put("pagesize", String.valueOf(clamp(intValue(q.get("pagesize"),24),1,60)));
                p.put("sort_direction", "desc");
                proxy(out, q, p);
            } else if ("/api/search".equals(path)) {
                String text = trim(q.get("q"));
                if (text.isEmpty() || text.length() > 120) { writeError(out,400,"请输入番号、标题或演员关键词"); return; }
                LinkedHashMap<String,String> p = new LinkedHashMap<>();
                p.put("ac", "detail"); p.put("wd", text);
                p.put("pg", String.valueOf(clamp(intValue(q.get("pg"),1),1,100000)));
                p.put("pagesize", String.valueOf(clamp(intValue(q.get("pagesize"),30),1,60)));
                int t = intValue(q.get("t"),0); if (t >= 1 && t <= 7) p.put("t", String.valueOf(t));
                proxy(out, q, p);
            } else if ("/api/detail".equals(path)) {
                String id = trim(q.get("id"));
                if (!id.matches("\\d+")) { writeError(out,400,"无效影片 ID"); return; }
                LinkedHashMap<String,String> p = new LinkedHashMap<>();
                p.put("ac", "detail"); p.put("ids", id);
                proxy(out, q, p);
            } else {
                writeError(out, 404, "not found");
            }
        } catch (Exception ignored) {}
    }

    private void proxy(OutputStream out, Map<String,String> local, LinkedHashMap<String,String> params) {
        try {
            boolean zh = "zh".equals(local.get("lang"));
            String body = fetchAvdb(zh, params);
            if (zh && "1".equals(local.get("bilingual"))) {
                try { body = mergeEnglishFallback(body, fetchAvdb(false, params)); } catch (Exception ignored) {}
            }
            write(out, 200, "application/json; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            try { writeError(out, 502, "无法连接 AVDB API：" + e.getMessage()); } catch (Exception ignored) {}
        }
    }

    private String fetchAvdb(boolean zh, LinkedHashMap<String,String> params) throws Exception {
        StringBuilder qs = new StringBuilder();
        for (Map.Entry<String,String> e : params.entrySet()) {
            if (qs.length() > 0) qs.append('&');
            qs.append(URLEncoder.encode(e.getKey(), "UTF-8"));
            qs.append('=');
            qs.append(URLEncoder.encode(e.getValue(), "UTF-8"));
        }
        URL url = new URL("https://avdbapi.com" + (zh ? "/zh" : "") + "/api.php/provide/vod?" + qs);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(22000);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("Accept-Charset", "utf-8");
        c.setRequestProperty("User-Agent", "AVDB-Mini-Android/" + VERSION);
        c.setInstanceFollowRedirects(true);
        int status = c.getResponseCode();
        if (status < 200 || status >= 300) throw new Exception("HTTP " + status);
        byte[] bytes = readAll(c.getInputStream());
        String charsetName = "UTF-8";
        String ct = c.getContentType();
        if (ct != null) {
            Matcher m = CHARSET.matcher(ct);
            if (m.find()) charsetName = m.group(1).replace("\"", "").replace("'", "");
        }
        Charset cs;
        try { cs = Charset.forName(charsetName); } catch (Exception e) { cs = StandardCharsets.UTF_8; }
        String text = new String(bytes, cs);
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        return text;
    }

    private static String mergeEnglishFallback(String zhText, String enText) throws Exception {
        JSONObject zh = new JSONObject(zhText);
        JSONObject en = new JSONObject(enText);
        JSONArray zl = zh.optJSONArray("list");
        JSONArray el = en.optJSONArray("list");
        if (zl == null || el == null) return zhText;
        Map<String,JSONObject> byId = new LinkedHashMap<>();
        for (int i=0;i<el.length();i++) {
            JSONObject item = el.optJSONObject(i);
            if (item != null) byId.put(item.optString("id"), item);
        }
        for (int i=0;i<zl.length();i++) {
            JSONObject item = zl.optJSONObject(i);
            if (item == null) continue;
            JSONObject fallback = byId.get(item.optString("id"));
            if (fallback != null) item.put("_en", fallback);
        }
        return zh.toString();
    }

    private static Map<String,String> parseQuery(String query) throws Exception {
        LinkedHashMap<String,String> map = new LinkedHashMap<>();
        if (query == null || query.isEmpty()) return map;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq >= 0 ? pair.substring(0,eq) : pair;
            String v = eq >= 0 ? pair.substring(eq+1) : "";
            map.put(URLDecoder.decode(k,"UTF-8"), URLDecoder.decode(v,"UTF-8"));
        }
        return map;
    }

    private static String readLine(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int prev = -1, ch;
        while ((ch = in.read()) != -1) {
            if (prev == '\r' && ch == '\n') break;
            if (prev != -1) b.write(prev);
            prev = ch;
            if (b.size() > 16384) throw new Exception("header too large");
        }
        if (ch == -1 && prev == -1 && b.size() == 0) return null;
        if (prev != -1 && prev != '\r') b.write(prev);
        return b.toString("ISO-8859-1");
    }

    private static byte[] readAll(InputStream in) throws Exception {
        try (InputStream input = in; ByteArrayOutputStream b = new ByteArrayOutputStream()) {
            byte[] buf = new byte[32768];
            int n;
            while ((n = input.read(buf)) >= 0) b.write(buf,0,n);
            return b.toByteArray();
        }
    }

    private static void writeError(OutputStream out, int status, String msg) throws Exception {
        writeJson(out, status, new JSONObject().put("error", msg).toString());
    }
    private static void writeJson(OutputStream out, int status, String json) throws Exception {
        write(out, status, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
    }
    private static void write(OutputStream out, int status, String type, byte[] body) throws Exception {
        String reason = status == 200 ? "OK" : status == 400 ? "Bad Request" : status == 404 ? "Not Found" : status == 405 ? "Method Not Allowed" : "Bad Gateway";
        String headers = "HTTP/1.1 " + status + " " + reason + "\r\n" +
                "Content-Type: " + type + "\r\n" +
                "Content-Length: " + body.length + "\r\n" +
                "Cache-Control: no-store\r\n" +
                "X-Content-Type-Options: nosniff\r\n" +
                "Referrer-Policy: no-referrer\r\n" +
                "Connection: close\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }
    private static int intValue(String s, int def) { try { return Integer.parseInt(s); } catch (Exception e) { return def; } }
    private static int clamp(int n, int lo, int hi) { return Math.max(lo, Math.min(hi,n)); }
    private static String trim(String s) { return s == null ? "" : s.trim(); }

    @Override
    public void close() {
        running = false;
        try { if (server != null) server.close(); } catch (Exception ignored) {}
        workers.shutdownNow();
    }
}
