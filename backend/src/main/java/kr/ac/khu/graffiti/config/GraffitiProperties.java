package kr.ac.khu.graffiti.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Single source of every tunable number (INV-10). Keys and defaults: docs/ARCHITECTURE.md §6.
 * Invalid values fail application startup.
 */
@Validated
@ConfigurationProperties("graffiti")
public record GraffitiProperties(
        @NotNull @Valid Canvas canvas,
        @NotNull @Valid Page page,
        @NotNull @Valid Doodle doodle,
        @NotNull Duration postCooldown,
        @NotNull @Valid Realtime realtime,
        @NotNull @Valid Query query,
        @NotNull @Valid Moderation moderation,
        @NotNull @Valid Auth auth,
        @NotNull @Valid Web web) {

    public enum MlMode { OFF, SHADOW, ENFORCE }

    /** What happens to a doodle no real check looked at (a stub stood in): published as UNCHECKED, or held. */
    public enum UncheckedPolicy { PUBLISH, HOLD }

    @AssertTrue(message = "post-cooldown must be positive")
    public boolean isPostCooldownPositive() {
        return isPositive(postCooldown);
    }

    @AssertTrue(message = "moderation.shrink-font-size must be one of doodle.font-sizes")
    public boolean isShrinkFontSizeListed() {
        return doodle == null || moderation == null || doodle.fontSizes() == null
                || doodle.fontSizes().contains(moderation.shrinkFontSize());
    }

    public record Canvas(
            @Positive int width,
            @Positive int height,
            @Positive double zoomMin,
            @Positive double zoomMax,
            @Positive int chunkSize,
            @Positive int cellSize) {

        @AssertTrue(message = "zoom-min must not exceed zoom-max")
        public boolean isZoomRangeValid() {
            return zoomMin <= zoomMax;
        }

        // Chunks and cells must tile the canvas exactly (D-06).
        @AssertTrue(message = "chunk-size must be a multiple of cell-size")
        public boolean isChunkAlignedToCells() {
            return cellSize > 0 && chunkSize % cellSize == 0;
        }

        @AssertTrue(message = "width and height must be multiples of chunk-size")
        public boolean isCanvasAlignedToChunks() {
            return chunkSize > 0 && width % chunkSize == 0 && height % chunkSize == 0;
        }
    }

    public record Page(
            @Positive @DecimalMax("1.0") double countdownThreshold,
            @NotNull Duration countdownDuration,
            @NotNull Duration maxLifetime,
            @NotNull Duration countdownWarning) {

        @AssertTrue(message = "countdown-duration, max-lifetime and countdown-warning must be positive")
        public boolean isDurationsPositive() {
            return isPositive(countdownDuration) && isPositive(maxLifetime) && isPositive(countdownWarning);
        }
    }

    public record Doodle(
            @Positive int maxBbox,
            @Positive int maxPayloadBytes,
            @NotEmpty List<@NotNull @Pattern(regexp = "#[0-9A-Fa-f]{6}") String> palette,
            @NotEmpty List<@NotNull @Positive Integer> strokeWidths,
            @NotEmpty List<@NotNull @Positive Integer> fontSizes,
            @Positive int defaultFontSize,
            @Positive int textMaxLength,
            @NotNull Duration draftIdleTimeout) {

        @AssertTrue(message = "default-font-size must be one of font-sizes")
        public boolean isDefaultFontSizeListed() {
            return fontSizes != null && fontSizes.contains(defaultFontSize);
        }

        @AssertTrue(message = "draft-idle-timeout must be positive")
        public boolean isDraftIdleTimeoutPositive() {
            return isPositive(draftIdleTimeout);
        }
    }

    public record Realtime(
            @Positive int maxMessagesPerSecond,
            @Positive int maxSubscriptions,
            @Positive int inboundMessageMaxBytes,
            @NotNull Duration cursorInterval,
            @NotNull Duration draftInterval,
            @NotNull Duration heartbeat) {

        @AssertTrue(message = "cursor-interval, draft-interval and heartbeat must be positive")
        public boolean isIntervalsPositive() {
            return isPositive(cursorInterval) && isPositive(draftInterval) && isPositive(heartbeat);
        }
    }

    public record Query(
            @Positive int maxChunks,
            @Positive int maxBatchDelete,
            @Positive int adminPageSizeMax) {
    }

    /**
     * {@code textChecks} and {@code drawingChecks} name the checks per kind, in order (D-19); names without an
     * implementation run as stubs that check nothing.
     */
    public record Moderation(
            @NotNull Duration timeout,
            @NotNull UncheckedPolicy unchecked,
            @NotNull List<@NotBlank String> textChecks,
            @NotNull List<@NotBlank String> drawingChecks,
            @Positive int shrinkFontSize,
            @NotNull @Valid Api chatkhu,
            @NotNull @Valid Api openai,
            @NotNull @Valid Ml ml) {

        @AssertTrue(message = "moderation timeout must be positive")
        public boolean isTimeoutPositive() {
            return isPositive(timeout);
        }
    }

    /**
     * An external moderation API (specs/07-moderation.md §5.4-5.5). A blank {@code apiKey} leaves its checks
     * as stubs.
     */
    public record Api(
            @NotBlank String baseUrl,
            @NotBlank String model,
            @NotNull Duration timeout,
            @NotNull String apiKey) {

        @AssertTrue(message = "api timeout must be positive")
        public boolean isTimeoutPositive() {
            return isPositive(timeout);
        }
    }

    /**
     * The ML server (D-40). Its image label comes from the thresholds in {@code ml/models.lock}, not from here.
     * {@code maxInFlight} caps the SHADOW analyses running in the background; {@code renderMaxSide} is the
     * longest side of the PNG a drawing is rendered to.
     */
    public record Ml(
            @NotNull MlMode mode,
            @NotBlank String baseUrl,
            @NotNull Duration timeout,
            @Positive int maxInFlight,
            @Positive int renderMaxSide) {

        @AssertTrue(message = "ml timeout must be positive")
        public boolean isTimeoutPositive() {
            return isPositive(timeout);
        }
    }

    public record Auth(
            @NotBlank String allowedDomain,
            @NotNull List<String> adminEmails,
            @NotNull Duration sessionTimeout,
            @Positive int nicknameMaxAttempts) {

        @AssertTrue(message = "session-timeout must be positive")
        public boolean isSessionTimeoutPositive() {
            return isPositive(sessionTimeout);
        }
    }

    /**
     * {@code adminRequestsPerSecond} caps every {@code /api/admin/**} request of one admin, and
     * {@code writeRequestsPerSecond} every other state-changing {@code /api/**} request of one signed-in user
     * (SEC-08/09). nginx limits per IP in front of both.
     */
    public record Web(
            @NotEmpty List<@NotBlank String> allowedOrigins,
            @Positive int adminRequestsPerSecond,
            @Positive int writeRequestsPerSecond) {
    }

    private static boolean isPositive(Duration duration) {
        return duration != null && !duration.isNegative() && !duration.isZero();
    }
}
