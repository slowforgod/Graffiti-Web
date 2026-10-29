package kr.ac.khu.graffiti.moderation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Renders a drawing payload (DB.md §3) to the PNG the image checks look at (ARCHITECTURE.md §4.7): white
 * background, the strokes' bbox (widened by half the stroke width, as {@code DoodleValidator} does), round caps
 * and joins and a dot for a one-point stroke as the canvas draws them, shrunk so the longest side is at most
 * {@code maxSide}. The same payload always gives the same bytes. Pure: no Spring, no I/O beyond memory.
 */
public final class DoodleRenderer {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final List<Color> palette;
    private final int maxSide;

    public DoodleRenderer(List<String> palette, int maxSide) {
        if (maxSide < 1) {
            throw new IllegalArgumentException("maxSide must be positive");
        }
        this.palette = palette.stream().map(Color::decode).toList();
        this.maxSide = maxSide;
    }

    /** Throws {@link IllegalArgumentException} for a payload that is not a drawing with strokes. */
    public byte[] render(String payload) {
        JsonNode strokes = strokes(payload);
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (JsonNode s : strokes) {
            double half = width(s) / 2.0;
            JsonNode p = points(s);
            for (int i = 0; i < p.size(); i += 2) {
                minX = Math.min(minX, Math.floor(p.get(i).asDouble() - half));
                minY = Math.min(minY, Math.floor(p.get(i + 1).asDouble() - half));
                maxX = Math.max(maxX, Math.ceil(p.get(i).asDouble() + half));
                maxY = Math.max(maxY, Math.ceil(p.get(i + 1).asDouble() + half));
            }
        }
        double w = Math.max(1, maxX - minX);
        double h = Math.max(1, maxY - minY);
        double scale = Math.min(1.0, maxSide / Math.max(w, h));
        int width = Math.max(1, (int) Math.round(w * scale));
        int height = Math.max(1, (int) Math.round(h * scale));

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.scale(scale, scale);
            g.translate(-minX, -minY);
            for (JsonNode s : strokes) {
                draw(g, s);
            }
        } finally {
            g.dispose();
        }
        return png(image);
    }

    private void draw(Graphics2D g, JsonNode s) {
        g.setColor(color(s));
        int w = width(s);
        JsonNode p = points(s);
        if (p.size() == 2) {
            double x = p.get(0).asDouble();
            double y = p.get(1).asDouble();
            g.fill(new Ellipse2D.Double(x - w / 2.0, y - w / 2.0, w, w));
            return;
        }
        Path2D.Double path = new Path2D.Double();
        path.moveTo(p.get(0).asDouble(), p.get(1).asDouble());
        for (int i = 2; i < p.size(); i += 2) {
            path.lineTo(p.get(i).asDouble(), p.get(i + 1).asDouble());
        }
        g.setStroke(new BasicStroke(w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(path);
    }

    private static JsonNode strokes(String payload) {
        JsonNode tree;
        try {
            tree = JSON.readTree(payload);
        } catch (JsonProcessingException e) {
            // No message: it would quote the payload.
            throw new IllegalArgumentException("payload is not JSON");
        }
        JsonNode strokes = tree == null ? null : tree.get("strokes");
        if (strokes == null || !strokes.isArray() || strokes.isEmpty()) {
            throw new IllegalArgumentException("payload has no strokes");
        }
        for (JsonNode s : strokes) {
            points(s);
            width(s);
        }
        return strokes;
    }

    private static JsonNode points(JsonNode s) {
        JsonNode p = s.get("p");
        if (p == null || !p.isArray() || p.size() < 2 || p.size() % 2 != 0) {
            throw new IllegalArgumentException("stroke points are not pairs");
        }
        for (JsonNode n : p) {
            if (!n.isIntegralNumber()) {
                throw new IllegalArgumentException("stroke point is not an integer");
            }
        }
        return p;
    }

    private static int width(JsonNode s) {
        JsonNode w = s.get("w");
        if (w == null || !w.isIntegralNumber() || w.asInt() < 1) {
            throw new IllegalArgumentException("stroke width is not positive");
        }
        return w.asInt();
    }

    private Color color(JsonNode s) {
        JsonNode c = s.get("c");
        if (c == null || !c.isIntegralNumber() || c.asInt() < 0 || c.asInt() >= palette.size()) {
            throw new IllegalArgumentException("stroke color is not in the palette");
        }
        return palette.get(c.asInt());
    }

    private static byte[] png(BufferedImage image) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }
}
