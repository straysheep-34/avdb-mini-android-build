package com.avdbmini.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.BitmapFactory;
import android.util.Base64;

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
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class MiniServer implements Closeable {
    private static final String VERSION = "6.11-android";
    private static final Pattern CHARSET = Pattern.compile("charset\\s*=\\s*([^;\\s]+)", Pattern.CASE_INSENSITIVE);

    private final Context context;
    private final ExecutorService workers = Executors.newCachedThreadPool();
    private ServerSocket server;
    private volatile boolean running;
    private byte[] indexBytes;
    private final Object dataLock = new Object();
    private final ConcurrentHashMap<String, CoverToken> coverTokens = new ConcurrentHashMap<>();
    private final Random random = new Random();
    private static final class CoverToken {
        final String url;
        final long created = System.currentTimeMillis();
        CoverToken(String value) { url = value; }
    }
    private SharedPreferences dataStore() { return context.getSharedPreferences("avdb-mini-data-v611", Context.MODE_PRIVATE); }


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
            s.setSoTimeout(30000);
            String requestLine = readLine(rawIn);
            if (requestLine == null || requestLine.isEmpty()) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2 || !("GET".equals(parts[0]) || "PUT".equals(parts[0]) || "POST".equals(parts[0]))) {
                writeJson(out, 405, new JSONObject().put("error", "method not allowed").toString());
                return;
            }
            Map<String,String> headers = new LinkedHashMap<>();
            String line;
            while ((line = readLine(rawIn)) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0) headers.put(line.substring(0,colon).trim().toLowerCase(Locale.ROOT),line.substring(colon+1).trim());
            }
            String method = parts[0];
            JSONObject payload = new JSONObject();
            if (!"GET".equals(method)) {
                String origin = headers.get("origin");
                if (origin != null && !origin.equals("http://127.0.0.1:" + server.getLocalPort())) {
                    writeError(out,403,"鏉ユ簮鏃犳晥"); return;
                }
                if (headers.containsKey("transfer-encoding")) { writeError(out,400,"涓嶆敮鎸佸垎鍧楄姹?"); return; }
                int length = intValue(headers.get("content-length"),-1);
                if (length < 0 || length > 3 * 1024 * 1024) { writeError(out,400,"璇锋眰澶у皬鏃犳晥"); return; }
                byte[] body = new byte[length];
                int offset = 0;
                while (offset < length) { int n = rawIn.read(body,offset,length-offset); if(n < 0) throw new Exception("璇锋眰浣撲笉瀹屾暣"); offset += n; }
                try { payload = new JSONObject(new String(body,StandardCharsets.UTF_8)); }
                catch (Exception e) { writeError(out,400,"JSON鏍煎紡鏃犳晥"); return; }
            }

            String target = parts[1];
            int qpos = target.indexOf('?');
            String path = qpos >= 0 ? target.substring(0, qpos) : target;
            Map<String,String> q = parseQuery(qpos >= 0 ? target.substring(qpos + 1) : "");

            try {
                if ("/api/favorites".equals(path)) {
                    if ("GET".equals(method)) { synchronized(dataLock) { writeJson(out,200,dataStore().getString("favorites","{\"folders\":[]}")); } }
                    else if ("PUT".equals(method)) {
                        if (payload.optJSONArray("folders") == null) throw new Exception("鏀惰棌鏍煎紡鏃犳晥");
                        synchronized(dataLock) { if(!dataStore().edit().putString("favorites",payload.toString()).commit()) throw new Exception("鏀惰棌淇濆瓨澶辫触"); }
                        writeJson(out,200,"{\"ok\":true}");
                    } else writeError(out,405,"method not allowed");
                    return;
                }
                if ("/api/actor-info".equals(path) && "GET".equals(method)) { writeJson(out,200,actorInfo(q.get("actor")).toString()); return; }
                if ("/api/actor-cover".equals(path) && "GET".equals(method)) { writeJson(out,200,pickActorCover(q.get("actor"),q.get("movieId")).toString()); return; }
                if ("/api/actor-cover-image".equals(path) && "GET".equals(method)) {
                    CoverToken cover = coverTokens.get(q.getOrDefault("token",""));
                    if (cover == null || System.currentTimeMillis()-cover.created > 1800000) throw new Exception("灏侀潰宸茶繃鏈燂紝璇锋崲涓?寮?");
                    RemoteData image = remote(cover.url,true,0);
                    write(out,200,image.type,image.body); return;
                }
                if ("/api/actor-avatar".equals(path) && "PUT".equals(method)) { writeJson(out,200,saveActorCrop(payload.optString("actor"),payload.optString("image")).toString()); return; }
                if ("/api/actor-profile".equals(path)) {
                    if ("POST".equals(method)) writeJson(out,200,readActorProfile(payload.optString("url")).toString());
                    else if ("PUT".equals(method)) writeJson(out,200,saveActorProfile(payload).toString());
                    else writeError(out,405,"method not allowed");
                    return;
                }
            } catch (Exception e) { writeError(out,502,e.getMessage()==null?"璇锋眰澶辫触":e.getMessage()); return; }
            if (!"GET".equals(method)) { writeError(out,405,"method not allowed"); return; }
            if ("/".equals(path)) {
                write(out, 200, "text/html; charset=utf-8", indexBytes);
            } else if ("/health".equals(path)) {
                writeJson(out, 200, new JSONObject().put("ok", true).put("version", VERSION).put("engine", "GeckoView").toString());
            } else if ("/api/browse".equals(path)) {
                int t = clamp(intValue(q.get("t"), 0), 0, 7);
                if (t < 1) { writeError(out, 400, "鏃犳晥鍒嗙被"); return; }
                LinkedHashMap<String,String> p = new LinkedHashMap<>();
                p.put("ac", "detail"); p.put("t", String.valueOf(t));
                p.put("pg", String.valueOf(clamp(intValue(q.get("pg"),1),1,100000)));
                p.put("pagesize", String.valueOf(clamp(intValue(q.get("pagesize"),24),1,60)));
                p.put("sort_direction", "desc");
                proxy(out, q, p);
            } else if ("/api/search".equals(path)) {
                String text = trim(q.get("q"));
                if (text.isEmpty() || text.length() > 120) { writeError(out,400,"璇疯緭鍏ョ暘鍙枫?佹爣棰樻垨婕斿憳鍏抽敭璇?"); return; }
                LinkedHashMap<String,String> p = new LinkedHashMap<>();
                p.put("ac", "detail");
                String field = trim(q.get("field"));
                if ("actor".equals(field) || "category".equals(field) || "code".equals(field) || "director".equals(field)) p.put(field,text);
                else p.put("wd",text);
                p.put("pg", String.valueOf(clamp(intValue(q.get("pg"),1),1,100000)));
                p.put("pagesize", String.valueOf(clamp(intValue(q.get("pagesize"),30),1,60)));
                int t = intValue(q.get("t"),0); if (t >= 1 && t <= 7) p.put("t", String.valueOf(t));
                proxy(out, q, p);
            } else if ("/api/tag".equals(path)) {
                String tag=trim(q.get("category"));
                if(tag.isEmpty() || tag.length()>120) { writeError(out,400,"鏍囩鏃犳晥"); return; }
                LinkedHashMap<String,String> p = new LinkedHashMap<>();
                p.put("ac","detail"); p.put("category",tag);
                p.put("pg",String.valueOf(clamp(intValue(q.get("pg"),1),1,100000)));
                p.put("pagesize",String.valueOf(clamp(intValue(q.get("pagesize"),25),1,60)));
                int t=intValue(q.get("t"),0); if(t>=1 && t<=7)p.put("t",String.valueOf(t));
                proxy(out,q,p);
            } else if ("/api/detail".equals(path)) {
                String id = trim(q.get("id"));
                if (!id.matches("\\d+")) { writeError(out,400,"鏃犳晥褰辩墖 ID"); return; }
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
            try { writeError(out, 502, "鏃犳硶杩炴帴 AVDB API锛?" + e.getMessage()); } catch (Exception ignored) {}
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

    private static String validActor(String value) throws Exception {
        String name=trim(value); if(name.isEmpty() || name.length()>120)throw new Exception("婕斿憳濮撳悕鏃犳晥"); return name;
    }
    private static String actorKey(String value) { return java.text.Normalizer.normalize(value,java.text.Normalizer.Form.NFKC).toLowerCase(Locale.ROOT); }
    private JSONObject readActor(String name) throws Exception {
        synchronized(dataLock) { JSONObject all=new JSONObject(dataStore().getString("actors","{}")); JSONObject record=all.optJSONObject(actorKey(name)); return record==null?new JSONObject():new JSONObject(record.toString()); }
    }
    private JSONObject updateActor(String name, JSONObject changes) throws Exception {
        synchronized(dataLock) {
            JSONObject all=new JSONObject(dataStore().getString("actors","{}"));
            JSONObject record=all.optJSONObject(actorKey(name)); if(record==null)record=new JSONObject();
            java.util.Iterator<String> keys=changes.keys();while(keys.hasNext()){String key=keys.next();record.put(key,changes.get(key));}
            record.put("name",name);all.put(actorKey(name),record);
            if(!dataStore().edit().putString("actors",all.toString()).commit())throw new Exception("婕斿憳鏁版嵁淇濆瓨澶辫触");
            return new JSONObject(record.toString());
        }
    }
    private JSONObject actorInfo(String value) throws Exception {
        String name=validActor(value);JSONObject record=readActor(name);
        if(record.has("total") && System.currentTimeMillis()-record.optLong("countUpdated",0)<86400000)return record;
        try {
            LinkedHashMap<String,String> p=new LinkedHashMap<>();p.put("ac","detail");p.put("actor",name);p.put("pg","1");p.put("pagesize","1");
            JSONObject data=new JSONObject(fetchAvdb(true,p));double total=data.optDouble("total",Double.NaN);
            if(!data.has("total")||data.isNull("total")||data.optString("total").trim().isEmpty()||Double.isNaN(total)||Double.isInfinite(total)||total<0)throw new Exception("API鏈繑鍥炴湁鏁堜綔鍝佹?绘暟");
            return updateActor(name,new JSONObject().put("total",(long)total).put("countUpdated",System.currentTimeMillis()));
        } catch(Exception e) { record.put("name",name);record.put("countError",e.getMessage()==null?"鏁伴噺鏌ヨ澶辫触":e.getMessage());return record; }
    }
    private JSONObject saveActorCrop(String value,String image) throws Exception {
        String name=validActor(value);String prefix="data:image/png;base64,";
        if(image==null||!image.startsWith(prefix)||image.length()>2800000)throw new Exception("澶村儚鏍煎紡鏃犳晥");
        byte[] bytes=Base64.decode(image.substring(prefix.length()),Base64.DEFAULT);
        BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
        if(options.outWidth!=384 || options.outHeight!=512 || !"image/png".equals(options.outMimeType))throw new Exception("澶村儚蹇呴』涓?384脳512 PNG");
        return updateActor(name,new JSONObject().put("image",image).put("source","manual-cover-crop"));
    }
    private JSONObject pickActorCover(String value,String preferred) throws Exception {
        String name=validActor(value);LinkedHashMap<String,String> p=new LinkedHashMap<>();p.put("ac","detail");p.put("actor",name);p.put("pg","1");p.put("pagesize","1");
        boolean usePreferred=preferred!=null&&!preferred.isEmpty();
        if(usePreferred){if(!preferred.matches("\\d+"))throw new Exception("褰辩墖ID鏃犳晥");p.put("ids",preferred);}
        JSONObject data=new JSONObject(fetchAvdb(true,p));
        int pages=clamp(data.optInt("pagecount",data.optInt("total",1)),1,100000);
        int page=usePreferred?1:random.nextInt(pages)+1;
        if(page>1){p.put("pg",String.valueOf(page));data=new JSONObject(fetchAvdb(true,p));}
        JSONArray list=data.optJSONArray("list");JSONObject movie=list==null?null:list.optJSONObject(0);
        if(movie==null)throw new Exception("娌℃湁鍙敤鐨勬紨鍛樺皝闈?");
        if(usePreferred){boolean found=false;Object raw=movie.opt("actor");JSONArray names=raw instanceof JSONArray?(JSONArray)raw:new JSONArray().put(raw);for(int i=0;i<names.length();i++){for(String part:names.optString(i).split("[,锛屻??;锛沑\n\\r]|\\s+/\\s+")){if(actorKey(trim(part)).equals(actorKey(name)))found=true;}}if(!found)throw new Exception("婕斿憳涓嶅尮閰嶏紝璇锋崲涓?寮?");}
        String image=movie.optString("poster_url");if(image.isEmpty())image=movie.optString("thumb_url");URL source=validHttps(image);
        long now=System.currentTimeMillis();for(Map.Entry<String,CoverToken> entry:coverTokens.entrySet())if(now-entry.getValue().created>1800000)coverTokens.remove(entry.getKey());
        if(coverTokens.size()>=100){java.util.Iterator<String> it=coverTokens.keySet().iterator();if(it.hasNext())coverTokens.remove(it.next());}
        String token=UUID.randomUUID().toString();coverTokens.put(token,new CoverToken(source.toString()));
        return new JSONObject().put("image","/api/actor-cover-image?token="+token).put("title",movie.optString("name",movie.optString("movie_code","褰辩墖灏侀潰"))).put("movieId",movie.opt("id"));
    }
    private static URL validHttps(String value) throws Exception {
        URL url=new URL(value);if(!"https".equalsIgnoreCase(url.getProtocol())||url.getUserInfo()!=null)throw new Exception("鍙敮鎸丠TTPS鍥剧墖");return url;
    }
    private static URL validProfile(String value) throws Exception {
        URL url=validHttps(value);if(!"cn.javd.me".equalsIgnoreCase(url.getHost())||url.getPort()!=-1||!url.getPath().matches("/actors/\\d+/"))throw new Exception("璇峰～鍐檆n.javd.me鐨勬暟瀛楁紨鍛樹富椤甸摼鎺?");return url;
    }
    private static final class RemoteData {
        final byte[] body;final String type;
        RemoteData(byte[] value,String mime){body=value;type=mime;}
    }
    private RemoteData remote(String value,boolean image,int redirects) throws Exception {
        URL url=image?validHttps(value):validProfile(value);
        HttpURLConnection c=(HttpURLConnection)url.openConnection();c.setConnectTimeout(15000);c.setReadTimeout(22000);c.setInstanceFollowRedirects(false);c.setRequestProperty("User-Agent","AVDB-Mini-Android/"+VERSION);
        try {
            int status=c.getResponseCode();
            if(status>=300&&status<400){if(redirects>=3)throw new Exception("閲嶅畾鍚戣繃澶?");String location=c.getHeaderField("Location");if(location==null)throw new Exception("閲嶅畾鍚戝湴鍧?缂哄け");return remote(new URL(url,location).toString(),image,redirects+1);}
            if(status!=200)throw new Exception("杩滅▼鍥剧墖鎴栭〉闈TTP "+status);
            String type=c.getContentType();if(type==null)type="";type=type.split(";")[0].trim().toLowerCase(Locale.ROOT);
            if(image&&!type.matches("image/(jpeg|png|webp|avif|gif)"))throw new Exception("杩滅▼鍦板潃涓嶆槸鍙鍙栧浘鐗?");
            int limit=image?12*1024*1024:2*1024*1024;
            try(InputStream in=c.getInputStream();ByteArrayOutputStream bytes=new ByteArrayOutputStream()){byte[] buffer=new byte[16384];int n;while((n=in.read(buffer))!=-1){if(bytes.size()+n>limit)throw new Exception("杩滅▼鍐呭杩囧ぇ");bytes.write(buffer,0,n);}return new RemoteData(bytes.toByteArray(),type);}
        } finally {c.disconnect();}
    }
    private static String htmlText(String value){return value.replace("&quot;","\"").replace("&#39;","'").replace("&apos;","'").replace("&amp;","&").replace("&lt;","<").replace("&gt;",">");}
    private JSONObject readActorProfile(String value) throws Exception {
        URL url=validProfile(value);String html=new String(remote(url.toString(),false,0).body,StandardCharsets.UTF_8);Map<String,String> meta=new LinkedHashMap<>();
        Matcher tags=Pattern.compile("<meta\\b[^>]*>",Pattern.CASE_INSENSITIVE).matcher(html);
        Pattern attr=Pattern.compile("([\\w:-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))");
        while(tags.find()){Map<String,String> attrs=new LinkedHashMap<>();Matcher m=attr.matcher(tags.group());while(m.find()){String v=m.group(2)!=null?m.group(2):m.group(3)!=null?m.group(3):m.group(4);attrs.put(m.group(1).toLowerCase(Locale.ROOT),htmlText(v));}String key=attrs.containsKey("property")?attrs.get("property"):attrs.get("name");if(key!=null&&attrs.containsKey("content"))meta.put(key.toLowerCase(Locale.ROOT),attrs.get("content"));}
        String image=meta.get("og:image");if(image==null)image=meta.get("twitter:image");if(image==null)throw new Exception("鏈壘鍒板叕寮?澶村儚鍏冩暟鎹紝鍙互鏀圭敤灏侀潰瑁佸壀");
        URL target=validHttps(new URL(url,image).toString());return new JSONObject().put("image",target.toString()).put("title",meta.containsKey("og:title")?meta.get("og:title"):"婕斿憳涓婚〉").put("source",url.toString());
    }
    private JSONObject saveActorProfile(JSONObject payload) throws Exception {
        String name=validActor(payload.optString("actor"));String source=validProfile(payload.optString("source")).toString();String image=validHttps(payload.optString("image")).toString();
        return updateActor(name,new JSONObject().put("image",image).put("source",source).put("profileTitle",payload.optString("title")));
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

