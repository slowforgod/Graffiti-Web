package kr.ac.khu.graffiti.page;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The rows a post changes, written from the page queue (INV-01, INV-08): the author's
 * {@code last_posted_at} and the doodle with its chunk rows. Implemented by the doodle package so the
 * queue does not depend on it.
 */
public interface PostStore {

    /** What the queue needs to know about an author at ✓, read from the database (SEC-15). */
    record Poster(boolean banned, boolean admin, Instant lastPostedAt) {
    }

    /** A doodle ready to insert as PUBLISHED. Strings are the DB enum names; {@code payload} is JSON. */
    record NewDoodle(long pageId, long authorId, String kind, String moderationState, String moderationDetail,
            int x, int y, int w, int h, String payload, int payloadBytes, Instant submittedAt,
            List<Integer> chunks) {
    }

    Optional<Poster> poster(long userId);

    /**
     * Takes the post: sets {@code last_posted_at} to {@code at} if it is still {@code expected} and the user
     * is not banned. Returns false when the row changed meanwhile.
     */
    boolean reserve(long userId, Instant expected, Instant at);

    /** Gives the post back (D-28): sets {@code last_posted_at} to {@code previous} if it is still {@code at}. */
    boolean restore(long userId, Instant at, Instant previous);

    /** Inserts the doodle and its {@code doodle_chunk} rows; returns the doodle id. */
    long insertPublished(NewDoodle doodle, Instant publishedAt);
}
