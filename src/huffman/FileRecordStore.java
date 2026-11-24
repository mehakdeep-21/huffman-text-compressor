package huffman;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Saves records in data/records.txt, one JSON object per line. Needs no database. */
public final class FileRecordStore implements RecordStore {

    private static final int MAX_PER_USER = 100;   // oldest records are dropped after this

    private final Path file;
    private final List<CompressionRecord> all = new ArrayList<>();
    private long nextId = 1;

    public FileRecordStore(Path file) {
        this.file = file;
        load();
    }

    public FileRecordStore() { this(Paths.get("data", "records.txt")); }

    @SuppressWarnings("unchecked")
    private void load() {
        try {
            if (!Files.isRegularFile(file)) return;
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                try {
                    CompressionRecord r = CompressionRecord.fromMap((Map<String, Object>) Json.parse(line));
                    all.add(r);
                    nextId = Math.max(nextId, r.id + 1);
                } catch (RuntimeException bad) {
                    System.err.println("Skipping a damaged record line.");
                }
            }
        } catch (IOException e) {
            System.err.println("Could not read records file: " + e.getMessage());
        }
    }

    private void saveAll() {
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            StringBuilder sb = new StringBuilder();
            for (CompressionRecord r : all) sb.append(Json.stringify(r.toMap())).append('\n');
            Files.write(file, sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Could not save record: " + e.getMessage());
        }
    }

    @Override
    public synchronized CompressionRecord add(CompressionRecord r) {
        r.id = nextId++;
        r.created = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
        all.add(r);
        // keep only the newest MAX_PER_USER records for this user
        int count = 0;
        for (int i = all.size() - 1; i >= 0; i--) {
            if (all.get(i).user.equals(r.user) && ++count > MAX_PER_USER) all.remove(i);
        }
        saveAll();
        return r;
    }

    @Override
    public synchronized List<CompressionRecord> list(String user) {
        List<CompressionRecord> out = new ArrayList<>();
        for (int i = all.size() - 1; i >= 0; i--) {
            if (all.get(i).user.equals(user)) out.add(all.get(i));
        }
        return out;
    }

    @Override
    public synchronized CompressionRecord get(String user, long id) {
        for (CompressionRecord r : all) if (r.id == id && r.user.equals(user)) return r;
        return null;
    }

    @Override
    public synchronized boolean delete(String user, long id) {
        boolean removed = all.removeIf(r -> r.id == id && r.user.equals(user));
        if (removed) saveAll();
        return removed;
    }
}
