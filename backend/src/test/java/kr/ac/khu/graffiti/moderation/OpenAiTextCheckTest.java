package kr.ac.khu.graffiti.moderation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import kr.ac.khu.graffiti.config.GraffitiProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Configuration;

@ExtendWith(OutputCaptureExtension.class)
class OpenAiTextCheckTest {

    static final Duration TIMEOUT = Duration.ofMillis(500);
    static final String KEY = "test-key-not-real";
    private static final String SECRET = "비밀 글자";

    private final ObjectMapper json = new ObjectMapper();
    private final FakeApiServer server = new FakeApiServer();

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void sendsTheTextAndPassesWhenNothingIsFlagged() throws Exception {
        server.body.set(answer());

        assertThat(check().check(text(SECRET))).isEqualTo(CheckResult.pass("openai-text"));
        assertThat(server.path.get()).isEqualTo("/v1/moderations");
        assertThat(server.authorization.get()).isEqualTo("Bearer " + KEY);
        JsonNode sent = json.readTree(server.received.get());
        assertThat(sent.get("model").asText()).isEqualTo("omni-test");
        assertThat(sent.get("input").asText()).isEqualTo(SECRET);
    }

    // specs/07-moderation.md §5.5 table, text column.
    @ParameterizedTest
    @CsvSource({
        "sexual, BLOCK, SEXUAL",
        "sexual/minors, BLOCK, SEXUAL",
        "hate, BLOCK, HATE",
        "hate/threatening, BLOCK, HATE",
        "harassment, BLOCK, PROFANITY",
        "harassment/threatening, REVIEW, THREAT",
        "violence, REVIEW, VIOLENCE",
        "violence/graphic, REVIEW, VIOLENCE",
        "self-harm, REVIEW, SELF_HARM",
        "self-harm/intent, REVIEW, SELF_HARM",
        "self-harm/instructions, REVIEW, SELF_HARM",
        "illicit, REVIEW, ILLICIT",
        "illicit/violent, REVIEW, ILLICIT",
    })
    void mapsEachOpenAiCategory(String flagged, CheckOutcome outcome, String category) {
        server.body.set(answer(flagged));

        assertThat(check().check(text(SECRET))).isEqualTo(new CheckResult("openai-text", outcome, category, null));
    }

    @Test
    void aRejectingCategoryWinsOverProfanityAndReview() {
        server.body.set(answer("violence", "harassment", "hate"));
        assertThat(check().check(text(SECRET))).isEqualTo(CheckResult.block("openai-text", "HATE"));

        server.body.set(answer("violence", "harassment"));
        assertThat(check().check(text(SECRET))).isEqualTo(CheckResult.block("openai-text", "PROFANITY"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "not json",
        "{}",
        "{\"results\":[]}",
        "{\"results\":[{\"flagged\":true}]}",
        "{\"results\":[{\"categories\":[]}]}",
        "{\"results\":[{\"categories\":{\"hate\":\"yes\"}}]}",
    })
    void aBodyWithoutBooleanCategoriesIsAnError(String body) {
        server.body.set(body);

        assertThat(check().check(text(SECRET))).isEqualTo(CheckResult.error("openai-text", "BAD_RESPONSE"));
    }

    @Test
    void statusCodesAndSlownessAreErrors(CapturedOutput output) {
        server.status.set(401);
        assertThat(check().check(text(SECRET))).isEqualTo(CheckResult.error("openai-text", "HTTP_401"));
        server.status.set(429);
        assertThat(check().check(text(SECRET))).isEqualTo(CheckResult.error("openai-text", "HTTP_429"));

        server.status.set(200);
        server.body.set(answer());
        server.delay.set(TIMEOUT.multipliedBy(3));
        assertThat(check().check(text(SECRET))).isEqualTo(CheckResult.error("openai-text", "TIMEOUT"));

        assertThat(output.getAll()).doesNotContain(SECRET).doesNotContain(KEY);
    }

    @Test
    void skipsDrawings() {
        assertThat(check().check(new ModerationInput("DRAWING", null, "{}")).outcome())
                .isEqualTo(CheckOutcome.SKIPPED);
    }

    @Test
    void theClientAndBothChecksAreBeansOnlyWhenTheKeyIsSet() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withBean(ObjectMapper.class)
                .withUserConfiguration(Props.class, OpenAiModerationClient.class, OpenAiTextCheck.class,
                        OpenAiImageCheck.class);

        runner.withPropertyValues("graffiti.moderation.openai.api-key= ").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(OpenAiModerationClient.class);
            assertThat(context).doesNotHaveBean(ModerationCheck.class);
        });
        runner.withPropertyValues("graffiti.moderation.openai.api-key=" + KEY).run(context -> {
            assertThat(context).hasSingleBean(OpenAiTextCheck.class);
            assertThat(context).hasSingleBean(OpenAiImageCheck.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(GraffitiProperties.class)
    static class Props {
    }

    private OpenAiTextCheck check() {
        return new OpenAiTextCheck(client(server, json));
    }

    static OpenAiModerationClient client(FakeApiServer server, ObjectMapper json) {
        return new OpenAiModerationClient(server.url("/v1"), "omni-test", TIMEOUT, KEY, json);
    }

    private static ModerationInput text(String text) {
        return new ModerationInput("TEXT", text, "{}");
    }

    /** A moderation response with every §5.5 category false except {@code flagged}. */
    static String answer(String... flagged) {
        StringBuilder categories = new StringBuilder();
        for (String name : new String[] {"sexual", "sexual/minors", "hate", "hate/threatening", "harassment",
                "harassment/threatening", "violence", "violence/graphic", "self-harm", "self-harm/intent",
                "self-harm/instructions", "illicit", "illicit/violent"}) {
            boolean on = java.util.Arrays.asList(flagged).contains(name);
            categories.append(categories.isEmpty() ? "" : ",").append('"').append(name).append("\":").append(on);
        }
        return "{\"id\":\"modr-1\",\"model\":\"omni-moderation-latest\",\"results\":[{\"flagged\":"
                + (flagged.length > 0) + ",\"categories\":{" + categories + "},\"category_scores\":{}}]}";
    }
}
