package kr.ac.khu.graffiti.doodle;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import kr.ac.khu.graffiti.page.PostStore;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/** {@link PostStore} over MySQL (DB.md §2, §4). Called on the page queue thread, inside its transactions. */
@Repository
class JdbcPostStore implements PostStore {

    private static final String POSTER = """
            SELECT banned_at IS NOT NULL AS banned, role = 'ADMIN' AS admin, last_posted_at
            FROM users WHERE id = :id""";

    // <=> is MySQL's null-safe equality: a first post compares NULL with NULL.
    private static final String RESERVE = """
            UPDATE users SET last_posted_at = :at
            WHERE id = :id AND banned_at IS NULL AND last_posted_at <=> :expected""";

    private static final String RESTORE = """
            UPDATE users SET last_posted_at = :previous
            WHERE id = :id AND last_posted_at = :at""";

    private static final String INSERT_DOODLE = """
            INSERT INTO doodle (page_id, author_id, kind, status, moderation_state, moderation_detail,
                                bbox_x, bbox_y, bbox_w, bbox_h, payload, payload_bytes, submitted_at, published_at)
            VALUES (:pageId, :authorId, :kind, 'PUBLISHED', :moderationState, :moderationDetail,
                    :x, :y, :w, :h, :payload, :payloadBytes, :submittedAt, :publishedAt)""";

    private static final String INSERT_CHUNK = """
            INSERT INTO doodle_chunk (page_id, chunk_index, doodle_id) VALUES (:pageId, :chunk, :doodleId)""";

    private final NamedParameterJdbcTemplate jdbc;

    JdbcPostStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Poster> poster(long userId) {
        return jdbc.query(POSTER, Map.of("id", userId), (rs, n) -> new Poster(rs.getBoolean("banned"),
                rs.getBoolean("admin"), instant(rs.getObject("last_posted_at", LocalDateTime.class)))).stream().findFirst();
    }

    @Override
    public boolean reserve(long userId, Instant expected, Instant at) {
        return jdbc.update(RESERVE, new MapSqlParameterSource("id", userId)
                .addValue("at", utc(at))
                .addValue("expected", utc(expected))) == 1;
    }

    @Override
    public boolean restore(long userId, Instant at, Instant previous) {
        return jdbc.update(RESTORE, new MapSqlParameterSource("id", userId)
                .addValue("at", utc(at))
                .addValue("previous", utc(previous))) == 1;
    }

    @Override
    public long insertPublished(NewDoodle d, Instant publishedAt) {
        SqlParameterSource params = new MapSqlParameterSource()
                .addValue("pageId", d.pageId())
                .addValue("authorId", d.authorId())
                .addValue("kind", d.kind())
                .addValue("moderationState", d.moderationState())
                .addValue("moderationDetail", d.moderationDetail())
                .addValue("x", d.x())
                .addValue("y", d.y())
                .addValue("w", d.w())
                .addValue("h", d.h())
                .addValue("payload", d.payload())
                .addValue("payloadBytes", d.payloadBytes())
                .addValue("submittedAt", utc(d.submittedAt()))
                .addValue("publishedAt", utc(publishedAt));
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(INSERT_DOODLE, params, key, new String[] {"id"});
        long id = key.getKey().longValue();
        SqlParameterSource[] chunks = d.chunks().stream()
                .map(chunk -> new MapSqlParameterSource("pageId", d.pageId())
                        .addValue("chunk", chunk)
                        .addValue("doodleId", id))
                .toArray(SqlParameterSource[]::new);
        jdbc.batchUpdate(INSERT_CHUNK, chunks);
        return id;
    }

    // DATETIME columns hold UTC wall time (hibernate.jdbc.time_zone=UTC). LocalDateTime is bound and read
    // as is, whatever the JVM's time zone.
    private static LocalDateTime utc(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(LocalDateTime time) {
        return time == null ? null : time.toInstant(ZoneOffset.UTC);
    }
}
