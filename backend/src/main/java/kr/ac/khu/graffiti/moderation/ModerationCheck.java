package kr.ac.khu.graffiti.moderation;

/**
 * One step of the moderation pipeline (D-19): the word filter, ChatKHU, an image check or the ML server.
 * A Spring bean implementing this replaces the stub of the same {@link #name()}; the configuration
 * ({@code graffiti.moderation.text-checks}, {@code drawing-checks}) decides which names run and in which order.
 *
 * <p>Runs outside the page queue (INV-02) with the moderation timeout around it. Throwing is the same as
 * returning {@link CheckOutcome#ERROR}.
 */
public interface ModerationCheck {

    /** The configured name, e.g. {@code word-filter}. */
    String name();

    CheckResult check(ModerationInput input);
}
