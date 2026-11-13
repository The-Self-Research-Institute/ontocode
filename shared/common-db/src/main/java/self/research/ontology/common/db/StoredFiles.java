package self.research.ontology.common.db;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import self.research.ontology.common.db.sql.Instants;
import self.research.ontology.common.db.sql.SqlLists;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class StoredFiles {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final RowMapper<StoredFile> rowMapper;

    public StoredFiles(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.rowMapper = (rs, i) -> new StoredFile(
                rs.getString("id"),
                rs.getString("bucket"),
                rs.getString("filename"),
                rs.getString("content_type"),
                rs.getLong("length"),
                rs.getString("sha256"),
                Instants.read(rs, "upload_date"),
                readMetadata(rs.getString("metadata")),
                rs.getString("storage"));
    }

    public void insert(String id, String bucket, String filename, String contentType, Map<String, Object> metadata,
                       long length, String sha256, String storage) {
        jdbc.update("insert into stored_file(id, bucket, filename, content_type, length, sha256, metadata, storage) "
                        + "values (?, ?, ?, ?, ?, ?, ?::jsonb, ?)",
                id, bucket, filename, contentType, length, sha256, writeMetadata(metadata), storage);
    }

    public void upsert(String id, String bucket, String filename, String contentType, Map<String, Object> metadata,
                       long length, String sha256, String storage, java.time.Instant uploadDate) {
        jdbc.update("insert into stored_file(id, bucket, filename, content_type, length, sha256, upload_date, metadata, storage) "
                        + "values (?, ?, ?, ?, ?, ?, coalesce(?, now()), ?::jsonb, ?) "
                        + "on conflict (id) do update set bucket = excluded.bucket, filename = excluded.filename, "
                        + "content_type = excluded.content_type, length = excluded.length, sha256 = excluded.sha256, "
                        + "upload_date = excluded.upload_date, metadata = excluded.metadata, storage = excluded.storage",
                id, bucket, filename, contentType, length, sha256, Instants.toDb(uploadDate), writeMetadata(metadata), storage);
    }

    public void complete(String id, long length, String sha256) {
        jdbc.update("update stored_file set length = ?, sha256 = ? where id = ?", length, sha256, id);
    }

    public Optional<StoredFile> info(String id) {
        List<StoredFile> rows = jdbc.query("select * from stored_file where id = ?", rowMapper, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<StoredFile> find(String bucket, String filename, Map<String, String> metadataEquals) {
        Where w = where(bucket, filename, metadataEquals);
        return jdbc.query("select * from stored_file where " + w.sql + " order by upload_date desc, id desc",
                rowMapper, w.args.toArray());
    }

    public Optional<StoredFile> findLatest(String bucket, String filename, Map<String, String> metadataEquals) {
        Where w = where(bucket, filename, metadataEquals);
        List<StoredFile> rows = jdbc.query("select * from stored_file where " + w.sql
                + " order by upload_date desc, id desc limit 1", rowMapper, w.args.toArray());
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<StoredFile> recent(String bucket, int limit) {
        return jdbc.query("select * from stored_file where bucket = ? order by upload_date desc, id desc limit ?",
                rowMapper, bucket, limit);
    }

    public boolean deleteRow(String id) {
        return jdbc.update("delete from stored_file where id = ?", id) > 0;
    }

    public List<StoredFile> findAllWhere(String bucket, String filename, Map<String, String> metadataEquals) {
        return find(bucket, filename, metadataEquals);
    }

    public Set<String> existing(List<String> ids) {
        Set<String> found = new HashSet<>();
        if (ids.isEmpty()) {
            return found;
        }
        jdbc.query("select id from stored_file where id in (" + SqlLists.placeholders(ids.size()) + ")",
                rs -> {
                    found.add(rs.getString(1));
                }, ids.toArray());
        return found;
    }

    private record Where(String sql, List<Object> args) {
    }

    private Where where(String bucket, String filename, Map<String, String> metadataEquals) {
        StringBuilder sql = new StringBuilder("bucket = ?");
        List<Object> args = new ArrayList<>();
        args.add(bucket);
        if (filename != null) {
            sql.append(" and filename = ?");
            args.add(filename);
        }
        if (metadataEquals != null) {
            for (Map.Entry<String, String> e : metadataEquals.entrySet()) {
                sql.append(" and metadata ->> ? = ?");
                args.add(e.getKey());
                args.add(e.getValue());
            }
        }
        return new Where(sql.toString(), args);
    }

    private String writeMetadata(Map<String, Object> metadata) {
        try {
            return mapper.writeValueAsString(metadata == null ? Map.of() : metadata);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Map<String, Object> readMetadata(String json) {
        try {
            return json == null ? Map.of() : mapper.readValue(json, MAP_TYPE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
