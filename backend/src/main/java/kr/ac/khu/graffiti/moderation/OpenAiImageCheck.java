package kr.ac.khu.graffiti.moderation;

import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.Base64;
import kr.ac.khu.graffiti.config.GraffitiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

/**
 * The drawing check (D-19): the drawing rendered to PNG ({@link DoodleRenderer}) and sent to OpenAI moderation
 * as a data URL. A payload that does not render is ERROR.
 */
@Component
@Conditional(OpenAiModerationClient.KeyPresent.class)
class OpenAiImageCheck implements ModerationCheck {

    static final String NAME = "openai-image";

    private static final Logger log = LoggerFactory.getLogger(OpenAiImageCheck.class);

    private final OpenAiModerationClient client;
    private final DoodleRenderer renderer;

    @Autowired
    OpenAiImageCheck(OpenAiModerationClient client, GraffitiProperties props) {
        this(client, new DoodleRenderer(props.doodle().palette(), props.moderation().ml().renderMaxSide()));
    }

    OpenAiImageCheck(OpenAiModerationClient client, DoodleRenderer renderer) {
        this.client = client;
        this.renderer = renderer;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public CheckResult check(ModerationInput input) {
        if (input.isText()) {
            return CheckResult.skipped(NAME);
        }
        byte[] png;
        try {
            png = renderer.render(input.payload());
        } catch (IllegalArgumentException e) {
            log.warn("Drawing did not render for {}", NAME);
            return CheckResult.error(NAME, "RENDER");
        }
        ArrayNode parts = client.json().createArrayNode();
        parts.addObject().put("type", "image_url").putObject("image_url")
                .put("url", "data:image/png;base64," + Base64.getEncoder().encodeToString(png));
        return client.moderate(NAME, parts);
    }
}
