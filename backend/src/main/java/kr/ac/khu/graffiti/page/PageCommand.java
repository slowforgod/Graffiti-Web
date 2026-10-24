package kr.ac.khu.graffiti.page;

import java.time.Instant;

/**
 * Work for the page command queue (INV-01). Each command names the page it targets; a command for a
 * page that is not current fails with {@code PAGE_ENDED} (INV-04).
 *
 * @param <R> result type
 */
public sealed interface PageCommand<R> {

    /** Dispatches to the handler; runs on the queue thread only. */
    R executeOn(PageCommandHandler handler);

    /** Adds ({@code delta = +1}) or removes ({@code -1}) one doodle's cells. May start the countdown. */
    record ApplyFootprint(long pageId, Footprint footprint, int delta) implements PageCommand<PageSnapshot> {

        @Override
        public PageSnapshot executeOn(PageCommandHandler handler) {
            return handler.applyFootprint(this);
        }
    }

    /**
     * Takes the author's post for {@code pageId} (D-12, INV-08): {@code BANNED} or {@code COOLDOWN} when they
     * may not post now. Admins are exempt and leave no trace.
     */
    record ReservePost(long pageId, long userId) implements PageCommand<Reservation> {

        @Override
        public Reservation executeOn(PageCommandHandler handler) {
            return handler.reservePost(this);
        }
    }

    /** Publishes a checked doodle on its page: inserts it, adds its cells and may start the countdown. */
    record PublishDoodle(PostStore.NewDoodle doodle, Footprint footprint) implements PageCommand<Published> {

        @Override
        public Published executeOn(PageCommandHandler handler) {
            return handler.publish(this);
        }
    }

    /**
     * Stores a doodle moderation kept off the canvas: {@code status} REJECTED or HELD (D-42, PROPOSED), with a
     * {@code moderation_action(AI, REJECT|HOLD)} row. No cells change; the post stays taken (D-12).
     */
    record WithholdDoodle(PostStore.NewDoodle doodle, String status) implements PageCommand<Long> {

        @Override
        public Long executeOn(PageCommandHandler handler) {
            return handler.withhold(this);
        }
    }

    /**
     * An admin publishes a HELD or REJECTED ({@code from}) doodle of the current page: like {@link PublishDoodle}
     * but updating the stored row, with {@code moderation_action(ADMIN, APPROVE|RESTORE)}. {@code doodle} is the
     * stored row, for the broadcast.
     */
    record PublishWithheld(long doodleId, PostStore.NewDoodle doodle, String from, Footprint footprint, long adminId)
            implements PageCommand<Published> {

        @Override
        public Published executeOn(PageCommandHandler handler) {
            return handler.publishWithheld(this);
        }
    }

    /** An admin rejects a HELD doodle, on any page; {@code moderation_action(ADMIN, REJECT)}. */
    record RejectHeld(long doodleId, long authorId, long adminId) implements PageCommand<Boolean> {

        @Override
        public Boolean executeOn(PageCommandHandler handler) {
            return handler.rejectHeld(this);
        }
    }

    /** Gives a reserved post back when it was not published for reasons not the author's (D-28). */
    record ReleasePost(Reservation reservation) implements PageCommand<Boolean> {

        @Override
        public Boolean executeOn(PageCommandHandler handler) {
            return handler.release(this);
        }
    }

    /** A post taken at {@code reservedAt}; {@code previous} is the {@code last_posted_at} it replaced. */
    record Reservation(long userId, Instant reservedAt, Instant previous, boolean exempt) {
    }

    record Published(long doodleId, Instant publishedAt) {
    }

    /** Ends the page when its scheduled end has passed and opens the next one. */
    record EndPage(long pageId) implements PageCommand<PageSnapshot> {

        @Override
        public PageSnapshot executeOn(PageCommandHandler handler) {
            return handler.endPage(this);
        }
    }

    /** Reads the current page. */
    record GetPageSnapshot() implements PageCommand<PageSnapshot> {

        @Override
        public PageSnapshot executeOn(PageCommandHandler handler) {
            return handler.snapshot();
        }
    }

    /** Reads the occupancy bitmap of {@code pageId}, which must be the current page (D-08). */
    record GetMinimap(long pageId) implements PageCommand<Minimap> {

        @Override
        public Minimap executeOn(PageCommandHandler handler) {
            return handler.minimap(this);
        }
    }

    /** Startup only: returns the live page id, creating page seq 1 (or the next seq) when none exists. */
    record EnsureLivePage() implements PageCommand<Long> {

        @Override
        public Long executeOn(PageCommandHandler handler) {
            return handler.ensureLivePage();
        }
    }

    /** Startup only: makes {@code pageId} current with a grid rebuilt outside the queue (INV-02, D-25). */
    record InstallPage(long pageId, OccupancyGrid grid) implements PageCommand<PageSnapshot> {

        @Override
        public PageSnapshot executeOn(PageCommandHandler handler) {
            return handler.install(this);
        }
    }
}
