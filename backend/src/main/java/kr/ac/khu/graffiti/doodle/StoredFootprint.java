package kr.ac.khu.graffiti.doodle;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import kr.ac.khu.graffiti.page.CellGeometry;
import kr.ac.khu.graffiti.page.Footprint;

/** The cells of a stored doodle, from its row (D-14, INV-12): a text's bbox, a drawing's strokes. */
public final class StoredFootprint {

    private StoredFootprint() {
    }

    public static Footprint of(String kind, String payload, int x, int y, int w, int h, CellGeometry geometry,
            ObjectMapper json) {
        return "TEXT".equals(kind)
                ? Footprint.ofBox(x, y, w, h, geometry)
                : Footprint.ofStrokes(strokes(payload, json), geometry);
    }

    private static List<Footprint.Stroke> strokes(String payload, ObjectMapper json) {
        try {
            return json.readValue(payload, DoodleValidator.DrawingPayload.class).strokes().stream()
                    .map(s -> new Footprint.Stroke(s.w(), s.p()))
                    .toList();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored drawing payload is not readable", e);
        }
    }
}
