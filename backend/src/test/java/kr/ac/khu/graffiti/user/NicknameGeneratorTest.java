package kr.ac.khu.graffiti.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class NicknameGeneratorTest {

    private static final List<String> PALETTE = List.of("#000000", "#FFFFFF", "#e53935");

    @Test
    void nicknameHasAnimalAndFourDigits() {
        NicknameGenerator generator = new NicknameGenerator(new Random(1), PALETTE);
        for (int i = 0; i < 200; i++) {
            assertThat(generator.nickname()).matches("익명의 \\S+ #\\d{4}");
        }
    }

    @Test
    void cursorColorComesFromPaletteWithoutWhite() {
        NicknameGenerator generator = new NicknameGenerator(new Random(2), PALETTE);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            seen.add(generator.cursorColor());
        }
        assertThat(seen).containsExactlyInAnyOrder("#000000", "#E53935");
    }

    @Test
    void smallNumbersArePadded() {
        NicknameGenerator generator = new NicknameGenerator(new FixedRandom(7), PALETTE);
        assertThat(generator.nickname()).endsWith("#0007");
    }

    @Test
    void rejectsPaletteWithOnlyWhite() {
        assertThatThrownBy(() -> new NicknameGenerator(new Random(), List.of("#ffffff")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static final class FixedRandom implements java.util.random.RandomGenerator {
        private final int value;

        FixedRandom(int value) {
            this.value = value;
        }

        @Override
        public long nextLong() {
            return value;
        }

        @Override
        public int nextInt(int bound) {
            return Math.min(value, bound - 1);
        }
    }
}
