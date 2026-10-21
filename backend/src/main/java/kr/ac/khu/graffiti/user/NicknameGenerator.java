package kr.ac.khu.graffiti.user;

import java.util.List;
import java.util.Locale;
import java.util.random.RandomGenerator;

/** Random anonymous identity for a new account (D-22): "익명의 {동물} #dddd" and a cursor color. */
public class NicknameGenerator {

    private static final List<String> ANIMALS = List.of(
            "펭귄", "고양이", "강아지", "여우", "토끼", "다람쥐", "고슴도치", "수달", "판다", "코알라",
            "부엉이", "참새", "돌고래", "고래", "거북이", "사슴", "너구리", "햄스터", "오리", "알파카");

    private static final String WHITE = "#FFFFFF";
    private static final int NUMBER_BOUND = 10_000; // four digits, "#0000" to "#9999"

    private final RandomGenerator random;
    private final List<String> cursorColors;

    /** {@code palette} is the drawing palette (D-07); white is left out because it vanishes on the canvas. */
    public NicknameGenerator(RandomGenerator random, List<String> palette) {
        this.random = random;
        this.cursorColors = palette.stream()
                .map(c -> c.toUpperCase(Locale.ROOT))
                .filter(c -> !c.equals(WHITE))
                .toList();
        if (cursorColors.isEmpty()) {
            throw new IllegalArgumentException("palette has no usable cursor color");
        }
    }

    public String nickname() {
        String animal = ANIMALS.get(random.nextInt(ANIMALS.size()));
        return "익명의 %s #%04d".formatted(animal, random.nextInt(NUMBER_BOUND));
    }

    public String cursorColor() {
        return cursorColors.get(random.nextInt(cursorColors.size()));
    }
}
