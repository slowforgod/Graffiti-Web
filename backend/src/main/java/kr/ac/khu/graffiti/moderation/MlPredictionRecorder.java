package kr.ac.khu.graffiti.moderation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import kr.ac.khu.graffiti.config.GraffitiProperties.MlMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Writes one {@code ml_prediction} row per analysis (DB.md §2), outside the publish transaction: a failure is
 * logged with the doodle id only and never changes the published result (ARCHITECTURE.md §4.7).
 */
@Repository
public class MlPredictionRecorder {

    private static final Logger log = LoggerFactory.getLogger(MlPredictionRecorder.class);

    /** {@code ml_prediction.ocr_text} holds this many characters; the checks saw the whole text. */
    static final int OCR_TEXT_MAX = 500;

    private static final String INSERT = """
            INSERT INTO ml_prediction (doodle_id, model_version, mode, outcome, image_label, image_scores, ocr_text,
                                       ocr_text_outcome, ocr_text_category, ocr_confidence, latency_ms, created_at)
            VALUES (:doodleId, :modelVersion, :mode, :outcome, :label, :scores, :ocrText,
                    :ocrOutcome, :ocrCategory, :ocrConfidence, :latencyMs, :at)""";

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;

    MlPredictionRecorder(NamedParameterJdbcTemplate jdbc, ObjectMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    /** Never throws. */
    public void record(long doodleId, MlMode mode, MlAnalysis.Result result) {
        try {
            MlAnswer a = result.answer();
            jdbc.update(INSERT, new MapSqlParameterSource()
                    .addValue("doodleId", doodleId)
                    .addValue("modelVersion", a.modelVersion())
                    .addValue("mode", mode.name())
                    .addValue("outcome", a.outcome().name())
                    .addValue("label", a.label() == null ? null : a.label().name())
                    .addValue("scores", a.scores() == null ? null : json.writeValueAsString(a.scores()))
                    .addValue("ocrText", a.ocrText() == null || a.ocrText().isEmpty() ? null : cut(a.ocrText()))
                    .addValue("ocrOutcome", result.ocrOutcome() == null ? null : result.ocrOutcome().name())
                    .addValue("ocrCategory", result.ocrCategory())
                    .addValue("ocrConfidence", a.ocrConfidence() == null ? null
                            : BigDecimal.valueOf(a.ocrConfidence()).setScale(3, RoundingMode.HALF_UP))
                    .addValue("latencyMs", a.latencyMs())
                    .addValue("at", LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)));
        } catch (JsonProcessingException | RuntimeException e) {
            // The exception message could quote the OCR text (SEC-17): its kind only.
            log.warn("Could not record the ML analysis of doodle {}: {}", doodleId, e.getClass().getSimpleName());
        }
    }

    // Cuts at a code point boundary so a surrogate pair is never split.
    private static String cut(String text) {
        if (text.codePointCount(0, text.length()) <= OCR_TEXT_MAX) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, OCR_TEXT_MAX));
    }
}
