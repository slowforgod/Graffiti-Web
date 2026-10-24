package kr.ac.khu.graffiti.page;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The rows a post changes, written from the page queue (INV-01, INV-08): the author's
 * {@code last_posted_at}, the doodle with its chunk rows, and the {@code moderation_action} record of a
 * moderation result or an admin decision (SEC-14). Implemented by the doodle package so the queue does not
 * depend on it.
 */
public interface PostStore {

    /** What the queue needs to know about an author at ✓, read from the database (SEC-15). */
    record Poster(boolean banned, boolean admin, Instant lastPostedAt) {
    }

    /** A doodle ready to insert. Strings are the DB enum names; {@code payload} is JSON. */
    record NewDoodle(long pageId, long authorId, String kind, String moderationState, String moderationDetail,
            int x, int y, int w, int h, String payload, int payloadBytes, Instant submittedAt,
            List<Integer> chunks) {
    }

    /**
     * One {@code moderation_action} row. {@code actorType} is AI, ADMIN or SYSTEM ({@code actorId} is the admin's
     * user id, else null); {@code detail} is JSON or null.
     */
    record Action(String actorType, Long actorId, String action, Long targetDoodleId, Long targetUserId,
            String reason, String detail) {
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

    /**
     * Inserts a doodle moderation kept off the canvas, {@code status} REJECTED or HELD, with its chunk rows (only
     * PUBLISHED doodles are read per chunk) and no {@code published_at}. Returns the doodle id.
     */
    long insertWithheld(NewDoodle doodle, String status);

    /** REJECTED or HELD ({@code from}) to PUBLISHED on {@code pageId}; false when the row is not in that state. */
    boolean publishWithheld(long doodleId, long pageId, String from, Instant publishedAt);

    /** HELD to REJECTED; false when the row is not HELD any more. */
    boolean rejectHeld(long doodleId);

    void recordAction(Action action, Instant at);
}
