package kr.ac.khu.graffiti.doodle;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import kr.ac.khu.graffiti.doodle.DoodleValidator.Limits;
import kr.ac.khu.graffiti.doodle.DoodleValidator.Size;
import kr.ac.khu.graffiti.doodle.DoodleValidator.TextPayload;
import kr.ac.khu.graffiti.doodle.DoodleValidator.Validated;
import kr.ac.khu.graffiti.page.CellGeometry;
import org.junit.jupiter.api.Test;

class TextShrinkerTest {

    private static final Limits LIMITS = new Limits(new CellGeometry(4096, 4096, 16), 512, 12, List.of(2, 4, 8, 16),
            List.of(10, 12, 24, 96), 1024, 50);

    @Test
    void scalesTheSizeAndBboxAndKeepsTheCorner() {
        Validated big = text("시발 시험", 24, 1500, 900, 115, 33);

        TextShrinker.Shrunk s = TextShrinker.shrink(big, 10, LIMITS);

        assertThat(s.fromSize()).isEqualTo(24);
        assertThat(((TextPayload) s.doodle().payload()).size()).isEqualTo(10);
        // ceil(115 * 10 / 24) = 48, ceil(33 * 10 / 24) = 14
        assertThat(s.doodle().bbox()).isEqualTo(new DoodleValidator.Box(1500, 900, 48, 14));
        assertThat(s.doodle().chunks()).isEqualTo(DoodleValidator.chunks(s.doodle().bbox(), LIMITS));
        assertThat(s.doodle().footprint()).isNotEqualTo(big.footprint());
    }

    @Test
    void aBoxAtTheD31BoundsStaysValid() {
        // Width size x longest line, height size x 1.4 x lines: the largest box a 96pt pair of lines may have.
        Validated widest = text("가나다\n라", 96, 0, 0, 288, 268);

        TextShrinker.Shrunk s = TextShrinker.shrink(widest, 10, LIMITS);

        assertThat(s.doodle().bbox()).isEqualTo(new DoodleValidator.Box(0, 0, 30, 28));
    }

    @Test
    void aTextAlreadyAtTheSizeIsLeftAsItIs() {
        assertThat(TextShrinker.shrink(text("시발", 10, 0, 0, 20, 14), 10, LIMITS)).isNull();
    }

    private static Validated text(String text, int size, int x, int y, int w, int h) {
        return DoodleValidator.text(new TextPayload(1, text, 0, size, x, y), new Size(w, h), LIMITS);
    }
}
