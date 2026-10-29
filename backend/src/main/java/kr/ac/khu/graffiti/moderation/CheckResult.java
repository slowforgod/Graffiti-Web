package kr.ac.khu.graffiti.moderation;

/**
 * One check's answer. {@code category} is a FILTER.md category name for BLOCK and REVIEW, and {@code note}
 * is a short machine-readable remark such as an error kind. Neither may hold the user's text (SEC-05).
 */
public record CheckResult(String check, CheckOutcome outcome, String category, String note) {

    /** Profanity is shrunk or reviewed, not rejected (D-43). */
    public static final String PROFANITY = "PROFANITY";

    /** A BLOCK that rejects: any category but {@link #PROFANITY} (FILTER.md §1 categories 2-4). */
    public boolean rejects() {
        return outcome == CheckOutcome.BLOCK && !PROFANITY.equals(category);
    }

    /** A BLOCK for profanity: the text is shrunk, and the checks go on (D-43). */
    public boolean profanity() {
        return outcome == CheckOutcome.BLOCK && PROFANITY.equals(category);
    }

    public static CheckResult pass(String check) {
        return new CheckResult(check, CheckOutcome.PASS, null, null);
    }

    public static CheckResult block(String check, String category) {
        return new CheckResult(check, CheckOutcome.BLOCK, category, null);
    }

    public static CheckResult review(String check, String category) {
        return new CheckResult(check, CheckOutcome.REVIEW, category, null);
    }

    public static CheckResult error(String check, String note) {
        return new CheckResult(check, CheckOutcome.ERROR, null, note);
    }

    public static CheckResult skipped(String check) {
        return new CheckResult(check, CheckOutcome.SKIPPED, null, null);
    }
}
