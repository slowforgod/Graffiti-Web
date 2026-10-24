package kr.ac.khu.graffiti.doodle;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Reads for the author's own doodles (D-13). Runs on the request thread, never inside the page queue (INV-02). */
@Repository
class MyDoodleQuery {

    /** One of the author's published doodles: enough to find it on the canvas. */
    record Mine(long id, int x, int y, int w, int h) {
    }

    /** A doodle as stored, for a deletion: who wrote it, where it is, and what it covers. */
    record Stored(long id, long pageId, long authorId, String kind, String status, int x, int y, int w, int h,
            String payload) {
    }

    private static final String MINE = """
            SELECT id, bbox_x, bbox_y, bbox_w, bbox_h FROM doodle
            WHERE author_id = :authorId AND page_id = :pageId AND status = 'PUBLISHED'
            ORDER BY id""";

    private static final String STORED = """
            SELECT id, page_id, author_id, kind, status, bbox_x, bbox_y, bbox_w, bbox_h, payload
            FROM doodle WHERE id IN (:ids) ORDER BY id""";

    private static final String CHUNKS = """
            SELECT doodle_id, chunk_index FROM doodle_chunk WHERE doodle_id IN (:ids)
            ORDER BY doodle_id, chunk_index""";

    private final NamedParameterJdbcTemplate jdbc;

    MyDoodleQuery(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<Mine> mine(long authorId, long pageId) {
        return jdbc.query(MINE, new MapSqlParameterSource("authorId", authorId).addValue("pageId", pageId),
                (rs, n) -> new Mine(rs.getLong("id"), rs.getInt("bbox_x"), rs.getInt("bbox_y"), rs.getInt("bbox_w"),
                        rs.getInt("bbox_h")));
    }

    List<Stored> stored(Collection<Long> ids) {
        return jdbc.query(STORED, Map.of("ids", ids), (rs, n) -> new Stored(rs.getLong("id"), rs.getLong("page_id"),
                rs.getLong("author_id"), rs.getString("kind"), rs.getString("status"), rs.getInt("bbox_x"),
                rs.getInt("bbox_y"), rs.getInt("bbox_w"), rs.getInt("bbox_h"), rs.getString("payload")));
    }

    /** The chunk rows of each doodle, in chunk order. */
    Map<Long, List<Integer>> chunks(Collection<Long> ids) {
        Map<Long, List<Integer>> byDoodle = new HashMap<>();
        jdbc.query(CHUNKS, Map.of("ids", ids), rs -> {
            byDoodle.computeIfAbsent(rs.getLong("doodle_id"), id -> new ArrayList<>())
                    .add(rs.getInt("chunk_index"));
        });
        return byDoodle;
    }
}
