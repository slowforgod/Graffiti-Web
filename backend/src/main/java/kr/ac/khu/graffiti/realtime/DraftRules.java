package kr.ac.khu.graffiti.realtime;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Checks one {@code /app/draft} message (SEC-02, D-11) and finds the chunks it touches. Pure: the
 * caller passes the page's sizes and the configured limits. A refusal throws
 * {@link IllegalArgumentException} whose message is the offending field.
 */
public final class DraftRules {

    private static final Pattern UUID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    public enum Kind { DRAWING, TEXT }

    /** Sizes of the current page and the doodle limits from configuration. */
    public record Limits(int width, int height, int chunkSize, int paletteSize, int minStrokeWidth,
            int maxStrokeWidth, int maxBbox) {
    }

    /** A rectangle {@code [x, x + w) x [y, y + h)} in world pixels. */
    public record Rect(int x, int y, int w, int h) {
    }

    public sealed interface Op {
    }

    /** New points of one stroke; {@code area} is their bounding box widened by half the width. */
    public record Points(int strokeIndex, int color, int width, int[] points, Rect area) implements Op {
    }

    public record Erase(int strokeIndex) implements Op {
    }

    public record Text(Rect box) implements Op {
    }

    public record Cancel() implements Op {
    }

    private DraftRules() {
    }

    static Op check(DraftMessage m, Limits limits) {
        if (m.draftId() == null || !UUID.matcher(m.draftId()).matches()) {
            throw new IllegalArgumentException("draftId");
        }
        if (m.op() == null) {
            throw new IllegalArgumentException("op");
        }
        return switch (m.op()) {
            case "points" -> {
                only(m, true, true, true, true, false);
                yield points(m, limits);
            }
            case "erase" -> {
                only(m, true, false, false, false, false);
                yield new Erase(strokeIndex(m));
            }
            case "text" -> {
                only(m, false, false, false, false, true);
                yield new Text(box(m.bbox(), limits));
            }
            case "cancel" -> {
                only(m, false, false, false, false, false);
                yield new Cancel();
            }
            default -> throw new IllegalArgumentException("op");
        };
    }

    public static Kind kindOf(Op op) {
        return op instanceof Text ? Kind.TEXT : Kind.DRAWING;
    }

    /** Chunk indices ({@code row * cols + col}) the rectangle touches. */
    public static List<Integer> chunksOf(Rect r, Limits limits) {
        int cols = limits.width() / limits.chunkSize();
        int c0 = r.x() / limits.chunkSize();
        int r0 = r.y() / limits.chunkSize();
        int c1 = (r.x() + r.w() - 1) / limits.chunkSize();
        int r1 = (r.y() + r.h() - 1) / limits.chunkSize();
        List<Integer> chunks = new ArrayList<>();
        for (int row = r0; row <= r1; row++) {
            for (int col = c0; col <= c1; col++) {
                chunks.add(row * cols + col);
            }
        }
        return chunks;
    }

    private static Points points(DraftMessage m, Limits limits) {
        int stroke = strokeIndex(m);
        if (m.c() < 0 || m.c() >= limits.paletteSize()) {
            throw new IllegalArgumentException("c");
        }
        if (m.w() == null || m.w() < limits.minStrokeWidth() || m.w() > limits.maxStrokeWidth()) {
            throw new IllegalArgumentException("w");
        }
        int[] p = m.points();
        if (p.length < 2 || p.length % 2 != 0) {
            throw new IllegalArgumentException("points");
        }
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (int i = 0; i < p.length; i += 2) {
            int x = p[i];
            int y = p[i + 1];
            if (x < 0 || x >= limits.width() || y < 0 || y >= limits.height()) {
                throw new IllegalArgumentException("points");
            }
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
        }
        // Same widening as the occupancy footprint (D-14): half the stroke width on every side, clipped to the canvas.
        double half = m.w() / 2.0;
        int x0 = (int) Math.max(0, Math.floor(minX - half));
        int y0 = (int) Math.max(0, Math.floor(minY - half));
        int x1 = (int) Math.min(limits.width(), Math.ceil(maxX + half));
        int y1 = (int) Math.min(limits.height(), Math.ceil(maxY + half));
        if (x1 - x0 > limits.maxBbox() || y1 - y0 > limits.maxBbox()) {
            throw new IllegalArgumentException("points");
        }
        return new Points(stroke, m.c(), m.w(), p.clone(), new Rect(x0, y0, x1 - x0, y1 - y0));
    }

    private static Rect box(DraftMessage.Box b, Limits limits) {
        if (b.x() == null || b.y() == null || b.w() == null || b.h() == null
                || b.x() < 0 || b.y() < 0 || b.w() < 1 || b.h() < 1
                || b.w() > limits.maxBbox() || b.h() > limits.maxBbox()
                || (long) b.x() + b.w() > limits.width() || (long) b.y() + b.h() > limits.height()) {
            throw new IllegalArgumentException("bbox");
        }
        return new Rect(b.x(), b.y(), b.w(), b.h());
    }

    private static int strokeIndex(DraftMessage m) {
        if (m.strokeIndex() < 0) {
            throw new IllegalArgumentException("strokeIndex");
        }
        return m.strokeIndex();
    }

    // Each op carries exactly its own fields: a missing one or a foreign one is refused by name.
    private static void only(DraftMessage m, boolean stroke, boolean c, boolean w, boolean points, boolean bbox) {
        require("strokeIndex", m.strokeIndex() != null, stroke);
        require("c", m.c() != null, c);
        require("w", m.w() != null, w);
        require("points", m.points() != null, points);
        require("bbox", m.bbox() != null, bbox);
    }

    private static void require(String field, boolean present, boolean wanted) {
        if (present != wanted) {
            throw new IllegalArgumentException(field);
        }
    }
}
