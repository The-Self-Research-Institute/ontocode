package self.research.ontology.common.db.sql;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

public final class Instants {

    private Instants() {
    }

    public static OffsetDateTime toDb(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public static Instant fromDb(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    public static Instant read(ResultSet rs, String column) throws SQLException {
        return fromDb(rs.getObject(column, OffsetDateTime.class));
    }

    public static OffsetDateTime toDb(LocalDateTime local) {
        return local == null ? null : local.atZone(ZoneId.systemDefault()).toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC);
    }

    public static LocalDateTime readLocal(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
    }
}
