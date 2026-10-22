package kr.ac.khu.graffiti.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class DraftRulesTest {

    private static final DraftRules.Limits LIMITS =
            new DraftRules.Limits(7680, 4320, 480, 12, List.of(2, 4, 8, 16), 1024);
    private static final String ID = "3f2b8c1e-9a4d-4e6f-8b2a-1c3d5e7f9a0b";

    @Test
    void pointsWidenByHalfTheStrokeWidthAndClipToTheCanvas() {
        DraftRules.Points p = (DraftRules.Points) DraftRules.check(points(0, 3, 8, 10, 2, 20, 30), LIMITS);

        assertThat(p.area()).isEqualTo(new DraftRules.Rect(6, 0, 18, 34));
        assertThat(p.strokeIndex()).isZero();
        assertThat(p.color()).isEqualTo(3);
        assertThat(p.width()).isEqualTo(8);
    }

    @Test
    void aBatchMayNotBeWiderThanTheBboxLimitIncludingTheStrokeWidth() {
        assertThat(DraftRules.check(points(0, 0, 2, 100, 100, 1122, 100), LIMITS)).isInstanceOf(DraftRules.Points.class);

        assertThatThrownBy(() -> DraftRules.check(points(0, 0, 4, 100, 100, 1122, 100), LIMITS))
                .hasMessage("points");
    }

    @Test
    void pointsAreRefusedOutsideTheRules() {
        assertThatThrownBy(() -> DraftRules.check(points(0, 0, 4, 7680, 10), LIMITS)).hasMessage("points");
        assertThatThrownBy(() -> DraftRules.check(points(0, 0, 4, -1, 10), LIMITS)).hasMessage("points");
        assertThatThrownBy(() -> DraftRules.check(points(0, 0, 4, 10, 4320), LIMITS)).hasMessage("points");
        assertThatThrownBy(() -> DraftRules.check(points(0, 0, 4, 10, 10, 11), LIMITS)).hasMessage("points");
        assertThatThrownBy(() -> DraftRules.check(points(0, 0, 4), LIMITS)).hasMessage("points");
        assertThatThrownBy(() -> DraftRules.check(points(0, 12, 4, 10, 10), LIMITS)).hasMessage("c");
        assertThatThrownBy(() -> DraftRules.check(points(0, -1, 4, 10, 10), LIMITS)).hasMessage("c");
        assertThatThrownBy(() -> DraftRules.check(points(0, 0, 3, 10, 10), LIMITS)).hasMessage("w");
        assertThatThrownBy(() -> DraftRules.check(points(-1, 0, 4, 10, 10), LIMITS)).hasMessage("strokeIndex");
    }

    @Test
    void eachOpCarriesExactlyItsOwnFields() {
        assertThatThrownBy(() -> DraftRules.check(
                new DraftMessage("1", ID, "points", 0, 0, 4, null, null), LIMITS)).hasMessage("points");
        assertThatThrownBy(() -> DraftRules.check(
                new DraftMessage("1", ID, "erase", 0, 0, null, null, null), LIMITS)).hasMessage("c");
        assertThatThrownBy(() -> DraftRules.check(
                new DraftMessage("1", ID, "cancel", null, null, null, new int[] {1, 1}, null), LIMITS))
                .hasMessage("points");
        assertThatThrownBy(() -> DraftRules.check(
                new DraftMessage("1", ID, "text", null, null, null, null, null), LIMITS)).hasMessage("bbox");
        assertThatThrownBy(() -> DraftRules.check(
                new DraftMessage("1", ID, "start", null, null, null, null, null), LIMITS)).hasMessage("op");
        assertThatThrownBy(() -> DraftRules.check(
                new DraftMessage("1", ID, null, null, null, null, null, null), LIMITS)).hasMessage("op");

        assertThat(DraftRules.check(new DraftMessage("1", ID, "erase", 2, null, null, null, null), LIMITS))
                .isEqualTo(new DraftRules.Erase(2));
        assertThat(DraftRules.check(new DraftMessage("1", ID, "cancel", null, null, null, null, null), LIMITS))
                .isEqualTo(new DraftRules.Cancel());
    }

    @Test
    void theDraftIdMustBeAUuid() {
        for (String id : new String[] {null, "", "draft-1", ID + "0", ID.replace('-', '_')}) {
            assertThatThrownBy(() -> DraftRules.check(
                    new DraftMessage("1", id, "cancel", null, null, null, null, null), LIMITS)).hasMessage("draftId");
        }
    }

    @Test
    void textBoxesStayOnTheCanvasAndUnderTheLimit() {
        assertThat(DraftRules.check(text(7680 - 52, 4320 - 34, 52, 34), LIMITS))
                .isEqualTo(new DraftRules.Text(new DraftRules.Rect(7628, 4286, 52, 34)));

        for (DraftMessage bad : List.of(text(-1, 0, 10, 10), text(0, 0, 0, 10), text(0, 0, 1025, 10),
                text(0, 0, 10, 1025), text(7679, 0, 2, 10), text(0, 4311, 10, 10),
                new DraftMessage("1", ID, "text", null, null, null, null, new DraftMessage.Box(0, 0, null, 10)))) {
            assertThatThrownBy(() -> DraftRules.check(bad, LIMITS)).as("%s", bad.bbox()).hasMessage("bbox");
        }
    }

    @Test
    void kindFollowsTheOp() {
        assertThat(DraftRules.kindOf(new DraftRules.Text(new DraftRules.Rect(0, 0, 1, 1))))
                .isEqualTo(DraftRules.Kind.TEXT);
        assertThat(DraftRules.kindOf(new DraftRules.Erase(0))).isEqualTo(DraftRules.Kind.DRAWING);
    }

    @Test
    void chunksOfARectExcludeItsRightAndBottomEdges() {
        assertThat(DraftRules.chunksOf(new DraftRules.Rect(0, 0, 480, 480), LIMITS)).containsExactly(0);
        assertThat(DraftRules.chunksOf(new DraftRules.Rect(470, 470, 20, 20), LIMITS)).containsExactly(0, 1, 16, 17);
        assertThat(DraftRules.chunksOf(new DraftRules.Rect(7679, 4319, 1, 1), LIMITS)).containsExactly(143);
    }

    private static DraftMessage points(int stroke, int c, int w, int... points) {
        return new DraftMessage("1", ID, "points", stroke, c, w, points, null);
    }

    private static DraftMessage text(int x, int y, int w, int h) {
        return new DraftMessage("1", ID, "text", null, null, null, null, new DraftMessage.Box(x, y, w, h));
    }
}
