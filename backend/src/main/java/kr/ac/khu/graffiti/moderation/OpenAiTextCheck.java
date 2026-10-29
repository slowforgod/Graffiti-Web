package kr.ac.khu.graffiti.moderation;

import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

/** The second text check (D-19 ③, SEC-05 double check): OpenAI moderation on the text as written. */
@Component
@Conditional(OpenAiModerationClient.KeyPresent.class)
class OpenAiTextCheck implements ModerationCheck {

    static final String NAME = "openai-text";

    private final OpenAiModerationClient client;

    OpenAiTextCheck(OpenAiModerationClient client) {
        this.client = client;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public CheckResult check(ModerationInput input) {
        if (!input.isText()) {
            return CheckResult.skipped(NAME);
        }
        return client.moderate(NAME, client.json().getNodeFactory().textNode(input.text()));
    }
}
