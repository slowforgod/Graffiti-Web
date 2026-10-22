package kr.ac.khu.graffiti.moderation;

/** {@code doodle.moderation_state} (DB.md §2). Only PASSED and UNCHECKED doodles are published (INV-05). */
public enum ModerationState {
    PASSED,
    REJECTED,
    UNCHECKED;

    public boolean publishable() {
        return this != REJECTED;
    }
}
