import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * A stand-in BlueBubbles server with made-up conversations, for trying the app without a real Mac or any
 * real messages. Run with: java tools/FakeBlueBubbles.java [port] [password]
 * From the Android emulator the host is http://10.0.2.2:PORT (debug builds allow plain HTTP for this).
 * Port PORT speaks both plain HTTP and a Socket.IO v4 WebSocket feed (a front door hands upgrades to the feed and
 * pipes everything else to an internal HTTP server on PORT+1).
 * Admin routes (all need the password): /admin/incoming?text=..&from=..  /admin/read?chat=..  /admin/drop  /admin/mute  /admin/account?name=..  /admin/sweepcap?n=..  /admin/bulk?n=..  /admin/latency?ms=..
 */
public class FakeBlueBubbles {
    record Chat(String guid, String name, boolean group, List<String> people) {}
    record Msg(String guid, Chat chat, String text, long date, boolean mine, String sender, long read,
               String extra) {}

    static final List<Msg> messages = new ArrayList<>();
    static final Map<String, String> contacts = new HashMap<>();
    static String password = "fake";
    static int counter = 0;

    static String q(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    static String chatJson(Chat c) {
        StringBuilder p = new StringBuilder();
        for (String a : c.people) p.append(p.length() > 0 ? "," : "").append("{\"address\":").append(q(a)).append("}");
        return "{\"guid\":" + q(c.guid) + ",\"style\":" + (c.group ? 43 : 45) + ",\"displayName\":" + q(c.name)
            + ",\"chatIdentifier\":" + q(c.group ? "chat123" : c.people.get(0)) + ",\"participants\":[" + p + "]}";
    }

    static String msgJson(Msg m, boolean withChat) {
        String handle = m.mine ? "null" : "{\"address\":" + q(m.sender) + "}";
        return "{\"guid\":" + q(m.guid) + ",\"text\":" + q(m.text) + ",\"dateCreated\":" + m.date
            + ",\"isFromMe\":" + m.mine + ",\"dateRead\":" + m.read + ",\"dateDelivered\":" + (m.mine ? m.date + 1000 : 0)
            + ",\"handle\":" + handle + (withChat ? ",\"chats\":[" + chatJson(m.chat) + "]" : "") + m.extra + "}";
    }

    static void add(Chat c, String text, long minutesAgo, boolean mine, String sender, boolean unread, String extra) {
        long date = System.currentTimeMillis() - minutesAgo * 60_000L;
        messages.add(new Msg("MSG-" + (++counter), c, text, date, mine, sender, mine || !unread ? date + 5000 : 0, extra));
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8099;
        if (args.length > 1) password = args[1];

        Chat alex = new Chat("iMessage;-;+15550100001", "", false, List.of("+15550100001"));
        Chat sam = new Chat("iMessage;-;+15550100002", "", false, List.of("+15550100002"));
        Chat jordan = new Chat("iMessage;-;+15550100003", "", false, List.of("+15550100003"));
        Chat stranger = new Chat("iMessage;-;+15550100009", "", false, List.of("+15550100009"));
        Chat plans = new Chat("iMessage;+;chat123", "Weekend Plans", true, List.of("+15550100001", "+15550100002"));
        contacts.put("+15550100001", "Alex Rivera");
        contacts.put("+15550100002", "Sam Chen");
        contacts.put("+15550100003", "Jordan Lee");

        add(alex, "Are we still on for lunch tomorrow?", 600, false, "+15550100001", false, "");
        add(alex, "Yes, noon works. I will book the table.", 598, true, null, false, "");
        add(alex, "Perfect. See you there", 590, false, "+15550100001", false, "");
        // A tapback on my message, and a photo with a caption.
        add(alex, "Loved \\\"Yes, noon works. I will book the table.\\\"", 589, false, "+15550100001", false,
            ",\"associatedMessageGuid\":\"p:0/MSG-2\",\"associatedMessageType\":\"love\"");
        add(alex, "Look at this place", 20, false, "+15550100001", false,
            ",\"attachments\":[{\"guid\":\"ATT-1\",\"mimeType\":\"image/png\",\"transferName\":\"place.png\",\"width\":900,\"height\":600}]");
        add(plans, "Weekend Plans", 300, true, null, false, ",\"itemType\":2,\"groupTitle\":\"Weekend Plans\"");
        add(plans, "Who is driving on Saturday?", 240, false, "+15550100002", false, "");
        add(plans, "I can drive, I have room for four", 235, false, "+15550100001", false, "");
        add(plans, "Great, thank you both. Leaving at 8?", 200, true, null, false, "");
        add(plans, "8 is fine by me", 30, false, "+15550100002", true, "");
        add(sam, "Did you see the game last night? It went to overtime and the whole thing was wild from start to finish, "
            + "I could not believe the ending, they were down by twelve with five minutes to go.", 1500, false, "+15550100002", false, "");
        add(sam, "I missed it, tell me everything", 1490, true, null, false, "");
        add(jordan, "Can you send me the address?", 5, false, "+15550100003", true, "");
        add(stranger, "Your package has been delivered", 4000, false, "+15550100009", false, "");

        // A long thread for testing history paging: 230 rows, two minutes apart, row 0 the oldest and row 229 the newest.
        // Rows 60 to 179 are all tapbacks, which the app folds away, so whole pages of history add nothing visible.
        Chat longChat = new Chat("iMessage;-;+15550100012", "", false, List.of("+15550100012"));
        contacts.put("+15550100012", "Riley Long");
        long longBase = 60 * 24 * 3;
        String hearted = "";
        for (int i = 0; i < 230; i++) {
            boolean tapback = i >= 60 && i <= 179;
            boolean mine = i % 3 == 0;
            String sender = mine ? null : "+15550100012";
            if (tapback) {
                add(longChat, "Loved a message", longBase - 2L * i, false, "+15550100012", false,
                    ",\"associatedMessageGuid\":\"p:0/" + hearted + "\",\"associatedMessageType\":\"love\"");
            } else {
                add(longChat, "Long thread message " + i, longBase - 2L * i, mine, sender, false, "");
                if (i == 59) hearted = "MSG-" + counter;
            }
        }

        // Old, quiet chats. With a small sweep cap (see /admin/sweepcap) they sit outside the newest messages, the way
        // quiet chats do on a real server. The dentist's message was never read.
        Chat oldFriend = new Chat("iMessage;-;+15550100010", "", false, List.of("+15550100010"));
        Chat dentist = new Chat("iMessage;-;+15550100011", "", false, List.of("+15550100011"));
        contacts.put("+15550100010", "Casey Morgan");
        add(oldFriend, "Long time no see!", 60 * 24 * 55, false, "+15550100010", false, "");
        add(oldFriend, "We should catch up soon", 60 * 24 * 55 - 5, true, null, false, "");
        add(dentist, "Your appointment is confirmed", 60 * 24 * 40, false, "+15550100011", true, "");

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port + 1), 0);
        server.createContext("/", FakeBlueBubbles::handle);
        server.start();
        startFront(port, port + 1);
        System.out.println("Fake BlueBubbles on port " + port + ", password \"" + password + "\"");
    }

    static Map<String, String> query(HttpExchange ex) {
        Map<String, String> out = new HashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null) return out;
        for (String part : raw.split("&")) {
            int i = part.indexOf('=');
            if (i > 0) out.put(part.substring(0, i), URLDecoder.decode(part.substring(i + 1), StandardCharsets.UTF_8));
        }
        return out;
    }

    static long num(String body, String key, long fallback) {
        int i = body.indexOf("\"" + key + "\"");
        if (i < 0) return fallback;
        int c = body.indexOf(':', i) + 1;
        int e = c;
        while (e < body.length() && (Character.isDigit(body.charAt(e)) || body.charAt(e) == ' ')) e++;
        try { return Long.parseLong(body.substring(c, e).trim()); } catch (Exception x) { return fallback; }
    }

    static String str(String body, String key) {
        int i = body.indexOf("\"" + key + "\"");
        if (i < 0) return "";
        int s = body.indexOf('"', body.indexOf(':', i) + 1) + 1;
        StringBuilder sb = new StringBuilder();
        for (int p = s; p < body.length() && body.charAt(p) != '"'; p++) {
            if (body.charAt(p) == '\\') p++;
            sb.append(body.charAt(p));
        }
        return sb.toString();
    }

    /** The text value of one simple field in a multipart body. */
    static String multipartField(String body, String name) {
        int i = body.indexOf("name=\"" + name + "\"\r\n\r\n");
        if (i < 0) return "";
        int start = body.indexOf("\r\n\r\n", i) + 4;
        return body.substring(start, body.indexOf("\r\n", start));
    }

    static void send(HttpExchange ex, int code, String type, byte[] bytes) throws IOException {
        ex.getResponseHeaders().add("Content-Type", type);
        ex.sendResponseHeaders(code, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    static void json(HttpExchange ex, int code, String body) throws IOException {
        send(ex, code, "application/json", body.getBytes(StandardCharsets.UTF_8));
    }

    static final Map<String, byte[]> uploads = new HashMap<>();
    static final Map<String, String> uploadTypes = new HashMap<>();

    /** Uploads are made to take a couple of seconds so the app's sending state can be seen; the sleep is outside the lock. */
    static void handle(HttpExchange ex) throws IOException {
        String requestPath = ex.getRequestURI().getPath();
        if (requestPath.equals("/api/v1/message/attachment")) {
            try { Thread.sleep(2500); } catch (InterruptedException ignored) { }
        }
        if (latencyMs > 0 && (requestPath.equals("/api/v1/chat/query") || requestPath.equals("/api/v1/message/query"))) {
            try { Thread.sleep(latencyMs); } catch (InterruptedException ignored) { }
        }
        handleLocked(ex);
    }

    static synchronized void handleLocked(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        Map<String, String> q = query(ex);
        byte[] raw = ex.getRequestBody().readAllBytes();
        String body = new String(raw, StandardCharsets.UTF_8);
        System.out.println(ex.getRequestMethod() + " " + path);
        if (!password.equals(q.get("password"))) {
            json(ex, 401, "{\"status\":401,\"message\":\"Unauthorized\"}");
            return;
        }
        List<Msg> newestFirst = new ArrayList<>(messages);
        newestFirst.sort(Comparator.comparingLong((Msg m) -> m.date).reversed());

        if (path.startsWith("/admin/")) {
            admin(ex, path, q);
        } else if (path.equals("/api/v1/server/info")) {
            json(ex, 200, "{\"status\":200,\"data\":{\"server_version\":\"fake\",\"private_api\":false,\"helper_connected\":false,\"detected_icloud\":" + q(account) + "}}");
        } else if (path.equals("/api/v1/chat/count")) {
            java.util.Set<String> guids = new java.util.HashSet<>();
            for (Msg m : messages) guids.add(m.chat.guid);
            json(ex, 200, "{\"status\":200,\"data\":{\"total\":" + guids.size() + "}}");
        } else if (path.equals("/api/v1/chat/query")) {
            int limit = (int) num(body, "limit", 1000);
            int offset = (int) num(body, "offset", 0);
            Map<String, Msg> newest = new java.util.LinkedHashMap<>();
            for (Msg m : newestFirst) newest.putIfAbsent(m.chat.guid, m);
            StringBuilder out = new StringBuilder();
            int skipped = 0, taken = 0;
            for (Msg m : newest.values()) {
                if (skipped++ < offset) continue;
                if (taken++ >= limit) break;
                String chat = chatJson(m.chat);
                out.append(out.length() > 0 ? "," : "").append(chat, 0, chat.length() - 1)
                    .append(",\"lastMessage\":").append(msgJson(m, false)).append("}");
            }
            json(ex, 200, "{\"status\":200,\"data\":[" + out + "]}");
        } else if (path.equals("/api/v1/message/query")) {
            long after = num(body, "after", 0);
            int limit = Math.min((int) num(body, "limit", 100), sweepCap);
            int offset = (int) num(body, "offset", 0);
            StringBuilder out = new StringBuilder();
            int skipped = 0, taken = 0;
            for (Msg m : newestFirst) {
                if (m.date <= after) continue;
                if (skipped++ < offset) continue;
                if (taken++ >= limit) break;
                out.append(out.length() > 0 ? "," : "").append(msgJson(m, true));
            }
            json(ex, 200, "{\"status\":200,\"data\":[" + out + "]}");
        } else if (path.matches("/api/v1/chat/.+/message")) {
            String guid = path.substring("/api/v1/chat/".length(), path.length() - "/message".length());
            long before = q.containsKey("before") ? Long.parseLong(q.get("before")) : Long.MAX_VALUE;
            int limit = Integer.parseInt(q.getOrDefault("limit", "50"));
            StringBuilder out = new StringBuilder();
            int taken = 0;
            for (Msg m : newestFirst) {
                if (!m.chat.guid.equals(guid) || m.date >= before) continue;
                if (taken++ >= limit) break;
                out.append(out.length() > 0 ? "," : "").append(msgJson(m, false));
            }
            json(ex, 200, "{\"status\":200,\"data\":[" + out + "]}");
        } else if (path.equals("/api/v1/message/text")) {
            String guid = str(body, "chatGuid");
            Chat chat = null;
            for (Msg m : messages) if (m.chat.guid.equals(guid)) chat = m.chat;
            if (chat == null) { json(ex, 404, "{\"status\":404}"); return; }
            Msg m = new Msg("MSG-" + (++counter), chat, str(body, "message"), System.currentTimeMillis(), true, null, 0,
                ",\"tempGuid\":" + q(str(body, "tempGuid")));
            messages.add(m);
            broadcastLater("new-message", msgJson(m, true));
            json(ex, 200, "{\"status\":200,\"data\":" + msgJson(m, true) + "}");
        } else if (path.equals("/api/v1/chat/new")) {
            String address = str(body, "addresses");
            int a = body.indexOf("\"addresses\"");
            int s = body.indexOf('"', body.indexOf('[', a)) + 1;
            address = body.substring(s, body.indexOf('"', s));
            Chat chat = new Chat("iMessage;-;" + address, "", false, List.of(address));
            messages.add(new Msg("MSG-" + (++counter), chat, str(body, "message"), System.currentTimeMillis(), true, null, 0, ""));
            json(ex, 200, "{\"status\":200,\"data\":{\"guid\":" + q(chat.guid) + "}}");
        } else if (path.equals("/api/v1/contact")) {
            StringBuilder out = new StringBuilder();
            for (Map.Entry<String, String> c : contacts.entrySet()) {
                out.append(out.length() > 0 ? "," : "").append("{\"displayName\":").append(q(c.getValue()))
                    .append(",\"phoneNumbers\":[{\"address\":").append(q(c.getKey())).append("}],\"emails\":[]}");
            }
            json(ex, 200, "{\"status\":200,\"data\":[" + out + "]}");
        } else if (path.equals("/api/v1/message/attachment")) {
            // Multipart: read it as Latin-1 so the file bytes survive the round trip through a String.
            String s = new String(raw, StandardCharsets.ISO_8859_1);
            String chatGuid = multipartField(s, "chatGuid");
            String tempGuid = multipartField(s, "tempGuid");
            String name = multipartField(s, "name");
            int headerEnd = s.indexOf("\r\n\r\n", s.indexOf("name=\"attachment\""));
            int boundaryEnd = s.indexOf("\r\n--", headerEnd);
            byte[] file = s.substring(headerEnd + 4, boundaryEnd).getBytes(StandardCharsets.ISO_8859_1);
            Chat chat = null;
            for (Msg m : messages) if (m.chat.guid.equals(chatGuid)) chat = m.chat;
            if (chat == null) { json(ex, 404, "{\"status\":404}"); return; }
            String attGuid = "UP-" + (++counter);
            uploads.put(attGuid, file);
            // The type the client gave the file part, so audio is not mislabeled as a photo.
            int typeAt = s.indexOf("Content-Type:", s.indexOf("name=\"attachment\""));
            String mime = typeAt > 0 ? s.substring(typeAt + "Content-Type:".length(), s.indexOf("\r\n", typeAt)).trim() : "image/jpeg";
            uploadTypes.put(attGuid, mime);
            Msg m = new Msg("MSG-" + (++counter), chat, "￼", System.currentTimeMillis(), true, null, 0,
                ",\"tempGuid\":" + q(tempGuid) + ",\"attachments\":[{\"guid\":" + q(attGuid)
                    + ",\"mimeType\":" + q(mime) + ",\"transferName\":" + q(name) + ",\"width\":0,\"height\":0}]");
            messages.add(m);
            System.out.println("  uploaded " + file.length + " bytes as " + name + " (" + mime + "), voice message: "
                + "true".equals(multipartField(s, "isAudioMessage")) + ", method: " + multipartField(s, "method"));
            json(ex, 200, "{\"status\":200,\"data\":" + msgJson(m, true) + "}");
        } else if (path.matches("/api/v1/attachment/.+/download") && uploads.containsKey(path.split("/")[4])) {
            send(ex, 200, uploadTypes.getOrDefault(path.split("/")[4], "image/jpeg"), uploads.get(path.split("/")[4]));
        } else if (path.matches("/api/v1/attachment/.+/download")) {
            BufferedImage img = new BufferedImage(900, 600, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            g.setColor(new Color(60, 90, 140));
            g.fillRect(0, 0, 900, 600);
            g.setColor(new Color(230, 200, 90));
            g.fillOval(600, 60, 160, 160);
            g.setColor(new Color(40, 120, 70));
            g.fillRect(0, 420, 900, 180);
            g.dispose();
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write(img, "png", png);
            send(ex, 200, "image/png", png.toByteArray());
        } else {
            json(ex, 404, "{\"status\":404}");
        }
    }

    // ---- Socket.IO feed ----------------------------------------------------

    static final List<WsClient> clients = new ArrayList<>();
    static volatile boolean muted = false;
    /** The most messages a message query returns, to reproduce chats a sweep of the newest messages never reaches. */
    static volatile int sweepCap = Integer.MAX_VALUE;
    /** A pause added to every chat or message query, so a download is slow enough to watch its progress bar. */
    static volatile int latencyMs = 0;
    static volatile String account = "fake@icloud.com";

    static class WsClient {
        final java.net.Socket socket;
        final java.io.OutputStream out;
        WsClient(java.net.Socket socket) throws IOException { this.socket = socket; this.out = socket.getOutputStream(); }

        synchronized void text(String s) throws IOException {
            byte[] p = s.getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream f = new ByteArrayOutputStream();
            f.write(0x81);
            if (p.length < 126) f.write(p.length);
            else if (p.length < 65536) { f.write(126); f.write(p.length >> 8); f.write(p.length & 0xff); }
            else { f.write(127); for (int i = 7; i >= 0; i--) f.write((int) ((long) p.length >> (8 * i)) & 0xff); }
            f.write(p);
            out.write(f.toByteArray());
            out.flush();
        }

        synchronized void control(int opcode, byte[] payload) throws IOException {
            out.write(0x80 | opcode);
            out.write(payload.length);
            out.write(payload);
            out.flush();
        }
    }

    static void startFront(int port, int inner) throws IOException {
        java.net.ServerSocket front = new java.net.ServerSocket(port);
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    java.net.Socket s = front.accept();
                    new Thread(() -> route(s, inner)).start();
                } catch (IOException e) { return; }
            }
        });
        t.setDaemon(false);
        t.start();
    }

    /** Reads the request head; a WebSocket upgrade goes to the feed, anything else is piped to the HTTP server. */
    static void route(java.net.Socket s, int inner) {
        try {
            java.io.InputStream in = s.getInputStream();
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            int b, state = 0;
            while ((b = in.read()) >= 0) {
                head.write(b);
                state = (b == '\r' && (state == 0 || state == 2)) ? state + 1 : (b == '\n' && (state == 1 || state == 3)) ? state + 1 : 0;
                if (state == 4) break;
            }
            String text = head.toString(StandardCharsets.ISO_8859_1);
            if (text.toLowerCase().contains("upgrade: websocket")) {
                feed(s, in, text);
                return;
            }
            java.net.Socket up = new java.net.Socket("127.0.0.1", inner);
            up.getOutputStream().write(head.toByteArray());
            up.getOutputStream().flush();
            Thread back = new Thread(() -> pipe(up, s));
            back.start();
            // Forward the rest of this side, including any request body that follows the head.
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) { up.getOutputStream().write(buf, 0, n); up.getOutputStream().flush(); }
            up.close();
        } catch (IOException ignored) {
            try { s.close(); } catch (IOException e) { }
        }
    }

    static void pipe(java.net.Socket from, java.net.Socket to) {
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = from.getInputStream().read(buf)) > 0) { to.getOutputStream().write(buf, 0, n); to.getOutputStream().flush(); }
        } catch (IOException ignored) {
        } finally {
            try { to.close(); } catch (IOException e) { }
        }
    }

    static void feed(java.net.Socket s, java.io.InputStream in, String head) throws IOException {
        String line = head.substring(0, head.indexOf("\r\n"));
        String key = "";
        for (String h : head.split("\r\n")) if (h.toLowerCase().startsWith("sec-websocket-key:")) key = h.substring(h.indexOf(':') + 1).trim();
        boolean authed = line.contains("password=" + password);
        if (!authed) {
            s.getOutputStream().write("HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            s.close();
            return;
        }
        try {
            String accept = java.util.Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-1")
                .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.ISO_8859_1)));
            s.getOutputStream().write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: "
                + accept + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IOException(e); }
        WsClient c = new WsClient(s);
        synchronized (clients) { clients.add(c); }
        System.out.println("socket connected (" + clients.size() + " live)");
        c.text("0{\"sid\":\"fake-sid\",\"upgrades\":[],\"pingInterval\":4000,\"pingTimeout\":3000,\"maxPayload\":1000000}");
        Thread pinger = new Thread(() -> {
            try {
                while (!s.isClosed()) {
                    Thread.sleep(4000);
                    if (!muted) c.text("2");
                }
            } catch (Exception ignored) { }
        });
        pinger.setDaemon(true);
        pinger.start();
        try {
            while (true) {
                int b0 = in.read();
                if (b0 < 0) break;
                int opcode = b0 & 0x0f;
                int b1 = in.read();
                long len = b1 & 0x7f;
                if (len == 126) len = (in.read() << 8) | in.read();
                else if (len == 127) { len = 0; for (int i = 0; i < 8; i++) len = (len << 8) | in.read(); }
                byte[] mask = new byte[4];
                if ((b1 & 0x80) != 0) in.readNBytes(mask, 0, 4);
                byte[] p = in.readNBytes((int) len);
                for (int i = 0; i < p.length; i++) p[i] ^= mask[i % 4];
                if (opcode == 8) break;
                if (opcode == 9) { c.control(10, p); continue; }
                if (opcode != 1) continue;
                String m = new String(p, StandardCharsets.UTF_8);
                if (m.equals("40")) c.text("40{\"sid\":\"fake-ns\"}");
            }
        } catch (IOException ignored) {
        } finally {
            synchronized (clients) { clients.remove(c); }
            System.out.println("socket closed (" + clients.size() + " live)");
            try { s.close(); } catch (IOException e) { }
        }
    }

    static void broadcast(String event, String json) {
        List<WsClient> snapshot;
        synchronized (clients) { snapshot = new ArrayList<>(clients); }
        for (WsClient c : snapshot) {
            try { c.text("42[" + q(event) + "," + json + "]"); } catch (IOException ignored) { }
        }
    }

    /** The real server echoes a sent message on the socket shortly after the HTTP call returns. */
    static void broadcastLater(String event, String json) {
        Thread t = new Thread(() -> {
            try { Thread.sleep(300); } catch (InterruptedException ignored) { }
            broadcast(event, json);
        });
        t.start();
    }

    static void admin(HttpExchange ex, String path, Map<String, String> q) throws IOException {
        if (path.equals("/admin/incoming")) {
            String from = q.getOrDefault("from", "+15550100001");
            Chat chat = null;
            for (Msg m : messages) if (!m.chat.group && m.chat.people.contains(from)) chat = m.chat;
            if (chat == null) { chat = new Chat("iMessage;-;" + from, "", false, List.of(from)); }
            Msg m = new Msg("MSG-" + (++counter), chat, q.getOrDefault("text", "hello"), System.currentTimeMillis(), false, from, 0, "");
            messages.add(m);
            broadcast("new-message", msgJson(m, true));
            json(ex, 200, "{\"status\":200}");
        } else if (path.equals("/admin/read")) {
            broadcast("chat-read-status-changed", "{\"chatGuid\":" + q(q.getOrDefault("chat", "")) + ",\"read\":true}");
            json(ex, 200, "{\"status\":200}");
        } else if (path.equals("/admin/drop")) {
            List<WsClient> snapshot;
            synchronized (clients) { snapshot = new ArrayList<>(clients); }
            for (WsClient c : snapshot) c.socket.close();
            json(ex, 200, "{\"status\":200}");
        } else if (path.equals("/admin/bulk")) {
            int n = Integer.parseInt(q.getOrDefault("n", "100"));
            for (int i = 1; i <= n; i++) {
                String address = "+1555030" + String.format("%04d", i);
                Chat chat = new Chat("iMessage;-;" + address, "", false, List.of(address));
                add(chat, "Bulk message " + i, 60L * 24 * 70 + i, false, address, false, "");
            }
            json(ex, 200, "{\"status\":200,\"added\":" + n + "}");
        } else if (path.equals("/admin/latency")) {
            latencyMs = Integer.parseInt(q.getOrDefault("ms", "0"));
            json(ex, 200, "{\"status\":200,\"latencyMs\":" + latencyMs + "}");
        } else if (path.equals("/admin/sweepcap")) {
            sweepCap = Integer.parseInt(q.getOrDefault("n", "2147483647"));
            json(ex, 200, "{\"status\":200,\"sweepCap\":" + sweepCap + "}");
        } else if (path.equals("/admin/account")) {
            account = q.getOrDefault("name", account);
            json(ex, 200, "{\"status\":200}");
        } else if (path.equals("/admin/mute")) {
            muted = !muted;
            json(ex, 200, "{\"status\":200,\"muted\":" + muted + "}");
        } else {
            json(ex, 404, "{\"status\":404}");
        }
    }
}