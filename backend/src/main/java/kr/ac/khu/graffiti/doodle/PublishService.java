package kr.ac.khu.graffiti.doodle;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletionException;
import kr.ac.khu.graffiti.config.GraffitiProperties;
import kr.ac.khu.graffiti.doodle.DoodleValidator.Validated;
import kr.ac.khu.graffiti.moderation.ModerationService;
import kr.ac.khu.graffiti.page.PageCommand;
import kr.ac.khu.graffiti.page.PageCommandProcessor;
import kr.ac.khu.graffiti.page.PostStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Publishes a checked doodle (ARCHITECTURE.md §4.1): take the post in the queue, moderate outside it,
 * publish in the queue. A post that was taken but not published is given back unless moderation
 * rejected it (D-12, D-28).
 */
@Service
public class PublishService {

    private static final Logger log = LoggerFactory.getLogger(PublishService.class);

    /** The published doodle; {@code cooldownEndsAt} is null for admins (D-12). */
    public record Result(long id, Validated doodle, String payload, Instant cooldownEndsAt) {
    }

    private final PageCommandProcessor processor;
    private final ModerationService moderation;
    private final ObjectMapper json;
    private final Duration cooldown;

    PublishService(PageCommandProcessor processor, ModerationService moderation, ObjectMapper json,
            GraffitiProperties props) {
        this.processor = processor;
        this.moderation = moderation;
        this.json = json;
        this.cooldown = props.postCooldown();
    }

    public Result publish(long pageId, long authorId, Validated doodle) {
        String payload = write(doodle.payload());
        PageCommand.Reservation reservation = await(new PageCommand.ReservePost(pageId, authorId));
        try {
            ModerationService.Verdict verdict = moderation.moderate(doodle.kind(), doodle.payload());
            if (!verdict.state().publishable()) {
                // Rejections, which keep the cooldown taken, arrive with 07-moderation.
                throw new IllegalStateException("moderation rejected a doodle before 07");
            }
            DoodleValidator.Box b = doodle.bbox();
            PostStore.NewDoodle row = new PostStore.NewDoodle(pageId, authorId, doodle.kind(),
                    verdict.state().name(), verdict.detail(), b.x(), b.y(), b.w(), b.h(), payload,
                    payload.getBytes(StandardCharsets.UTF_8).length, reservation.reservedAt(), doodle.chunks());
            PageCommand.Published published = await(new PageCommand.PublishDoodle(row, doodle.footprint()));
            return new Result(published.doodleId(), doodle, payload,
                    reservation.exempt() ? null : reservation.reservedAt().plus(cooldown));
        } catch (RuntimeException e) {
            giveBack(reservation);
            throw e;
        }
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
