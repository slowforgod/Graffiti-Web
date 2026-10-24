package kr.ac.khu.graffiti.moderation;

import java.text.Normalizer;
import java.util.Locale;

/**
 * The basic normalization for the word filter: NFKC, lower case, and no spaces, punctuation, symbols or
 * zero-width characters ("시 발", "ㅅ.ㅂ"). The fuller rules of FILTER.md §2 (look-alikes, jamo) come when the
 * criteria are settled. Pure; the result is only compared, never stored.
 */
public final class TextNormalizer {

    private TextNormalizer() {
    }

    public static String normalize(String text) {
        String folded = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(folded.length());
        folded.codePoints().filter(TextNormalizer::kept).forEach(out::appendCodePoint);
        return out.toString();
    }

    private static boolean kept(int cp) {
        return Character.isLetterOrDigit(cp);
    }
}
