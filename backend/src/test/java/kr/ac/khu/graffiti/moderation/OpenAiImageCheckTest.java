package kr.ac.khu.graffiti.moderation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class OpenAiImageCheckTest {

    private static final String DRAWING = "{\"v\":1,\"strokes\":[{\"c\":1,\"w\":10,\"p\":[100,50,200,50]}]}";
    private static final String PREFIX = "data:image/png;base64,";

    private final ObjectMapper json = new ObjectMapper();
    private final FakeApiServer server = new FakeApiServer();
    private final OpenAiImageCheck check = new OpenAiImageCheck(OpenAiTextCheckTest.client(server, json),
            new DoodleRenderer(List.of("#000000", "#FF0000"), 1024));

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void sendsTheRenderedDrawingAsAPngDataUrl() throws Exception {
        server.body.set(OpenAiTextCheckTest.answer());

        assertThat(check.check(drawing(DRAWING))).isEqualTo(CheckResult.pass("openai-image"));
        JsonNode part = json.readTree(server.received.get()).at("/input/0");
        assertThat(part.get("type").asText()).isEqualTo("image_url");
        String url = part.at("/image_url/url").asText();
        assertThat(url).startsWith(PREFIX);
        byte[] png = Base64.getDecoder().decode(url.substring(PREFIX.length()));
        assertThat(ImageIO.read(new ByteArrayInputStream(png)).getWidth()).isGreaterThan(100);
    }

    // §5.5 table, drawing column; a drawing's profanity is published UNCHECKED by VerdictCombiner (AC-6).
    @Test
    void mapsCategoriesLikeText() {
        server.body.set(OpenAiTextCheckTest.answer("sexual"));
        assertThat(check.check(drawing(DRAWING))).isEqualTo(CheckResult.block("openai-image", "SEXUAL"));
        server.body.set(OpenAiTextCheckTest.answer("hate"));
        assertThat(check.check(drawing(DRAWING))).isEqualTo(CheckResult.block("openai-image", "HATE"));
        server.body.set(OpenAiTextCheckTest.answer("violence/graphic"));
        assertThat(check.check(drawing(DRAWING))).isEqualTo(CheckResult.review("openai-image", "VIOLENCE"));
    }

    @Test
    void aDrawingThatDoesNotRenderIsAnErrorWithoutACall() {
        assertThat(check.check(drawing("{\"v\":1,\"strokes\":[]}")))
                .isEqualTo(CheckResult.error("openai-image", "RENDER"));
        assertThat(server.received.get()).isNull();
    }

    @Test
    void skipsText() {
        assertThat(check.check(new ModerationInput("TEXT", "글", "{}")).outcome()).isEqualTo(CheckOutcome.SKIPPED);
    }

    private static ModerationInput drawing(String payload) {
        return new ModerationInput("DRAWING", null, payload);
    }
}
