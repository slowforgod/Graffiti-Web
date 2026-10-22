package kr.ac.khu.graffiti.doodle;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Published doodles touching the given chunks (DB.md §4, D-24). Author columns are never selected
 * (D-22, SEC-13). Runs on the request thread, never inside the page queue (INV-02).
 */
@Repository
public class ChunkDoodleQuery {

    private static final String SQL = """
            SELECT DISTINCT d.id, d.kind, d.bbox_x, d.bbox_y, d.bbox_w, d.bbox_h, d.payload
            FROM doodle_chunk dc JOIN doodle d ON d.id = dc.doodle_id
            WHERE dc.page_id = :pageId AND dc.chunk_index IN (:chunks) AND d.status = 'PUBLISHED'
            ORDER BY d.id""";

    /** {@code payload} is the stored JSON text, passed through as-is. */
    public record Row(long id, String kind, int x, int y, int w, int h, String payload) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    ChunkDoodleQuery(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Row> find(long pageId, List<Integer> chunks) {
        return jdbc.query(SQL, Map.of("pageId", pageId, "chunks", chunks), (rs, n) -> new Row(
                rs.getLong("id"), rs.getString("kind"), rs.getInt("bbox_x"), rs.getInt("bbox_y"),
                rs.getInt("bbox_w"), rs.getInt("bbox_h"), rs.getString("payload")));
    }
}
