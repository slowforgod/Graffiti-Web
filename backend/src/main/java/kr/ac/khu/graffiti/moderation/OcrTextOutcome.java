package kr.ac.khu.graffiti.moderation;

import java.util.List;

/**
 * What the text checks said about the handwriting the ML server read ({@code ml_prediction.ocr_text_outcome}).
 * NONE: no text was read, so nothing was checked.
 */
public enum OcrTextOutcome {
    NONE, PASS, REVIEW, BLOCK, ERROR, SKIPPED;

    /** The first BLOCK, else any REVIEW, else any ERROR; PASS only when every check passed. */
    static OcrTextOutcome of(List<CheckResult> results) {
        if (results.stream().anyMatch(r -> r.outcome() == CheckOutcome.BLOCK)) {
            return BLOCK;
        }
        if (results.stream().anyMatch(r -> r.outcome() == CheckOutcome.REVIEW)) {
            return REVIEW;
        }
        if (results.stream().anyMatch(r -> r.outcome() == CheckOutcome.ERROR)) {
            return ERROR;
        }
        return !results.isEmpty() && results.stream().allMatch(r -> r.outcome() == CheckOutcome.PASS)
                ? PASS : SKIPPED;
    }
}
