package huffman;

import java.util.LinkedHashMap;
import java.util.Map;

/** One saved compression (a row of the "history"). Plain data class. */
public final class CompressionRecord {
    public long id;
    public String user;
    public String created;            // ISO date-time
    public String preview;            // first characters of the input text
    public int originalBytes, compressedBytes, uniqueSymbols;
    public double savedPercent;
    public Map<String, String> codes = new LinkedHashMap<>(); // byte value -> Huffman code
    public String bits = "";          // full encoded bit string ("0101...")

    /** Short form for the history table (no code table / bits). */
    public Map<String, Object> summary() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("created", created);
        m.put("preview", preview);
        m.put("original_bytes", originalBytes);
        m.put("compressed_bytes", compressedBytes);
        m.put("saved_percent", savedPercent);
        m.put("unique_symbols", uniqueSymbols);
        m.put("bit_count", bits.length());
        return m;
    }

    /** Full form used for saving to the file. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = summary();
        m.put("user", user);
        m.put("codes", new LinkedHashMap<String, Object>(codes));
        m.put("bits", bits);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static CompressionRecord fromMap(Map<String, Object> m) {
        CompressionRecord r = new CompressionRecord();
        r.id = ((Number) m.get("id")).longValue();
        r.user = (String) m.get("user");
        r.created = (String) m.get("created");
        r.preview = (String) m.get("preview");
        r.originalBytes = ((Number) m.get("original_bytes")).intValue();
        r.compressedBytes = ((Number) m.get("compressed_bytes")).intValue();
        r.uniqueSymbols = ((Number) m.get("unique_symbols")).intValue();
        r.savedPercent = ((Number) m.get("saved_percent")).doubleValue();
        for (Map.Entry<String, Object> e : ((Map<String, Object>) m.get("codes")).entrySet()) {
            r.codes.put(e.getKey(), (String) e.getValue());
        }
        r.bits = (String) m.get("bits");
        return r;
    }
}
