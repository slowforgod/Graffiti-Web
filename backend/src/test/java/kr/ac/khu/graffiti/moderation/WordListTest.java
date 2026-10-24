package kr.ac.khu.graffiti.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class WordListTest {

    // Sample entries for the mechanism only; the real lists come with FILTER.md.
    private final WordList words = WordList.parse("""
            # comment
            시발
            한남충 HATE   # trailing comment
            tlqkf
            """, "시발점\n");

    @Test
    void findsBannedWordsThroughSpacesPunctuationAndWidth() {
        assertThat(words.match("시 발")).isEqualTo("PROFANITY");
        assertThat(words.match("시.발 꺼져")).isEqualTo("PROFANITY");
        assertThat(words.match("ＴＬＱＫＦ")).isEqualTo("PROFANITY");
        assertThat(words.match("한남충 꺼져")).isEqualTo("HATE");
    }

    @Test
    void allowedWordsExcuseOnlyTheirOwnLetters() {
        assertThat(words.match("시발점이 어디야?")).isNull();
        assertThat(words.match("시발점 시발")).isEqualTo("PROFANITY");
        assertThat(words.match("안녕하세요")).isNull();
    }

    @Test
    void theShippedListsAreEmptyUntilTheCriteriaAreSettled() {
        WordFilterCheck shipped = new WordFilterCheck();

        assertThat(shipped.check(new ModerationInput("TEXT", "아무 말", "{}")).outcome())
                .isEqualTo(CheckOutcome.SKIPPED);
    }

    @Test
    void aFilledListPassesOrBlocksTextAndSkipsDrawings() {
        WordFilterCheck check = new WordFilterCheck(words);

        assertThat(check.check(new ModerationInput("TEXT", "안녕", "{}"))).isEqualTo(CheckResult.pass("word-filter"));
        assertThat(check.check(new ModerationInput("TEXT", "ㅎㅇ 시발", "{}")))
                .isEqualTo(CheckResult.block("word-filter", "PROFANITY"));
        assertThat(check.check(new ModerationInput("DRAWING", null, "{}")).outcome()).isEqualTo(CheckOutcome.SKIPPED);
    }

    @Test
    void malformedLinesFailLoudly() {
        assertThatThrownBy(() -> WordList.parse("시발 profanity", "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WordList.parse("a B C", "")).isInstanceOf(IllegalArgumentException.class);
    }
}
