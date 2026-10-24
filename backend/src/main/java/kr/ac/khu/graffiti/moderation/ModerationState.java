package kr.ac.khu.graffiti.moderation;

/**
 * {@code doodle.moderation_state} (DB.md §2). Only PASSED and UNCHECKED doodles are published (INV-05); HELD
 * waits for an admin and REJECTED is kept for review.
 */
public enum ModerationState {
    PASSED,
    REJECTED,
    HELD,
    UNCHECKED;

    public boolean publishable() {
        return this == PASSED || this == UNCHECKED;
    }
}
