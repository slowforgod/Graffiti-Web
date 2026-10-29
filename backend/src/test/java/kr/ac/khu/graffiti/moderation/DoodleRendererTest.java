package kr.ac.khu.graffiti.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class DoodleRendererTest {

    private static final int WHITE = 0xFFFFFF;
    private static final List<String> PALETTE = List.of("#000000", "#FF0000");

    private final DoodleRenderer renderer = new DoodleRenderer(PALETTE, 1024);

    @Test
    void drawsTheStrokesOnWhiteInTheirWidenedBbox() throws IOException {
        // A horizontal red line from (100, 50) to (200, 50), 10 wide: bbox 95..205 x 45..55.
        BufferedImage image = read(renderer.render("{\"v\":1,\"strokes\":[{\"c\":1,\"w\":10,\"p\":[100,50,200,50]}]}"));

        assertThat(image.getWidth()).isEqualTo(110);
        assertThat(image.getHeight()).isEqualTo(10);
        assertThat(image.getColorModel().hasAlpha()).isFalse();
        assertThat(rgb(image, 55, 5)).isEqualTo(0xFF0000);
        assertThat(rgb(image, 0, 0)).isEqualTo(WHITE);
    }

    @Test
    void aOnePointStrokeIsADot() throws IOException {
        BufferedImage image = read(renderer.render("{\"v\":1,\"strokes\":[{\"c\":0,\"w\":20,\"p\":[300,300]}]}"));

        assertThat(image.getWidth()).isEqualTo(20);
        assertThat(rgb(image, 10, 10)).isEqualTo(0x000000);
        assertThat(rgb(image, 0, 0)).isEqualTo(WHITE);
    }

    @Test
    void shrinksSoTheLongestSideFits() throws IOException {
        DoodleRenderer small = new DoodleRenderer(PALETTE, 100);

        BufferedImage image = read(small.render("{\"v\":1,\"strokes\":[{\"c\":0,\"w\":4,\"p\":[0,0,396,196]}]}"));

        assertThat(image.getWidth()).isEqualTo(100);
        assertThat(image.getHeight()).isEqualTo(50);
    }

    @Test
    void theSamePayloadGivesTheSameBytes() {
        String payload = "{\"v\":1,\"strokes\":[{\"c\":0,\"w\":8,\"p\":[10,10,40,70,90,20]},"
                + "{\"c\":1,\"w\":4,\"p\":[50,50]}]}";

        assertThat(renderer.render(payload)).isEqualTo(renderer.render(payload));
    }

    @Test
    void refusesWhatIsNotADrawingWithoutQuotingIt() {
        for (String payload : List.of("비밀", "{\"v\":1,\"text\":\"비밀\"}", "{\"strokes\":[]}",
                "{\"strokes\":[{\"c\":0,\"w\":4,\"p\":[1,2,3]}]}", "{\"strokes\":[{\"c\":9,\"w\":4,\"p\":[1,2]}]}",
                "{\"strokes\":[{\"c\":0,\"w\":0,\"p\":[1,2]}]}")) {
            assertThatThrownBy(() -> renderer.render(payload)).isInstanceOf(IllegalArgumentException.class)
                    .satisfies(e -> assertThat(e.getMessage()).doesNotContain("비밀"));
        }
    }

    private static BufferedImage read(byte[] png) throws IOException {
        assertThat(png).startsWith(0x89, 'P', 'N', 'G');
        return ImageIO.read(new ByteArrayInputStream(png));
    }

    private static int rgb(BufferedImage image, int x, int y) {
        return image.getRGB(x, y) & 0xFFFFFF;
    }
}
