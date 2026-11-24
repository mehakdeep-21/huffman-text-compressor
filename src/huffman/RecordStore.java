package huffman;

import java.util.List;

/**
 * Where compression records are saved.
 *
 * Right now FileRecordStore (a plain text file, Java only) implements it.
 * For Project Evaluation 2 a JdbcRecordStore (MySQL) can implement the same
 * interface, and nothing else in the project has to change.
 */
public interface RecordStore {

    /** Saves a record and returns it with its new id. */
    CompressionRecord add(CompressionRecord r);

    /** All records of one user, newest first. */
    List<CompressionRecord> list(String user);

    /** One record, or null if it does not exist or belongs to another user. */
    CompressionRecord get(String user, long id);

    /** Deletes a record. Returns true if something was deleted. */
    boolean delete(String user, long id);
}
