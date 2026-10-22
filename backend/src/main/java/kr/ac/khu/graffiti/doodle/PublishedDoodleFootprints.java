package kr.ac.khu.graffiti.doodle;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import kr.ac.khu.graffiti.page.CellGeometry;
import kr.ac.khu.graffiti.page.Footprint;
import kr.ac.khu.graffiti.page.PublishedFootprintSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/** Footprints of a page's PUBLISHED doodles from their stored payload (DB.md §4, D-25, INV-12). */
@Component
class PublishedDoodleFootprints implements PublishedFootprintSource {

    private static final String SQL = """
            SELECT kind, payload, bbox_x, bbox_y, bbox_w, bbox_h FROM doodle
            WHERE page_id = :pageId AND status = 'PUBLISHED'""";

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json;

    PublishedDoodleFootprints(NamedParameterJdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public List<Footprint> load(long pageId, CellGeometry geometry) {
        return jdbc.query(SQL, Map.of("pageId", pageId), (rs, n) -> "TEXT".equals(rs.getString("kind"))
                ? Footprint.ofBox(rs.getInt("bbox_x"), rs.getInt("bbox_y"), rs.getInt("bbox_w"), rs.getInt("bbox_h"),
                        geometry)
                : Footprint.ofStrokes(strokes(rs.getString("payload")), geometry));
    }

    private List<Footprint.Stroke> strokes(String payload) {
        try {
            return json.readValue(payload, DoodleValidator.DrawingPayload.class).strokes().stream()
                    .map(s -> new Footprint.Stroke(s.w(), s.p()))
                    .toList();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored drawing payload is not readable", e);
        }
    }
}
