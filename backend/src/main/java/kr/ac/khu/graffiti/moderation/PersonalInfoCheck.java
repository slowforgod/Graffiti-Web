package kr.ac.khu.graffiti.moderation;

import java.text.Normalizer;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * The format check for personal information (FILTER.md §1 category 4, specs/07-moderation.md §5.2): phone
 * numbers, e-mail addresses, student numbers and resident registration numbers block as PERSONAL_INFO, with the
 * kind as the note. Spaces, hyphens and dots between digits are dropped first, so "010 1234 5678" is a phone
 * number too. Numbers written in Hangul, addresses and @-handles are not looked for here (ChatKHU's, or
 * allowed). Pure; the matched text is never kept.
 */
@Component
class PersonalInfoCheck implements ModerationCheck {

    static final String NAME = "personal-info";
    static final String CATEGORY = "PERSONAL_INFO";

    private record Kind(String note, Pattern pattern) {
    }

    private static final Pattern DIGIT_SEPARATORS = Pattern.compile("(?<=\\d)[\\s\\-.·]+(?=\\d)");
    private static final Pattern EMAIL = Pattern.compile(
            "[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}");
    private static final List<Kind> NUMBERS = List.of(
            new Kind("PHONE", digits("01[016789]\\d{7,8}")),
            new Kind("PHONE", digits("0(2|[3-6][1-5])\\d{7,8}")),
            new Kind("STUDENT_ID", digits("20\\d{8}")),
            new Kind("RRN", digits("\\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])[1-8]\\d{6}")));

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public CheckResult check(ModerationInput input) {
        if (!input.isText()) {
            return CheckResult.skipped(NAME);
        }
        String text = Normalizer.normalize(input.text(), Normalizer.Form.NFKC);
        if (EMAIL.matcher(text).find()) {
            return found("EMAIL");
        }
        String joined = DIGIT_SEPARATORS.matcher(text).replaceAll("");
        for (Kind kind : NUMBERS) {
            if (kind.pattern().matcher(joined).find()) {
                return found(kind.note());
            }
        }
        return CheckResult.pass(NAME);
    }

    private static CheckResult found(String note) {
        return new CheckResult(NAME, CheckOutcome.BLOCK, CATEGORY, note);
    }

    // A number counts only as a whole run of digits, so a longer number never hides a shorter one.
    private static Pattern digits(String body) {
        return Pattern.compile("(?<!\\d)" + body + "(?!\\d)");
    }
}
