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

    private static final Limits LIMITS = new Limits(new CellGeometry(4096, 4096, 16), 512, 12, 2, 16,
            List.of(10, 12, 24, 96), 1024, 50, List.of("pretendard"), 125);

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
        // Width size x 1.25 x longest line, height size x 1.4 x lines: the largest box 96pt lines may have.
        Validated widest = text("가나다\n라", 96, 0, 0, 360, 268);

        TextShrinker.Shrunk s = TextShrinker.shrink(widest, 10, LIMITS);

        // ceil(360 * 10 / 96) = 38 is over 37.5, which the rounded-up bound lets through.
        assertThat(s.doodle().bbox()).isEqualTo(new DoodleValidator.Box(0, 0, 38, 28));
    }

    @Test
    void theFontStays() {
        Validated big = DoodleValidator.text(new TextPayload(1, "시발", 0, 24, 0, 0, "pretendard"), new Size(48, 24),
                LIMITS);

        assertThat(((TextPayload) TextShrinker.shrink(big, 10, LIMITS).doodle().payload()).f()).isEqualTo("pretendard");
    }

    @Test
    void aTextAlreadyAtTheSizeIsLeftAsItIs() {
        assertThat(TextShrinker.shrink(text("시발", 10, 0, 0, 20, 14), 10, LIMITS)).isNull();
    }

    private static Validated text(String text, int size, int x, int y, int w, int h) {
        return DoodleValidator.text(new TextPayload(1, text, 0, size, x, y, null), new Size(w, h), LIMITS);
    }
}
