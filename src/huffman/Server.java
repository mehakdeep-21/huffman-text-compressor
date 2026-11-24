package huffman;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * HTTP backend for the Huffman text compressor (JDK only, no external libraries).
 *
 *   GET  /                   main app (login required, else redirects to /login)
 *   GET  /login              login / sign up page
 *   GET  /api/health
 *   POST /api/signup         {"username": "...", "password": "..."}
 *   POST /api/login          {"username": "...", "password": "..."}
 *   POST /api/logout
 *   GET  /api/me             who is logged in
 *   POST /api/compress       {"text": "..."}       (login required, also saves a history record)
 *   GET  /api/records        saved compression records of the logged-in user
 *   POST /api/records/decode {"id": 1}             decode a saved record
 *   POST /api/records/delete {"id": 1}
 *   POST /api/decompress     {"data": "<base64>"}  (login required)
 */
public final class Server {

    private static final int MAX_BODY = 20 * 1024 * 1024; // 20 MB
    private static final Auth AUTH = new Auth();
    private static final RecordStore STORE = new FileRecordStore();   // swap for a JDBC store in PE2

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8000;
        Path index = findStatic("index.html");
        Path loginPage = findStatic("login.html");
        if (index == null || loginPage == null) {
            System.err.println("Warning: static/index.html or static/login.html not found. Run from the project folder.");
        }

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            if (!ex.getRequestMethod().equals("GET")) { error(ex, 405, "Method not allowed."); return; }
            boolean loggedIn = currentUser(ex) != null;
            if (path.equals("/") || path.equals("/index.html")) {
                if (!loggedIn) { redirect(ex, "/login"); return; }
                page(ex, index);
            } else if (path.equals("/login") || path.equals("/signup")) {
                if (loggedIn) { redirect(ex, "/"); return; }
                page(ex, loginPage);
            } else {
                error(ex, 404, "Not found");
            }
        });

        server.createContext("/api/health", ex -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", "ok");
            sendJson(ex, 200, m);
        });

        server.createContext("/api/signup", ex -> handlePost(ex, false, body -> {
            AUTH.signup(str(body, "username"), str(body, "password"));
            return startSession(ex, str(body, "username"), str(body, "password"));
        }));
        server.createContext("/api/login", ex -> handlePost(ex, false, body ->
                startSession(ex, str(body, "username"), str(body, "password"))));
        server.createContext("/api/logout", ex -> handlePost(ex, false, body -> {
            AUTH.logout(Auth.tokenFromCookie(ex.getRequestHeaders().getFirst("Cookie")));
            ex.getResponseHeaders().add("Set-Cookie", "SESSION=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", true);
            return m;
        }));
        server.createContext("/api/me", ex -> {
            String user = currentUser(ex);
            if (user == null) { error(ex, 401, "Please log in."); return; }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("username", user);
            sendJson(ex, 200, m);
        });

        server.createContext("/api/compress", ex -> handlePost(ex, true,
                body -> Api.compress(body, currentUser(ex), STORE)));

        // saved compression records (history)
        server.createContext("/api/records", ex -> {
            String user = currentUser(ex);
            if (user == null) { error(ex, 401, "Please log in."); return; }
            if (!ex.getRequestMethod().equals("GET")) { error(ex, 405, "Use GET."); return; }
            sendJson(ex, 200, Api.listRecords(user, STORE));
        });
        server.createContext("/api/records/decode", ex -> handlePost(ex, true,
                body -> Api.decodeRecord(currentUser(ex), body, STORE)));
        server.createContext("/api/records/delete", ex -> handlePost(ex, true,
                body -> Api.deleteRecord(currentUser(ex), body, STORE)));
        server.createContext("/api/decompress", ex -> handlePost(ex, true, Api::decompress));

        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
        System.out.println("Huffman compressor running at http://localhost:" + port + "  (Ctrl+C to stop)");
    }

    // ------------------------------------------------------------ helpers

    private static Path findStatic(String name) {
        for (String p : new String[] {"static/" + name, "../static/" + name}) {
            Path x = Paths.get(p);
            if (Files.isRegularFile(x)) return x;
        }
        return null;
    }

    private static String currentUser(HttpExchange ex) {
        return AUTH.userFor(Auth.tokenFromCookie(ex.getRequestHeaders().getFirst("Cookie")));
    }

    private static String str(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v instanceof String ? (String) v : null;
    }

    /** Logs in and sets the session cookie on the response. */
    private static Map<String, Object> startSession(HttpExchange ex, String user, String pass) {
        String token = AUTH.login(user, pass);
        ex.getResponseHeaders().add("Set-Cookie", "SESSION=" + token + "; Path=/; HttpOnly; SameSite=Strict");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("username", user.toLowerCase());
        return m;
    }

    private static void page(HttpExchange ex, Path file) throws IOException {
        if (file == null) { error(ex, 500, "Page missing"); return; }
        send(ex, 200, Files.readAllBytes(file), "text/html; charset=utf-8");
    }

    private static void redirect(HttpExchange ex, String to) throws IOException {
        ex.getResponseHeaders().set("Location", to);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(302, -1);
        ex.close();
        System.out.println(ex.getRequestMethod() + " " + ex.getRequestURI().getPath() + " -> 302 " + to);
    }

    @SuppressWarnings("unchecked")
    private static void handlePost(HttpExchange ex, boolean needLogin,
                                   Function<Map<String, Object>, Map<String, Object>> fn) throws IOException {
        try {
            if (!ex.getRequestMethod().equals("POST")) { error(ex, 405, "Use POST."); return; }
            if (needLogin && currentUser(ex) == null) { error(ex, 401, "Please log in first."); return; }
            byte[] body = ex.getRequestBody().readNBytes(MAX_BODY + 1);
            if (body.length > MAX_BODY) { error(ex, 413, "Payload too large (max 20 MB)."); return; }
            Object parsed = Json.parse(new String(body, StandardCharsets.UTF_8));
            if (!(parsed instanceof Map)) throw new IllegalArgumentException("JSON body must be an object.");
            sendJson(ex, 200, fn.apply((Map<String, Object>) parsed));
        } catch (IllegalArgumentException e) {
            error(ex, 400, e.getMessage());
        } catch (Exception e) {
            error(ex, 500, "Server error: " + e);
        }
    }

    private static void error(HttpExchange ex, int code, String msg) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", msg);
        sendJson(ex, code, m);
    }

    private static void sendJson(HttpExchange ex, int code, Map<String, Object> body) throws IOException {
        send(ex, code, Json.stringify(body).getBytes(StandardCharsets.UTF_8), "application/json");
    }

    private static void send(HttpExchange ex, int code, byte[] body, String type) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
        System.out.println(ex.getRequestMethod() + " " + ex.getRequestURI().getPath() + " -> " + code);
    }
}
