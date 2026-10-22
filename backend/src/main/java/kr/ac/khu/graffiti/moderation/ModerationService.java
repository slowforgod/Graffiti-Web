package kr.ac.khu.graffiti.moderation;

import org.springframework.stereotype.Service;

/**
 * Checks a doodle before it is published, outside the page queue (INV-02, ARCHITECTURE.md §4.1 ④).
 *
 * <p>06-publish placeholder: nothing is checked yet, so every doodle is {@code UNCHECKED}. 07-moderation
 * replaces the inside with the providers of D-19. Never deploy 06 alone (INV-05, PIPELINE.md).
 */
@Service
public class ModerationService {

    /** The outcome; {@code detail} is the JSON stored in {@code moderation_detail}, or null. */
    public record Verdict(ModerationState state, String detail) {
    }

    /** {@code payload} is the checked payload record of the doodle. */
    public Verdict moderate(String kind, Object payload) {
        return new Verdict(ModerationState.UNCHECKED, null);
    }
}
