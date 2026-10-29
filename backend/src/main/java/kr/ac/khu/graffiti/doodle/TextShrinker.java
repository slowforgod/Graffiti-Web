package kr.ac.khu.graffiti.doodle;

import kr.ac.khu.graffiti.doodle.DoodleValidator.Limits;
import kr.ac.khu.graffiti.doodle.DoodleValidator.Size;
import kr.ac.khu.graffiti.doodle.DoodleValidator.TextPayload;
import kr.ac.khu.graffiti.doodle.DoodleValidator.Validated;

/**
 * Shrinks a text with profanity to the shrink size (D-43, specs/07-moderation.md §5.3): the font size becomes
 * {@code size}, the bbox is scaled by {@code size / original} and rounded up, the top-left corner stays. The
 * result goes through {@link DoodleValidator#text} again, which recomputes the cells and chunks; D-31's bounds
 * are checked rounded up, so rounding the scaled box up never breaks them. Pure.
 */
final class TextShrinker {

    /** {@code doodle} at the new size, and the size it was sent with. */
    record Shrunk(Validated doodle, int fromSize) {
    }

    private TextShrinker() {
    }

    /** Null when the text is already at or below {@code size}. */
    static Shrunk shrink(Validated doodle, int size, Limits limits) {
        TextPayload p = (TextPayload) doodle.payload();
        if (p.size() <= size) {
            return null;
        }
        DoodleValidator.Box b = doodle.bbox();
        Size box = new Size(scaled(b.w(), size, p.size()), scaled(b.h(), size, p.size()));
        Validated shrunk = DoodleValidator.text(new TextPayload(p.v(), p.text(), p.c(), size, p.x(), p.y(), p.f()), box,
                limits);
        return new Shrunk(shrunk, p.size());
    }

    private static int scaled(int length, int to, int from) {
        return (int) (((long) length * to + from - 1) / from);
    }
}
