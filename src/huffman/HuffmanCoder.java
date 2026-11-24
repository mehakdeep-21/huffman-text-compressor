package huffman;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Core Huffman coding engine: tree building, encoding, decoding and a binary file format.
 *
 * File format (.huf), all integers big-endian:
 *   4 bytes  magic "HUF1"
 *   4 bytes  original length in bytes
 *   2 bytes  number of distinct symbols N (0..256)
 *   N * 5    symbol (1 byte) + frequency (4 bytes)
 *   1 byte   number of padding bits in the last payload byte
 *   ...      payload (Huffman-coded bit stream, MSB first)
 */
public final class HuffmanCoder {

    private static final byte[] MAGIC = {'H', 'U', 'F', '1'};
    public static final int BIT_PREVIEW = 512;

    private HuffmanCoder() {}

    /** Tree node. Leaves have sym >= 0; internal nodes have sym == -1. */
    public static final class Node implements Comparable<Node> {
        public final long freq;
        public final int sym;
        public final Node left, right;
        private final int order;

        Node(long freq, int sym, Node left, Node right, int order) {
            this.freq = freq;
            this.sym = sym;
            this.left = left;
            this.right = right;
            this.order = order;
        }

        public boolean isLeaf() { return sym >= 0; }

        // 'order' gives deterministic tie-breaking, so the encoder and decoder
        // always rebuild exactly the same tree from the same frequency table.
        @Override
        public int compareTo(Node o) {
            int c = Long.compare(freq, o.freq);
            return c != 0 ? c : Integer.compare(order, o.order);
        }
    }

    /** Everything the UI needs to know about one compression run. */
    public static final class Result {
        public byte[] blob;
        public long[] freqs;        // indexed by byte value
        public String[] codes;      // indexed by byte value, null if unused
        public Node tree;
        public String bitsPreview;
        public boolean bitsTruncated;
        public int originalBytes, headerBytes, payloadBytes;
        public long payloadBits;
        public double entropy, avgCodeLen;
    }

    /** Builds the Huffman tree from byte frequencies (array of size 256). Returns null if empty. */
    public static Node buildTree(long[] freqs) {
        java.util.PriorityQueue<Node> pq = new java.util.PriorityQueue<>();
        int order = 0;
        for (int s = 0; s < 256; s++) {
            if (freqs[s] > 0) pq.add(new Node(freqs[s], s, null, null, order++));
        }
        if (pq.isEmpty()) return null;
        while (pq.size() > 1) {
            Node a = pq.poll();
            Node b = pq.poll();
            pq.add(new Node(a.freq + b.freq, -1, a, b, order++));
        }
        return pq.poll();
    }

    /** Returns code strings indexed by byte. A single-symbol alphabet gets "0". */
    public static String[] buildCodes(Node root) {
        String[] codes = new String[256];
        if (root == null) return codes;
        if (root.isLeaf()) {
            codes[root.sym] = "0";
            return codes;
        }
        walk(root, "", codes);
        return codes;
    }

    private static void walk(Node n, String prefix, String[] codes) {
        if (n.isLeaf()) {
            codes[n.sym] = prefix;
            return;
        }
        walk(n.left, prefix + "0", codes);
        walk(n.right, prefix + "1", codes);
    }

    /** Printable label for a byte value. */
    public static String symbolLabel(int b) {
        switch (b) {
            case 32: return "' '";
            case 10: return "\\n";
            case 13: return "\\r";
            case 9:  return "\\t";
            default:
                if (b >= 33 && b < 127) return String.valueOf((char) b);
                return String.format("0x%02X", b);
        }
    }

    // ------------------------------------------------------- HashMap code tables

    /** HashMap: byte value -> Huffman code (only symbols that are used). */
    public static java.util.Map<Integer, String> codeTable(String[] codes) {
        java.util.Map<Integer, String> table = new java.util.HashMap<>();
        for (int s = 0; s < codes.length; s++) if (codes[s] != null) table.put(s, codes[s]);
        return table;
    }

    /**
     * Decodes a bit string such as "0110101" using a HashMap of code -> byte value.
     * Because the codes are prefix-free, the first match is always the right symbol.
     */
    public static byte[] decodeBits(String bits, java.util.Map<String, Integer> codeToSym) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < bits.length(); i++) {
            char c = bits.charAt(i);
            if (c != '0' && c != '1') throw new IllegalArgumentException("Bit string may only contain 0 and 1.");
            cur.append(c);
            Integer sym = codeToSym.get(cur.toString());
            if (sym != null) {
                out.write(sym);
                cur.setLength(0);
            }
        }
        if (cur.length() > 0) throw new IllegalArgumentException("Bit string ends in the middle of a code.");
        return out.toByteArray();
    }

    // ------------------------------------------------------------- tree traversal

    private static String nodeLabel(Node n) {
        return n.isLeaf() ? symbolLabel(n.sym) + ":" + n.freq : String.valueOf(n.freq);
    }

    /** Root, then left, then right. (This is also the order used to generate the codes.) */
    public static java.util.List<String> preOrder(Node n) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (n != null) pre(n, out);
        return out;
    }

    private static void pre(Node n, java.util.List<String> out) {
        out.add(nodeLabel(n));
        if (!n.isLeaf()) { pre(n.left, out); pre(n.right, out); }
    }

    /** Left, then root, then right. */
    public static java.util.List<String> inOrder(Node n) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (n != null) in(n, out);
        return out;
    }

    private static void in(Node n, java.util.List<String> out) {
        if (n.isLeaf()) { out.add(nodeLabel(n)); return; }
        in(n.left, out);
        out.add(nodeLabel(n));
        in(n.right, out);
    }

    /** Left, then right, then root. */
    public static java.util.List<String> postOrder(Node n) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (n != null) post(n, out);
        return out;
    }

    private static void post(Node n, java.util.List<String> out) {
        if (!n.isLeaf()) { post(n.left, out); post(n.right, out); }
        out.add(nodeLabel(n));
    }

    /** Level by level, using a queue (breadth-first). */
    public static java.util.List<String> levelOrder(Node root) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (root == null) return out;
        java.util.ArrayDeque<Node> q = new java.util.ArrayDeque<>();
        q.add(root);
        while (!q.isEmpty()) {
            Node n = q.poll();
            out.add(nodeLabel(n));
            if (!n.isLeaf()) { q.add(n.left); q.add(n.right); }
        }
        return out;
    }

    // ------------------------------------------------------------------ compress

    public static Result compress(byte[] raw) {
        long[] freqs = new long[256];
        for (byte b : raw) freqs[b & 0xFF]++;
        Node root = buildTree(freqs);
        String[] codes = buildCodes(root);

        long totalBits = 0;
        int unique = 0;
        for (int s = 0; s < 256; s++) {
            if (freqs[s] > 0) {
                unique++;
                totalBits += freqs[s] * codes[s].length();
            }
        }
        int payloadLen = (int) ((totalBits + 7) / 8);
        byte[] payload = new byte[payloadLen];
        StringBuilder preview = new StringBuilder();
        long bitPos = 0;
        for (byte b : raw) {
            String c = codes[b & 0xFF];
            for (int k = 0; k < c.length(); k++) {
                char ch = c.charAt(k);
                if (ch == '1') payload[(int) (bitPos >> 3)] |= (byte) (0x80 >> (bitPos & 7));
                if (bitPos < BIT_PREVIEW) preview.append(ch);
                bitPos++;
            }
        }
        int pad = (int) (payloadLen * 8L - totalBits);

        int headerLen = 4 + 4 + 2 + unique * 5 + 1;
        ByteBuffer out = ByteBuffer.allocate(headerLen + payloadLen);
        out.put(MAGIC);
        out.putInt(raw.length);
        out.putShort((short) unique);
        for (int s = 0; s < 256; s++) {
            if (freqs[s] > 0) {
                out.put((byte) s);
                out.putInt((int) freqs[s]);
            }
        }
        out.put((byte) pad);
        out.put(payload);

        int n = raw.length;
        double entropy = 0;
        if (n > 0) {
            for (int s = 0; s < 256; s++) {
                if (freqs[s] > 0) {
                    double p = (double) freqs[s] / n;
                    entropy -= p * (Math.log(p) / Math.log(2));
                }
            }
        }

        Result r = new Result();
        r.blob = out.array();
        r.freqs = freqs;
        r.codes = codes;
        r.tree = root;
        r.bitsPreview = preview.toString();
        r.bitsTruncated = totalBits > BIT_PREVIEW;
        r.originalBytes = n;
        r.headerBytes = headerLen;
        r.payloadBytes = payloadLen;
        r.payloadBits = totalBits;
        r.entropy = entropy;
        r.avgCodeLen = n > 0 ? (double) totalBits / n : 0;
        return r;
    }

    public static Result compressText(String text) {
        return compress(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    // ---------------------------------------------------------------- decompress

    /** Decompresses a .huf blob. Throws IllegalArgumentException if the data is invalid. */
    public static byte[] decompress(byte[] blob) {
        if (blob.length < 11 || !Arrays.equals(Arrays.copyOfRange(blob, 0, 4), MAGIC)) {
            throw new IllegalArgumentException("Not a valid Huffman (.huf) file.");
        }
        ByteBuffer buf = ByteBuffer.wrap(blob);
        buf.position(4);
        int origLen = buf.getInt();
        int nsyms = buf.getShort() & 0xFFFF;
        if (origLen < 0 || nsyms > 256) throw new IllegalArgumentException("Corrupt header.");
        if (buf.remaining() < nsyms * 5 + 1) throw new IllegalArgumentException("Corrupt or truncated header.");

        long[] freqs = new long[256];
        long sum = 0;
        for (int i = 0; i < nsyms; i++) {
            int sym = buf.get() & 0xFF;
            long f = buf.getInt() & 0xFFFFFFFFL;
            if (f == 0 || freqs[sym] != 0) throw new IllegalArgumentException("Corrupt frequency table.");
            freqs[sym] = f;
            sum += f;
        }
        int pad = buf.get() & 0xFF;
        if (pad > 7) throw new IllegalArgumentException("Corrupt header.");
        if (origLen == 0) return new byte[0];
        if (nsyms == 0 || sum != origLen) throw new IllegalArgumentException("Corrupt frequency table.");

        int start = buf.position();
        long totalBits = (long) (blob.length - start) * 8 - pad;
        if (totalBits < 0) throw new IllegalArgumentException("Corrupt data length.");

        Node root = buildTree(freqs);
        if (root.isLeaf()) { // single-symbol alphabet
            if (totalBits != origLen) throw new IllegalArgumentException("Corrupt data length.");
            byte[] out = new byte[origLen];
            Arrays.fill(out, (byte) root.sym);
            return out;
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(origLen, 1 << 24));
        Node node = root;
        int produced = 0;
        for (long bit = 0; bit < totalBits && produced < origLen; bit++) {
            int b = (blob[start + (int) (bit >> 3)] >> (7 - (int) (bit & 7))) & 1;
            node = (b == 1) ? node.right : node.left;
            if (node.isLeaf()) {
                out.write(node.sym);
                produced++;
                node = root;
            }
        }
        if (produced != origLen) throw new IllegalArgumentException("Data ended early; file is corrupt.");
        return out.toByteArray();
    }

    public static String decompressText(byte[] blob) {
        byte[] raw = decompress(blob);
        try {
            return java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            throw new IllegalArgumentException("Decoded data is not valid UTF-8 text.");
        }
    }
}
