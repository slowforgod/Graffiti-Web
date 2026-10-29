package kr.ac.khu.graffiti.doodle;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletionException;
import kr.ac.khu.graffiti.config.GraffitiProperties;
import kr.ac.khu.graffiti.doodle.DoodleValidator.Validated;
import kr.ac.khu.graffiti.moderation.MlAnalysis;
import kr.ac.khu.graffiti.moderation.ModerationService;
import kr.ac.khu.graffiti.moderation.ModerationState;
import kr.ac.khu.graffiti.page.PageCommand;
import kr.ac.khu.graffiti.page.PageCommandProcessor;
import kr.ac.khu.graffiti.page.PostStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Publishes a checked doodle (ARCHITECTURE.md §4.1): take the post in the queue, moderate outside it,
 * then in the queue publish it, or store it REJECTED or HELD. A post that was taken but not stored is given
 * back (D-28); a rejected or held one keeps it (D-12). A text with profanity only is published shrunk
 * ({@link TextShrinker}, D-43). Once stored, a drawing goes to the ML path
 * ({@link MlAnalysis#afterStored}), which never changes the result.
 */
@Service
public class PublishService {

    private static final Logger log = LoggerFactory.getLogger(PublishService.class);

    /**
     * What happened to the post. {@code state} is PASSED or UNCHECKED when published, REJECTED or HELD
     * otherwise; {@code category} is set on a rejection. {@code cooldownEndsAt} is null for admins (D-12).
     * {@code doodle} is what was stored: the shrunk text when {@code shrunk}.
     */
    public record Result(long id, Validated doodle, String payload, ModerationState state, String category,
            Instant cooldownEndsAt, boolean shrunk) {

        public boolean published() {
            return state.publishable();
        }
    }

    private final PageCommandProcessor processor;
    private final ModerationService moderation;
    private final MlAnalysis ml;
    private final ObjectMapper json;
    private final Duration cooldown;
    private final int shrinkFontSize;

    PublishService(PageCommandProcessor processor, ModerationService moderation, MlAnalysis ml, ObjectMapper json,
            GraffitiProperties props) {
        this.processor = processor;
        this.moderation = moderation;
        this.ml = ml;
        this.json = json;
        this.cooldown = props.postCooldown();
        this.shrinkFontSize = props.moderation().shrinkFontSize();
    }

    /** {@code limits} are the ones {@code doodle} was checked against; a shrunk text is checked again. */
    public Result publish(long pageId, long authorId, Validated doodle, DoodleValidator.Limits limits) {
        PageCommand.Reservation reservation = await(new PageCommand.ReservePost(pageId, authorId));
        ModerationService.Verdict verdict;
        String payload;
        boolean shrunk = false;
        long id;
        try {
            verdict = moderation.moderate(doodle.kind(), doodle.payload());
            if (verdict.shrink() && verdict.state().publishable()) {
                TextShrinker.Shrunk s = TextShrinker.shrink(doodle, shrinkFontSize, limits);
                if (s != null) {
                    doodle = s.doodle();
                    verdict = verdict.shrunkFrom(s.fromSize(), json);
                    shrunk = true;
                }
            }
            payload = write(doodle.payload());
            DoodleValidator.Box b = doodle.bbox();
            PostStore.NewDoodle row = new PostStore.NewDoodle(pageId, authorId, doodle.kind(),
                    verdict.state().name(), verdict.detail(), b.x(), b.y(), b.w(), b.h(), payload,
                    payload.getBytes(StandardCharsets.UTF_8).length, reservation.reservedAt(), doodle.chunks());
            id = verdict.state().publishable()
                    ? await(new PageCommand.PublishDoodle(row, doodle.footprint())).doodleId()
                    : await(new PageCommand.WithholdDoodle(row, verdict.state().name()));
        } catch (RuntimeException e) {
            giveBack(reservation);
            throw e;
        }
        ml.afterStored(id, doodle.kind(), payload, verdict.ml());
        return new Result(id, doodle, payload, verdict.state(), verdict.category(),
                reservation.exempt() ? null : reservation.reservedAt().plus(cooldown), shrunk);
    }

    private void giveBack(PageCommand.Reservation reservation) {
        try {
            await(new PageCommand.ReleasePost(reservation));
        } catch (RuntimeException e) {
            // The original failure is what the author sees; a cooldown left in place is the safe side.
            log.warn("Could not give a post back", e);
        }
    }

    private String write(Object payload) {
        try {
            return json.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private <R> R await(PageCommand<R> command) {
        try {
            return processor.submit(command).join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw e;
        }
    }
}
