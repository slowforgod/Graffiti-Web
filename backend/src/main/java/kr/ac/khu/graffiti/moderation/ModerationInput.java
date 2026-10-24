package kr.ac.khu.graffiti.moderation;

/**
 * What a check looks at. {@code text} is the text doodle's text (null for a drawing); {@code payload} is the
 * stored payload JSON, which a drawing check renders.
 */
public record ModerationInput(String kind, String text, String payload) {

    public boolean isText() {
        return "TEXT".equals(kind);
    }
}
