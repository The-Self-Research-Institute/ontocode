package self.research.ontology.common.db;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import self.research.ontology.common.db.sql.Ids;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class JdbcBlobStore implements BlobStore {

    public static final int CHUNK_SIZE = 1024 * 1024;
    public static final String STORAGE = "database";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final StoredFiles files;

    public JdbcBlobStore(JdbcTemplate jdbc, TransactionTemplate tx, StoredFiles files) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.files = files;
    }

    @Override
    public StoredFile store(String bucket, String filename, String contentType, Map<String, Object> metadata, InputStream content) {
        String id = Ids.newObjectId();
        tx.executeWithoutResult(status -> {
            files.insert(id, bucket, filename, contentType, metadata, 0, "", STORAGE);
            MessageDigest digest = newDigest();
            byte[] buffer = new byte[CHUNK_SIZE];
            long total = 0;
            int index = 0;
            try {
                while (true) {
                    int filled = 0;
                    while (filled < CHUNK_SIZE) {
                        int read = content.read(buffer, filled, CHUNK_SIZE - filled);
                        if (read < 0) {
                            break;
                        }
                        filled += read;
                    }
                    if (filled == 0) {
                        break;
                    }
                    byte[] piece = filled == CHUNK_SIZE ? buffer : Arrays.copyOf(buffer, filled);
                    digest.update(piece, 0, filled);
                    jdbc.update("insert into file_chunk(file_id, n, data) values (?, ?, ?)", id, index++, piece);
                    total += filled;
                    if (filled < CHUNK_SIZE) {
                        break;
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            files.complete(id, total, HexFormat.of().formatHex(digest.digest()));
        });
        return files.info(id).orElseThrow(() -> new IllegalStateException("Stored file vanished: " + id));
    }

    @Override
    public Optional<StoredFile> info(String id) {
        return files.info(id);
    }

    @Override
    public InputStream open(String id) {
        if (files.info(id).isEmpty()) {
            throw new IllegalArgumentException("No stored file " + id);
        }
        return new ChunkedInputStream(jdbc, id);
    }

    @Override
    public List<StoredFile> find(String bucket, String filename, Map<String, String> metadataEquals) {
        return files.find(bucket, filename, metadataEquals);
    }

    @Override
    public Optional<StoredFile> findLatest(String bucket, String filename, Map<String, String> metadataEquals) {
        return files.findLatest(bucket, filename, metadataEquals);
    }

    @Override
    public List<StoredFile> recent(String bucket, int limit) {
        return files.recent(bucket, limit);
    }

    @Override
    public boolean delete(String id) {
        return files.deleteRow(id);
    }

    @Override
    public int deleteWhere(String bucket, String filename, Map<String, String> metadataEquals) {
        int removed = 0;
        for (StoredFile file : files.findAllWhere(bucket, filename, metadataEquals)) {
            if (files.deleteRow(file.id())) {
                removed++;
            }
        }
        return removed;
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class ChunkedInputStream extends InputStream {
        private final JdbcTemplate jdbc;
        private final String id;
        private int next;
        private byte[] current = new byte[0];
        private int position;
        private boolean finished;

        ChunkedInputStream(JdbcTemplate jdbc, String id) {
            this.jdbc = jdbc;
            this.id = id;
        }

        private boolean fill() {
            while (position >= current.length) {
                if (finished) {
                    return false;
                }
                List<byte[]> rows = jdbc.query("select data from file_chunk where file_id = ? and n = ?",
                        (rs, i) -> rs.getBytes(1), id, next);
                if (rows.isEmpty()) {
                    finished = true;
                    return false;
                }
                current = rows.get(0);
                position = 0;
                next++;
            }
            return true;
        }

        @Override
        public int read() {
            return fill() ? (current[position++] & 0xff) : -1;
        }

        @Override
        public int read(byte[] target, int offset, int length) {
            if (length == 0) {
                return 0;
            }
            if (!fill()) {
                return -1;
            }
            int count = Math.min(length, current.length - position);
            System.arraycopy(current, position, target, offset, count);
            position += count;
            return count;
        }
    }
}
