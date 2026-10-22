package kr.ac.khu.graffiti.doodle;

import java.util.ArrayList;
import java.util.List;
import kr.ac.khu.graffiti.page.CellGeometry;
import kr.ac.khu.graffiti.page.Footprint;

/**
 * Checks a doodle sent with ✓ (API.md §3, SEC-02) and derives what the server stores: the bbox, the
 * occupied cells and the chunks. Pure: the caller passes the page's geometry and the configured limits.
 * The payload to store is the checked input itself, so unknown fields never reach the database.
 */
public final class DoodleValidator {

    /** The only payload version so far (DB.md §3). */
    public static final int VERSION = 1;

    // D-31: a text line is at most this many times its font size high (in tenths).
    private static final int LINE_HEIGHT_TENTHS = 14;

    /** The page's sizes and the doodle limits from configuration. */
    public record Limits(CellGeometry geometry, int chunkSize, int paletteSize, List<Integer> strokeWidths,
            List<Integer> fontSizes, int maxBbox, int textMaxLength) {
    }

    /** {@code payload} of a drawing (DB.md §3). */
    public record DrawingPayload(Integer v, List<StrokeInput> strokes) {
    }

    public record StrokeInput(Integer c, Integer w, int[] p) {
    }

    /** {@code payload} of a text; {@code (x, y)} is the top-left corner. */
    public record TextPayload(Integer v, String text, Integer c, Integer size, Integer x, Integer y) {
    }

    /** The box size the client measured for a text (D-31). Drawings send none. */
    public record Size(Integer w, Integer h) {
    }

    /** A rectangle {@code [x, x + w) x [y, y + h)} in world pixels. */
    public record Box(int x, int y, int w, int h) {
    }

    /** A checked doodle. {@code bbox} lies inside the canvas; {@code payload} is the record to store. */
    public record Validated(String kind, Box bbox, Object payload, Footprint footprint, List<Integer> chunks) {
    }

    /** A refusal: {@code field} is the offending field, {@code reason} the failed rule. */
    public static final class InvalidDoodleException extends RuntimeException {

        private final String field;
        private final String reason;

        InvalidDoodleException(String field, String reason) {
            super(field + ": " + reason);
            this.field = field;
            this.reason = reason;
        }

        public String field() {
            return field;
        }

        public String reason() {
            return reason;
        }
    }

    private DoodleValidator() {
    }

    public static Validated drawing(DrawingPayload payload, Size bbox, Limits limits) {
        if (bbox != null) {
            throw invalid("bbox", "not_allowed");
        }
        version(payload.v());
        List<StrokeInput> strokes = payload.strokes();
        if (strokes == null || strokes.isEmpty()) {
            throw invalid("strokes", "required");
        }
        CellGeometry g = limits.geometry();
        long minX = Long.MAX_VALUE;
        long minY = Long.MAX_VALUE;
        long maxX = Long.MIN_VALUE;
        long maxY = Long.MIN_VALUE;
        List<Footprint.Stroke> footprint = new ArrayList<>(strokes.size());
        for (StrokeInput s : strokes) {
            if (s == null) {
                throw invalid("strokes", "required");
            }
            color(s.c(), limits);
            if (s.w() == null || !limits.strokeWidths().contains(s.w())) {
                throw invalid("w", "not_allowed");
            }
            int[] p = s.p();
            if (p == null || p.length < 2 || p.length % 2 != 0) {
                throw invalid("p", "pairs");
            }
            double half = s.w() / 2.0;
            for (int i = 0; i < p.length; i += 2) {
                if (!inCanvas(p[i], p[i + 1], g)) {
                    throw invalid("p", "out_of_canvas");
                }
                // Same widening as the client's draft bounds (05), so a draft it accepted passes here.
                minX = Math.min(minX, (long) Math.floor(p[i] - half));
                minY = Math.min(minY, (long) Math.floor(p[i + 1] - half));
                maxX = Math.max(maxX, (long) Math.ceil(p[i] + half));
                maxY = Math.max(maxY, (long) Math.ceil(p[i + 1] + half));
            }
            footprint.add(new Footprint.Stroke(s.w(), p));
        }
        if (maxX - minX > limits.maxBbox() || maxY - minY > limits.maxBbox()) {
            throw invalid("strokes", "too_big");
        }
        int x0 = (int) Math.max(minX, 0);
        int y0 = (int) Math.max(minY, 0);
        Box box = new Box(x0, y0, (int) Math.min(maxX, g.width()) - x0, (int) Math.min(maxY, g.height()) - y0);
        return new Validated("DRAWING", box, new DrawingPayload(VERSION, strokes),
                Footprint.ofStrokes(footprint, g), chunks(box, limits));
    }

    public static Validated text(TextPayload payload, Size bbox, Limits limits) {
        version(payload.v());
        String text = payload.text();
        if (text == null) {
            throw invalid("text", "required");
        }
        int length = 0;
        int lines = 1;
        int line = 0;
        int longest = 0;
        boolean blank = true;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == '\n') {
                lines++;
                line = 0;
                continue;
            }
            if (Character.isISOControl(cp) || Character.getType(cp) == Character.SURROGATE) {
                throw invalid("text", "control_character");
            }
            blank &= Character.isWhitespace(cp) || Character.isSpaceChar(cp);
            length++;
            line++;
            longest = Math.max(longest, line);
        }
        if (length == 0 || blank) {
            throw invalid("text", "blank");
        }
        if (length > limits.textMaxLength()) {
            throw invalid("text", "too_long");
        }
        color(payload.c(), limits);
        Integer size = payload.size();
        if (size == null || !limits.fontSizes().contains(size)) {
            throw invalid("size", "not_allowed");
        }
        CellGeometry g = limits.geometry();
        if (payload.x() == null || payload.y() == null || !inCanvas(payload.x(), payload.y(), g)) {
            throw invalid("x", "out_of_canvas");
        }
        if (bbox == null || bbox.w() == null || bbox.h() == null) {
            throw invalid("bbox", "required");
        }
        int x = payload.x();
        int y = payload.y();
        int w = bbox.w();
        int h = bbox.h();
        if (w < 1 || h < 1 || w > limits.maxBbox() || h > limits.maxBbox()
                || (long) x + w > g.width() || (long) y + h > g.height()) {
            throw invalid("bbox", "out_of_range");
        }
        // D-31 upper bounds, and the lines themselves must fit the bbox limit (06 decision 4).
        if ((long) w > (long) size * longest || (long) h * 10 > (long) size * LINE_HEIGHT_TENTHS * lines
                || (long) size * lines > limits.maxBbox()) {
            throw invalid("bbox", "too_big");
        }
        Box box = new Box(x, y, w, h);
        return new Validated("TEXT", box, new TextPayload(VERSION, text, payload.c(), size, x, y),
                Footprint.ofBox(x, y, w, h, g), chunks(box, limits));
    }

    /** Chunk indices ({@code row * cols + col}) the box touches (D-24). */
    static List<Integer> chunks(Box box, Limits limits) {
        int size = limits.chunkSize();
        int cols = limits.geometry().width() / size;
        List<Integer> chunks = new ArrayList<>();
        for (int row = box.y() / size; row <= (box.y() + box.h() - 1) / size; row++) {
            for (int col = box.x() / size; col <= (box.x() + box.w() - 1) / size; col++) {
                chunks.add(row * cols + col);
            }
        }
        return chunks;
    }

    private static void version(Integer v) {
        if (v == null || v != VERSION) {
            throw invalid("v", "unsupported");
        }
    }

    private static void color(Integer c, Limits limits) {
        if (c == null || c < 0 || c >= limits.paletteSize()) {
            throw invalid("c", "not_in_palette");
        }
    }

    private static boolean inCanvas(int x, int y, CellGeometry g) {
        return x >= 0 && y >= 0 && x < g.width() && y < g.height();
    }

    private static InvalidDoodleException invalid(String field, String reason) {
        return new InvalidDoodleException(field, reason);
    }
}
