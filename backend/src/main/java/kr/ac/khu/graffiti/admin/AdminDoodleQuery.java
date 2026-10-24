package kr.ac.khu.graffiti.admin;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Reads for the admin review screen (API.md §4). Admins may see the author's email (D-22). */
@Repository
class AdminDoodleQuery {

    /** Which doodles a review list shows: HELD and REJECTED by status, UNCHECKED among the published ones. */
    enum Filter {
        HELD("d.status = 'HELD'", "ASC"),
        REJECTED("d.status = 'REJECTED'", "DESC"),
        UNCHECKED("d.status = 'PUBLISHED' AND d.moderation_state = 'UNCHECKED'", "DESC");

        // Fixed SQL fragments chosen by the enum, never from the request (SEC-01).
        private final String where;
        private final String order;

        Filter(String where, String order) {
            this.where = where;
            this.order = order;
        }
    }

    record Row(long id, long pageId, boolean pageEnded, String kind, int x, int y, int w, int h, String payload,
            String status, String moderationState, String moderationDetail, Instant submittedAt, long authorId,
            String nickname, String email) {
    }

    record Stored(long id, long pageId, long authorId, String kind, String status, int x, int y, int w, int h,
            String payload, List<Integer> chunks) {
    }

    record Action(long id, String actorType, Long actorId, String action, Long targetDoodleId, Long targetUserId,
            String reason, String detail, Instant createdAt) {
    }

    private static final String LIST = """
            SELECT d.id, d.page_id, p.status = 'ENDED' AS page_ended, d.kind, d.bbox_x, d.bbox_y, d.bbox_w,
                   d.bbox_h, d.payload, d.status, d.moderation_state, d.moderation_detail, d.submitted_at,
                   u.id AS author_id, u.nickname, u.email
            FROM doodle d JOIN users u ON u.id = d.author_id JOIN page p ON p.id = d.page_id
            WHERE %s ORDER BY d.submitted_at %s, d.id %s LIMIT :limit OFFSET :offset""";

    private static final String COUNT = "SELECT COUNT(*) FROM doodle d WHERE %s";

    private static final String STORED = """
            SELECT id, page_id, author_id, kind, status, bbox_x, bbox_y, bbox_w, bbox_h, payload
            FROM doodle WHERE id = :id""";

    private static final String CHUNKS = """
            SELECT chunk_index FROM doodle_chunk WHERE doodle_id = :id ORDER BY chunk_index""";

    private static final String ACTIONS = """
            SELECT id, actor_type, actor_id, action, target_doodle_id, target_user_id, reason, detail, created_at
            FROM moderation_action ORDER BY id DESC LIMIT :limit OFFSET :offset""";

    private final NamedParameterJdbcTemplate jdbc;

    AdminDoodleQuery(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<Row> list(Filter filter, int page, int size) {
        String sql = LIST.formatted(filter.where, filter.order, filter.order);
        return jdbc.query(sql, window(page, size), (rs, n) -> new Row(rs.getLong("id"), rs.getLong("page_id"),
                rs.getBoolean("page_ended"), rs.getString("kind"), rs.getInt("bbox_x"), rs.getInt("bbox_y"),
                rs.getInt("bbox_w"), rs.getInt("bbox_h"), rs.getString("payload"), rs.getString("status"),
                rs.getString("moderation_state"), rs.getString("moderation_detail"),
                instant(rs.getObject("submitted_at", LocalDateTime.class)), rs.getLong("author_id"),
                rs.getString("nickname"), rs.getString("email")));
    }

    long count(Filter filter) {
        Long n = jdbc.queryForObject(COUNT.formatted(filter.where), Map.of(), Long.class);
        return n == null ? 0 : n;
    }

    Optional<Stored> stored(long id) {
        List<Integer> chunks = jdbc.queryForList(CHUNKS, Map.of("id", id), Integer.class);
        return jdbc.query(STORED, Map.of("id", id), (rs, n) -> new Stored(rs.getLong("id"), rs.getLong("page_id"),
                rs.getLong("author_id"), rs.getString("kind"), rs.getString("status"), rs.getInt("bbox_x"),
                rs.getInt("bbox_y"), rs.getInt("bbox_w"), rs.getInt("bbox_h"), rs.getString("payload"), chunks))
                .stream().findFirst();
    }

    List<Action> actions(int page, int size) {
        return jdbc.query(ACTIONS, window(page, size), (rs, n) -> new Action(rs.getLong("id"),
                rs.getString("actor_type"), rs.getObject("actor_id", Long.class), rs.getString("action"),
                rs.getObject("target_doodle_id", Long.class), rs.getObject("target_user_id", Long.class),
                rs.getString("reason"), rs.getString("detail"),
                instant(rs.getObject("created_at", LocalDateTime.class))));
    }

    long countActions() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM moderation_action", Map.of(), Long.class);
        return n == null ? 0 : n;
    }

    private static MapSqlParameterSource window(int page, int size) {
        return new MapSqlParameterSource("limit", size).addValue("offset", (long) page * size);
    }

    private static Instant instant(LocalDateTime time) {
        return time == null ? null : time.toInstant(ZoneOffset.UTC);
    }
}
