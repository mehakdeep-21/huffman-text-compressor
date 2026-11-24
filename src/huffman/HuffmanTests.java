package huffman;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Dependency-free test runner. Exit code 1 if any test fails. */
public final class HuffmanTests {

    private static int passed = 0, failed = 0;

    private interface Check { void run() throws Exception; }

    private static void test(String name, Check c) {
        try {
            c.run();
            passed++;
            System.out.println("  PASS  " + name);
        } catch (Throwable t) {
            failed++;
            System.out.println("  FAIL  " + name + " -> " + t);
        }
    }

    private static void assertTrue(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }

    private static void roundTrip(String text) {
        byte[] blob = HuffmanCoder.compressText(text).blob;
        String back = HuffmanCoder.decompressText(blob);
        assertTrue(back.equals(text), "round trip mismatch for: " + text);
    }

    private static boolean throwsIAE(Check c) {
        try { c.run(); } catch (IllegalArgumentException e) { return true; } catch (Exception e) { return false; }
        return false;
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) {
        System.out.println("Running Huffman tests...");

        test("basic", () -> roundTrip("abracadabra"));
        test("empty", () -> roundTrip(""));
        test("single symbol", () -> { roundTrip("aaaaaaaaaaaa"); roundTrip("a"); });
        test("two symbols", () -> roundTrip("ababababbbba"));
        test("unicode and control chars", () ->
                roundTrip("héllo wörld — नमस्ते ਪੰਜਾਬ 😀\nline2\r\n\ttabbed"));

        test("large text compresses well", () -> {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 2000; i++) sb.append("the quick brown fox jumps over the lazy dog. ");
            String text = sb.toString();
            byte[] blob = HuffmanCoder.compressText(text).blob;
            // ~28 distinct symbols => ~4.5 bits/char => roughly 56% of the original size
            assertTrue(blob.length < text.length() * 0.65, "expected >35% reduction, got " + blob.length);
            assertTrue(HuffmanCoder.decompressText(blob).equals(text), "mismatch");
        });

        test("random bytes round trip", () -> {
            byte[] raw = new byte[5000];
            new Random(42).nextBytes(raw);
            byte[] back = HuffmanCoder.decompress(HuffmanCoder.compress(raw).blob);
            assertTrue(Arrays.equals(raw, back), "random bytes mismatch");
        });

        test("codes are prefix free", () -> {
            HuffmanCoder.Result r = HuffmanCoder.compressText("this is an example of a huffman tree");
            for (int a = 0; a < 256; a++) {
                for (int b = 0; b < 256; b++) {
                    if (a != b && r.codes[a] != null && r.codes[b] != null) {
                        assertTrue(!r.codes[b].startsWith(r.codes[a]), "prefix clash " + a + "/" + b);
                    }
                }
            }
        });

        test("frequent symbols get shorter codes", () -> {
            HuffmanCoder.Result r = HuffmanCoder.compressText("aaaaaaaaaabbbbcc d");
            assertTrue(r.codes['a'].length() <= r.codes['d'].length(), "a should be <= d");
        });

        test("invalid and corrupt files rejected", () -> {
            assertTrue(throwsIAE(() -> HuffmanCoder.decompress("garbage data here".getBytes())), "garbage");
            assertTrue(throwsIAE(() -> HuffmanCoder.decompress("HUF1".getBytes())), "short");
            byte[] blob = HuffmanCoder.compressText("hello world, hello huffman").blob;
            byte[] cut = Arrays.copyOf(blob, blob.length - 3);
            assertTrue(throwsIAE(() -> HuffmanCoder.decompress(cut)), "truncated");
        });

        test("JSON parse and stringify", () -> {
            Map<String, Object> m = (Map<String, Object>) Json.parse(
                    "{\"text\":\"a\\n\\\"q\\\" \\u00e9 \\ud83d\\ude00\",\"n\":[1,2,{\"x\":null}],\"b\":true}");
            assertTrue(m.get("text").equals("a\n\"q\" é 😀"), "string escapes");
            assertTrue(((List<Object>) m.get("n")).size() == 3, "array");
            String s = Json.stringify(m);
            Map<String, Object> m2 = (Map<String, Object>) Json.parse(s);
            assertTrue(m2.get("text").equals(m.get("text")), "re-parse");
            assertTrue(throwsIAE(() -> Json.parse("{bad json")), "invalid json");
        });

        test("API round trip", () -> {
            Map<String, Object> req = new java.util.HashMap<>();
            req.put("text", "hello hello hello");
            Map<String, Object> res = Api.compress(req);
            Map<String, Object> stats = (Map<String, Object>) res.get("stats");
            assertTrue(((Integer) stats.get("original_bytes")) == 17, "original_bytes");
            Map<String, Object> dreq = new java.util.HashMap<>();
            dreq.put("data", res.get("data"));
            assertTrue(Api.decompress(dreq).get("text").equals("hello hello hello"), "api text");
        });

        test("API errors", () -> {
            Map<String, Object> bad1 = new java.util.HashMap<>();
            bad1.put("text", 5.0);
            assertTrue(throwsIAE(() -> Api.compress(bad1)), "non-string text");
            Map<String, Object> bad2 = new java.util.HashMap<>();
            bad2.put("data", "!!!not base64!!!");
            assertTrue(throwsIAE(() -> Api.decompress(bad2)), "bad base64");
        });

        test("auth: signup, login, logout", () -> {
            java.nio.file.Path f = java.nio.file.Files.createTempDirectory("huf").resolve("users.txt");
            Auth auth = new Auth(f);
            auth.signup("Alice_1", "secret12");
            String tok = auth.login("alice_1", "secret12");   // username is case-insensitive
            assertTrue("alice_1".equals(auth.userFor(tok)), "session user");
            auth.logout(tok);
            assertTrue(auth.userFor(tok) == null, "logged out");
            String stored = new String(java.nio.file.Files.readAllBytes(f), StandardCharsets.UTF_8);
            assertTrue(!stored.contains("secret12"), "password must not be stored in plain text");
            Auth reloaded = new Auth(f);                        // users survive a restart
            assertTrue(reloaded.login("alice_1", "secret12") != null, "persisted user");
        });

        test("auth: validation and wrong credentials", () -> {
            Auth auth = new Auth(java.nio.file.Files.createTempDirectory("huf").resolve("u.txt"));
            auth.signup("bob", "password1");
            assertTrue(throwsIAE(() -> auth.signup("BOB", "password2")), "duplicate username");
            assertTrue(throwsIAE(() -> auth.signup("a", "password1")), "short username");
            assertTrue(throwsIAE(() -> auth.signup("bad name!", "password1")), "bad characters");
            assertTrue(throwsIAE(() -> auth.signup("carol", "123")), "short password");
            assertTrue(throwsIAE(() -> auth.login("bob", "wrong")), "wrong password");
            assertTrue(throwsIAE(() -> auth.login("nobody", "password1")), "unknown user");
            assertTrue(Auth.tokenFromCookie("a=1; SESSION=xyz; b=2").equals("xyz"), "cookie parse");
        });

        test("decode from bit string + HashMap code table", () -> {
            String text = "this is an example of a huffman tree é😀";
            HuffmanCoder.Result r = HuffmanCoder.compressText(text);
            Map<String, Integer> rev = new java.util.HashMap<>();
            for (Map.Entry<Integer, String> e : HuffmanCoder.codeTable(r.codes).entrySet()) rev.put(e.getValue(), e.getKey());
            StringBuilder bits = new StringBuilder();
            for (byte b : text.getBytes(StandardCharsets.UTF_8)) bits.append(r.codes[b & 0xFF]);
            assertTrue(new String(HuffmanCoder.decodeBits(bits.toString(), rev), StandardCharsets.UTF_8).equals(text), "decoded text");
            assertTrue(throwsIAE(() -> HuffmanCoder.decodeBits("012", rev)), "non-bit char");
        });

        test("tree traversals", () -> {
            HuffmanCoder.Result r = HuffmanCoder.compressText("aaabbc");   // 3 leaves + 2 internal = 5 nodes
            assertTrue(HuffmanCoder.preOrder(r.tree).size() == 5, "preorder size");
            assertTrue(HuffmanCoder.inOrder(r.tree).size() == 5, "inorder size");
            assertTrue(HuffmanCoder.postOrder(r.tree).size() == 5, "postorder size");
            assertTrue(HuffmanCoder.levelOrder(r.tree).get(0).equals("6"), "root frequency first in level order");
            assertTrue(HuffmanCoder.postOrder(r.tree).get(4).equals("6"), "root last in postorder");
        });

        test("history: save, list, decode, delete, per-user", () -> {
            java.nio.file.Path f = java.nio.file.Files.createTempDirectory("huf").resolve("records.txt");
            RecordStore store = new FileRecordStore(f);
            Map<String, Object> req = new java.util.HashMap<>();
            req.put("text", "hello hello huffman\nline 2 é");
            Map<String, Object> res = Api.compress(req, "alice", store);
            assertTrue(Boolean.TRUE.equals(res.get("saved")), "saved flag");
            long id = (Long) res.get("record_id");
            Api.compress(req, "bob", store);

            assertTrue(((List<Object>) Api.listRecords("alice", store).get("records")).size() == 1, "alice sees only hers");
            Map<String, Object> idReq = new java.util.HashMap<>();
            idReq.put("id", (double) id);
            assertTrue(Api.decodeRecord("alice", idReq, store).get("text").equals("hello hello huffman\nline 2 é"), "decoded record");
            assertTrue(throwsIAE(() -> Api.decodeRecord("bob", idReq, store)), "bob cannot read alice's record");

            RecordStore reloaded = new FileRecordStore(f);        // survives a restart
            assertTrue(reloaded.get("alice", id) != null, "persisted record");
            Api.deleteRecord("alice", idReq, reloaded);
            assertTrue(reloaded.list("alice").isEmpty(), "deleted");
            assertTrue(throwsIAE(() -> Api.deleteRecord("alice", idReq, reloaded)), "delete twice");
        });

        test("history: large text is not saved", () -> {
            RecordStore store = new FileRecordStore(java.nio.file.Files.createTempDirectory("huf").resolve("r.txt"));
            Map<String, Object> req = new java.util.HashMap<>();
            req.put("text", "ab".repeat(Api.MAX_SAVED_BYTES));
            assertTrue(Boolean.FALSE.equals(Api.compress(req, "alice", store).get("saved")), "too large");
            assertTrue(store.list("alice").isEmpty(), "nothing stored");
        });

        System.out.println("\n" + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
