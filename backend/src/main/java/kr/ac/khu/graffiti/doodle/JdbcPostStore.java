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
            VALUES (:pageId, :authorId, :kind, :status, :moderationState, :moderationDetail,
                    :x, :y, :w, :h, :payload, :payloadBytes, :submittedAt, :publishedAt)""";

    private static final String PUBLISH_WITHHELD = """
            UPDATE doodle SET status = 'PUBLISHED', published_at = :at, removed_at = NULL, removed_by = NULL
            WHERE id = :id AND page_id = :pageId AND status = :from""";

    private static final String REJECT_HELD = """
            UPDATE doodle SET status = 'REJECTED' WHERE id = :id AND status = 'HELD'""";

    private static final String REJECT_PUBLISHED = """
            UPDATE doodle SET status = 'REJECTED', removed_at = :at, removed_by = :adminId
            WHERE id = :id AND page_id = :pageId AND status = 'PUBLISHED'""";

    private static final String DELETE_OWN = """
            UPDATE doodle SET status = 'DELETED', removed_at = :at, removed_by = :authorId
            WHERE id = :id AND page_id = :pageId AND author_id = :authorId AND status = 'PUBLISHED'""";

    private static final String INSERT_ACTION = """
            INSERT INTO moderation_action (actor_type, actor_id, action, target_doodle_id, target_user_id, reason,
                                           detail, created_at)
            VALUES (:actorType, :actorId, :action, :doodleId, :userId, :reason, :detail, :at)""";

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
        return insert(d, "PUBLISHED", publishedAt);
    }

    @Override
    public long insertWithheld(NewDoodle d, String status) {
        if (!"REJECTED".equals(status) && !"HELD".equals(status)) {
            throw new IllegalArgumentException("not a withheld status: " + status);
        }
        return insert(d, status, null);
    }

    @Override
    public boolean publishWithheld(long doodleId, long pageId, String from, Instant publishedAt) {
        return jdbc.update(PUBLISH_WITHHELD, new MapSqlParameterSource("id", doodleId)
                .addValue("pageId", pageId)
                .addValue("from", from)
                .addValue("at", utc(publishedAt))) == 1;
    }

    @Override
    public boolean rejectHeld(long doodleId) {
        return jdbc.update(REJECT_HELD, Map.of("id", doodleId)) == 1;
    }

    @Override
    public boolean rejectPublished(long doodleId, long pageId, long adminId, Instant at) {
        return jdbc.update(REJECT_PUBLISHED, new MapSqlParameterSource("id", doodleId)
                .addValue("pageId", pageId)
                .addValue("adminId", adminId)
                .addValue("at", utc(at))) == 1;
    }

    @Override
    public boolean deleteOwn(long doodleId, long pageId, long authorId, Instant at) {
        return jdbc.update(DELETE_OWN, new MapSqlParameterSource("id", doodleId)
                .addValue("pageId", pageId)
                .addValue("authorId", authorId)
                .addValue("at", utc(at))) == 1;
    }

    @Override
    public void recordAction(Action a, Instant at) {
        jdbc.update(INSERT_ACTION, new MapSqlParameterSource("actorType", a.actorType())
                .addValue("actorId", a.actorId())
                .addValue("action", a.action())
                .addValue("doodleId", a.targetDoodleId())
                .addValue("userId", a.targetUserId())
                .addValue("reason", a.reason())
                .addValue("detail", a.detail())
                .addValue("at", utc(at)));
    }

    private long insert(NewDoodle d, String status, Instant publishedAt) {
        SqlParameterSource params = new MapSqlParameterSource()
                .addValue("status", status)
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
