package kr.ac.khu.graffiti.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import kr.ac.khu.graffiti.config.GraffitiProperties;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ModerationChecksTest {

    @Test
    void namesWithoutAnImplementationRunAsStubs() {
        ModerationCheck real = new WordFilterCheck(WordList.parse("시발", ""));

        ModerationChecks checks = new ModerationChecks(List.of(real),
                props(List.of("word-filter", "chatkhu"), List.of("openai-image")));

        assertThat(checks.forKind("TEXT")).hasSize(2);
        assertThat(checks.forKind("TEXT").get(0)).isSameAs(real);
        assertThat(checks.forKind("TEXT").get(1)).isInstanceOf(StubCheck.class);
        assertThat(checks.forKind("DRAWING")).singleElement().isInstanceOf(StubCheck.class);
    }

    @Test
    void anUnknownNameFailsStartup() {
        assertThatThrownBy(() -> new ModerationChecks(List.of(), props(List.of("word-filtre"), List.of())))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("word-filtre");
        assertThatThrownBy(() -> new ModerationChecks(List.of(), props(List.of("ml"), List.of())))
                .isInstanceOf(IllegalStateException.class);
    }

    private static GraffitiProperties props(List<String> text, List<String> drawing) {
        GraffitiProperties props = Mockito.mock(GraffitiProperties.class);
        Mockito.when(props.moderation()).thenReturn(new GraffitiProperties.Moderation(Duration.ofSeconds(5),
                GraffitiProperties.UncheckedPolicy.HOLD, text, drawing,
                new GraffitiProperties.Ml(GraffitiProperties.MlMode.SHADOW, "http://ml:8000", Duration.ofSeconds(3),
                        4, 1024)));
        return props;
    }
}
