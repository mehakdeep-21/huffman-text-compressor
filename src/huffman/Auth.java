package huffman;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * User accounts and login sessions (JDK only).
 *
 *  - Users are saved in data/users.txt as   username:salt:hash   (Base64).
 *  - Passwords are never stored. Only a salted PBKDF2-HMAC-SHA256 hash is kept.
 *  - After login the server gives the browser a random session token (cookie).
 */
public final class Auth {

    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_]{3,20}");
    private static final int ITERATIONS = 65_536;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path file;
    private final Map<String, String[]> users = new ConcurrentHashMap<>(); // name -> {salt, hash}
    private final Map<String, String> sessions = new ConcurrentHashMap<>(); // token -> name

    public Auth(Path file) {
        this.file = file;
        load();
    }

    public Auth() { this(Paths.get("data", "users.txt")); }

    // --------------------------------------------------------------- storage

    private void load() {
        try {
            if (!Files.isRegularFile(file)) return;
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (String line : lines) {
                String[] p = line.split(":");
                if (p.length == 3) users.put(p[0], new String[] {p[1], p[2]});
            }
        } catch (IOException e) {
            System.err.println("Could not read users file: " + e.getMessage());
        }
    }

    private void append(String name, String salt, String hash) {
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            Files.write(file, (name + ":" + salt + ":" + hash + "\n").getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException("Could not save user: " + e.getMessage());
        }
    }

    // --------------------------------------------------------------- hashing

    private static byte[] hash(String password, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    // --------------------------------------------------------------- sign up / login

    /** Creates an account. Throws IllegalArgumentException with a user-friendly message. */
    public synchronized void signup(String username, String password) {
        if (username == null || !USERNAME.matcher(username).matches())
            throw new IllegalArgumentException("Username must be 3-20 letters, digits or underscore.");
        if (password == null || password.length() < 6)
            throw new IllegalArgumentException("Password must be at least 6 characters.");
        String key = username.toLowerCase();
        if (users.containsKey(key)) throw new IllegalArgumentException("That username is already taken.");

        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        String s = Base64.getEncoder().encodeToString(salt);
        String h = Base64.getEncoder().encodeToString(hash(password, salt));
        append(key, s, h);
        users.put(key, new String[] {s, h});
    }

    /** Checks the password and returns a new session token. */
    public String login(String username, String password) {
        String[] rec = username == null ? null : users.get(username.toLowerCase());
        // Always compute a hash so a wrong username takes the same time as a wrong password.
        byte[] salt = rec != null ? Base64.getDecoder().decode(rec[0]) : new byte[16];
        byte[] got = hash(password == null ? "" : password, salt);
        boolean ok = rec != null && MessageDigest.isEqual(got, Base64.getDecoder().decode(rec[1]));
        if (!ok) throw new IllegalArgumentException("Wrong username or password.");

        byte[] t = new byte[32];
        RANDOM.nextBytes(t);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(t);
        sessions.put(token, username.toLowerCase());
        return token;
    }

    public void logout(String token) {
        if (token != null) sessions.remove(token);
    }

    /** Returns the username for a session token, or null if not logged in. */
    public String userFor(String token) {
        return token == null ? null : sessions.get(token);
    }

    /** Extracts the SESSION cookie value from a Cookie header (may be null). */
    public static String tokenFromCookie(String cookieHeader) {
        if (cookieHeader == null) return null;
        for (String part : cookieHeader.split(";")) {
            String p = part.trim();
            if (p.startsWith("SESSION=")) return p.substring(8);
        }
        return null;
    }
}
