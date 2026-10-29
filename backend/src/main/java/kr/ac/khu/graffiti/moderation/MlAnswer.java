package kr.ac.khu.graffiti.moderation;

import java.util.Map;

/**
 * The ML server's answer for one drawing (API.md §7), or why there is none. Every field but {@code outcome}
 * is null unless {@code outcome} is OK. {@code ocrText} is the gated handwriting the server read: it goes to the
 * text checks and {@code ml_prediction.ocr_text} only, never to a log (SEC-17).
 */
public record MlAnswer(Outcome outcome, String modelVersion, Label label, Map<String, Double> scores,
        String ocrText, Double ocrConfidence, Integer latencyMs) {

    /** {@code ml_prediction.outcome}. */
    public enum Outcome { OK, TIMEOUT, ERROR }

    /** {@code ml_prediction.image_label}. */
    public enum Label { SAFE, FLAGGED }

    public static MlAnswer failed(Outcome outcome) {
        if (outcome == Outcome.OK) {
            throw new IllegalArgumentException("a failure is not OK");
        }
        return new MlAnswer(outcome, null, null, null, null, null, null);
    }

    public boolean ok() {
        return outcome == Outcome.OK;
    }

    /** The highest-scoring category, used as the rejection category when the image is FLAGGED. */
    public String topCategory() {
        return scores == null ? null : scores.entrySet().stream()
                .max(Map.Entry.<String, Double>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey).orElse(null);
    }
}
