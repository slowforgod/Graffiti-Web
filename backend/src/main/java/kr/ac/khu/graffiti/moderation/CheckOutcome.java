package kr.ac.khu.graffiti.moderation;

/** What one check said about a doodle. */
public enum CheckOutcome {
    /** Checked and fine. */
    PASS,
    /** Checked and not allowed: the doodle is rejected. */
    BLOCK,
    /** Checked but not sure: an admin decides (HELD). */
    REVIEW,
    /** The check could not give an answer (timeout, HTTP error, malformed response): held like REVIEW. */
    ERROR,
    /** Nothing was checked, e.g. a stub standing in for a check that is not built yet. */
    SKIPPED
}
