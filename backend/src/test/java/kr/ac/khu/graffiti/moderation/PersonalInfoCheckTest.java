package kr.ac.khu.graffiti.moderation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class PersonalInfoCheckTest {

    private final PersonalInfoCheck check = new PersonalInfoCheck();

    // FILTER.md §1 category 4 and specs/07-moderation.md §5.2.
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "010-1234-5678로 연락해 | PHONE",
        "010 1234 5678 | PHONE",
        "010.1234.5678 | PHONE",
        "연락 01012345678 | PHONE",
        "02-961-0114 | PHONE",
        "031 123 4567 | PHONE",
        "abc@khu.ac.kr | EMAIL",
        "메일 hong.gil-dong@gmail.com 으로 | EMAIL",
        "２０２３１２３４５６ 김OO | STUDENT_ID",
        "2023123456 김OO | STUDENT_ID",
        "990101-1234567 | RRN",
    })
    void blocksContactsAndIdentifiers(String text, String kind) {
        assertThat(check.check(text(text))).isEqualTo(new CheckResult("personal-info", CheckOutcome.BLOCK,
                "PERSONAL_INFO", kind));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "인스타 @hong_123 팔로우", "경영 23 화이팅", "중도 3층 최고", "010 화이팅", "02학번 모여라",
        "2024년 10월 29일", "공일공 일이삼사 오육칠팔", "OO동 OO아파트 101동", "20251029 시험 끝", "1234567890123",
    })
    void passesWhatIdentifiesNoOne(String text) {
        assertThat(check.check(text(text))).isEqualTo(CheckResult.pass("personal-info"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"01012345678901", "2023123456789"})
    void digitsRunningOnAreNotCut(String text) {
        assertThat(check.check(text(text)).outcome()).isEqualTo(CheckOutcome.PASS);
    }

    @org.junit.jupiter.api.Test
    void skipsDrawings() {
        assertThat(check.check(new ModerationInput("DRAWING", null, "{}")).outcome()).isEqualTo(CheckOutcome.SKIPPED);
    }

    private static ModerationInput text(String text) {
        return new ModerationInput("TEXT", text, "{}");
    }
}
