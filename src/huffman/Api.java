package huffman;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Request handling logic, kept separate from HTTP so it can be unit tested. */
public final class Api {

    private Api() {}

    private static double round(double v, int places) {
        double m = Math.pow(10, places);
        return Math.round(v * m) / m;
    }

    private static Map<String, Object> treeToMap(HuffmanCoder.Node n) {
        if (n == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("freq", n.freq);
        if (n.isLeaf()) {
            m.put("sym", n.sym);
            m.put("label", HuffmanCoder.symbolLabel(n.sym));
        } else {
            m.put("left", treeToMap(n.left));
            m.put("right", treeToMap(n.right));
        }
        return m;
    }

    /** Largest input (in bytes) that is saved to the history. */
    public static final int MAX_SAVED_BYTES = 20_000;

    /** POST /api/compress  {"text": "..."}   (no history) */
    public static Map<String, Object> compress(Map<String, Object> payload) {
        return compress(payload, null, null);
    }

    /** Same as above, and also saves a record for 'user' when a store is given. */
    public static Map<String, Object> compress(Map<String, Object> payload, String user, RecordStore store) {
        Object t = payload.get("text");
        if (!(t instanceof String)) throw new IllegalArgumentException("'text' must be a string.");
        byte[] raw = ((String) t).getBytes(StandardCharsets.UTF_8);
        HuffmanCoder.Result r = HuffmanCoder.compress(raw);

        List<Integer> syms = new ArrayList<>();
        for (int s = 0; s < 256; s++) if (r.freqs[s] > 0) syms.add(s);
        syms.sort(Comparator.<Integer>comparingInt(s -> r.codes[s].length()).thenComparingInt(s -> s));

        List<Object> codes = new ArrayList<>();
        for (int s : syms) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("byte", s);
            c.put("label", HuffmanCoder.symbolLabel(s));
            c.put("freq", r.freqs[s]);
            c.put("code", r.codes[s]);
            c.put("bits_total", r.freqs[s] * r.codes[s].length());
            codes.add(c);
        }

        int n = r.originalBytes;
        int comp = r.blob.length;
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("original_bytes", n);
        stats.put("compressed_bytes", comp);
        stats.put("header_bytes", r.headerBytes);
        stats.put("payload_bytes", r.payloadBytes);
        stats.put("payload_bits", r.payloadBits);
        stats.put("unique_symbols", syms.size());
        stats.put("entropy", round(r.entropy, 4));
        stats.put("avg_code_len", round(r.avgCodeLen, 4));
        stats.put("ratio", n > 0 ? round((double) comp / n, 4) : 0.0);
        stats.put("saved_percent", n > 0 ? round((1 - (double) comp / n) * 100, 2) : 0.0);
        stats.put("payload_only_saved_percent", n > 0 ? round((1 - (double) r.payloadBytes / n) * 100, 2) : 0.0);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("stats", stats);
        out.put("codes", codes);
        out.put("tree", treeToMap(r.tree));
        Map<String, Object> trav = new LinkedHashMap<>();
        trav.put("preorder", HuffmanCoder.preOrder(r.tree));
        trav.put("inorder", HuffmanCoder.inOrder(r.tree));
        trav.put("postorder", HuffmanCoder.postOrder(r.tree));
        trav.put("levelorder", HuffmanCoder.levelOrder(r.tree));
        out.put("traversals", trav);
        out.put("bits_preview", r.bitsPreview);
        out.put("bits_truncated", r.bitsTruncated);
        out.put("data", Base64.getEncoder().encodeToString(r.blob));

        if (store != null) {
            if (n == 0) {
                out.put("saved", false);
            } else if (n > MAX_SAVED_BYTES) {
                out.put("saved", false);
                out.put("save_note", "Text is larger than " + MAX_SAVED_BYTES + " bytes, so it was not saved to history.");
            } else {
                CompressionRecord rec = new CompressionRecord();
                rec.user = user;
                String text = (String) t;
                rec.preview = text.length() > 60 ? text.substring(0, 60) + "..." : text;
                rec.originalBytes = n;
                rec.compressedBytes = comp;
                rec.uniqueSymbols = syms.size();
                rec.savedPercent = (Double) stats.get("saved_percent");
                for (Map.Entry<Integer, String> e : HuffmanCoder.codeTable(r.codes).entrySet()) {
                    rec.codes.put(String.valueOf(e.getKey()), e.getValue());
                }
                StringBuilder bits = new StringBuilder();
                for (byte b : raw) bits.append(r.codes[b & 0xFF]);
                rec.bits = bits.toString();
                out.put("saved", true);
                out.put("record_id", store.add(rec).id);
            }
        }
        return out;
    }

    // ------------------------------------------------------------ history

    /** GET /api/records */
    public static Map<String, Object> listRecords(String user, RecordStore store) {
        List<Object> rows = new ArrayList<>();
        for (CompressionRecord r : store.list(user)) rows.add(r.summary());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("records", rows);
        return out;
    }

    private static long idOf(Map<String, Object> payload) {
        Object id = payload.get("id");
        if (!(id instanceof Number)) throw new IllegalArgumentException("'id' must be a number.");
        return ((Number) id).longValue();
    }

    /** POST /api/records/decode  {"id": 3}  - rebuilds the text from the saved bits + code table (HashMap). */
    public static Map<String, Object> decodeRecord(String user, Map<String, Object> payload, RecordStore store) {
        CompressionRecord rec = store.get(user, idOf(payload));
        if (rec == null) throw new IllegalArgumentException("Record not found.");
        Map<String, Integer> codeToSym = new java.util.HashMap<>();
        for (Map.Entry<String, String> e : rec.codes.entrySet()) {
            codeToSym.put(e.getValue(), Integer.parseInt(e.getKey()));
        }
        byte[] raw = HuffmanCoder.decodeBits(rec.bits, codeToSym);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("text", new String(raw, StandardCharsets.UTF_8));
        out.put("bytes", raw.length);
        return out;
    }

    /** POST /api/records/delete  {"id": 3} */
    public static Map<String, Object> deleteRecord(String user, Map<String, Object> payload, RecordStore store) {
        if (!store.delete(user, idOf(payload))) throw new IllegalArgumentException("Record not found.");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        return out;
    }

    /** POST /api/decompress  {"data": "<base64 .huf>"} */
    public static Map<String, Object> decompress(Map<String, Object> payload) {
        Object d = payload.get("data");
        if (!(d instanceof String)) throw new IllegalArgumentException("'data' must be a base64 string.");
        byte[] blob;
        try {
            blob = Base64.getDecoder().decode(((String) d).replaceAll("\\s+", ""));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Input is not valid base64.");
        }
        String text = HuffmanCoder.decompressText(blob);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("text", text);
        out.put("bytes", text.getBytes(StandardCharsets.UTF_8).length);
        return out;
    }
}
