package kr.ac.khu.graffiti.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class GraffitiPropertiesTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(GraffitiProperties.class)
    static class Config {
    }

    // Loads the real application.yml so the test checks the shipped defaults, not a copy.
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(Config.class);

    @Test
    void bindsArchitectureDefaults() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            GraffitiProperties p = context.getBean(GraffitiProperties.class);

            assertThat(p.canvas().width()).isEqualTo(7680);
            assertThat(p.canvas().height()).isEqualTo(4320);
            assertThat(p.canvas().zoomMin()).isEqualTo(0.5);
            assertThat(p.canvas().zoomMax()).isEqualTo(4.0);
            assertThat(p.canvas().chunkSize()).isEqualTo(480);
            assertThat(p.canvas().cellSize()).isEqualTo(48);

            assertThat(p.page().countdownThreshold()).isEqualTo(0.70);
            assertThat(p.page().countdownDuration()).isEqualTo(Duration.ofHours(6));
            assertThat(p.page().maxLifetime()).isEqualTo(Duration.ofDays(7));
            assertThat(p.page().countdownWarning()).isEqualTo(Duration.ofMinutes(5));

            assertThat(p.doodle().maxBbox()).isEqualTo(1024);
            assertThat(p.doodle().maxPayloadBytes()).isEqualTo(2_097_152);
            assertThat(p.doodle().palette()).hasSize(12);
            assertThat(p.doodle().strokeWidths()).containsExactly(2, 4, 8, 16);
            assertThat(p.doodle().fontSizes())
                    .containsExactly(10, 11, 12, 13, 14, 15, 16, 20, 24, 32, 36, 40, 48, 64, 96);
            assertThat(p.doodle().defaultFontSize()).isEqualTo(24);
            assertThat(p.doodle().textMaxLength()).isEqualTo(50);
            assertThat(p.doodle().draftIdleTimeout()).isEqualTo(Duration.ofMinutes(10));

            assertThat(p.postCooldown()).isEqualTo(Duration.ofMinutes(30));

            assertThat(p.realtime().maxMessagesPerSecond()).isEqualTo(20);
            assertThat(p.realtime().maxSubscriptions()).isEqualTo(60);
            assertThat(p.realtime().inboundMessageMaxBytes()).isEqualTo(65_536);

            assertThat(p.query().maxChunks()).isEqualTo(60);
            assertThat(p.query().maxBatchDelete()).isEqualTo(50);

            assertThat(p.moderation().timeout()).isEqualTo(Duration.ofSeconds(5));
            assertThat(p.moderation().ml().mode()).isEqualTo(GraffitiProperties.MlMode.SHADOW);
            assertThat(p.moderation().ml().baseUrl()).isEqualTo("http://ml:8000");
            assertThat(p.moderation().ml().timeout()).isEqualTo(Duration.ofSeconds(3));
            assertThat(p.moderation().ml().flagThreshold()).isNull();

            assertThat(p.auth().allowedDomain()).isEqualTo("khu.ac.kr");
            assertThat(p.auth().sessionTimeout()).isEqualTo(Duration.ofDays(30));
            assertThat(p.auth().nicknameMaxAttempts()).isEqualTo(10);
        });
    }

    @Test
    void readsAdminEmailsAndOriginsFromEnvironmentStyleProperties() {
        runner.withPropertyValues(
                        "ADMIN_EMAILS=a@khu.ac.kr,b@khu.ac.kr",
                        "APP_ORIGIN=https://graffiti.example")
                .run(context -> {
                    GraffitiProperties p = context.getBean(GraffitiProperties.class);
                    assertThat(p.auth().adminEmails()).isEqualTo(List.of("a@khu.ac.kr", "b@khu.ac.kr"));
                    assertThat(p.web().allowedOrigins()).isEqualTo(List.of("https://graffiti.example"));
                });
    }

    @Test
    void rejectsThresholdAboveOne() {
        assertStartupFails("graffiti.page.countdown-threshold=1.5");
    }

    @Test
    void rejectsEmptyPalette() {
        assertStartupFails("graffiti.doodle.palette=");
    }

    @Test
    void rejectsNonHexPaletteColor() {
        assertStartupFails("graffiti.doodle.palette[0]=red");
    }

    @Test
    void rejectsZoomMinAboveZoomMax() {
        assertStartupFails("graffiti.canvas.zoom-min=5");
    }

    @Test
    void rejectsChunkSizeNotMultipleOfCellSize() {
        assertStartupFails("graffiti.canvas.chunk-size=500");
    }

    @Test
    void rejectsCanvasNotMultipleOfChunkSize() {
        assertStartupFails("graffiti.canvas.width=7000");
    }

    @Test
    void rejectsNonPositiveCooldown() {
        assertStartupFails("graffiti.post-cooldown=0s");
    }

    @Test
    void rejectsNonPositiveSessionTimeout() {
        assertStartupFails("graffiti.auth.session-timeout=0s");
    }

    @Test
    void rejectsDefaultFontSizeOutsideTheList() {
        assertStartupFails("graffiti.doodle.default-font-size=25");
    }

    @Test
    void rejectsUnknownMlMode() {
        assertStartupFails("graffiti.moderation.ml.mode=ON");
    }

    private void assertStartupFails(String property) {
        runner.withPropertyValues(property).run(context -> assertThat(context).hasFailed());
    }
}
