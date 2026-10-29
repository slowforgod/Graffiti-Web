package kr.ac.khu.graffiti.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.ac.khu.graffiti.auth.SessionUser;
import kr.ac.khu.graffiti.page.FreshPage;
import kr.ac.khu.graffiti.page.PageSnapshot;
import kr.ac.khu.graffiti.support.MutableClock;
import kr.ac.khu.graffiti.support.WebIntegrationTest;
import kr.ac.khu.graffiti.user.User;
import kr.ac.khu.graffiti.user.UserAccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Publishing with the real ChatKHU and OpenAI checks against local fake servers (specs/07-moderation.md §8),
 * under the shipped {@code unchecked=HOLD}: real checks that pass publish PASSED (AC-13), and a failing one
 * publishes UNCHECKED instead of holding (AC-8, D-42).
 */
@TestPropertySource(properties = "graffiti.moderation.unchecked=HOLD")
class ExternalChecksIT extends WebIntegrationTest {

    private static final String CSRF_TOKEN = "test-csrf-token";
    private static final FakeApiServer CHATKHU = new FakeApiServer();
    private static final FakeApiServer OPENAI = new FakeApiServer();
    private static final String TEXT = """
            {"kind": "TEXT", "payload": {"v": 1, "text": "안녕", "c": 0, "size": 24, "x": 1500, "y": 900},
             "bbox": {"w": 48, "h": 24}}""";
    private static final String DRAWING = """
            {"kind": "DRAWING", "payload": {"v": 1, "strokes": [{"c": 3, "w": 8, "p": [470, 10, 490, 12]}]}}""";

    @DynamicPropertySource
    static void fakeServers(DynamicPropertyRegistry registry) {
        registry.add("graffiti.moderation.chatkhu.base-url", () -> CHATKHU.url("/v1/gateway").toString());
        registry.add("graffiti.moderation.chatkhu.api-key", () -> "test-chatkhu-key");
        registry.add("graffiti.moderation.openai.base-url", () -> OPENAI.url("/v1").toString());
        registry.add("graffiti.moderation.openai.api-key", () -> "test-openai-key");
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private MutableClock clock;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private UserAccountService accounts;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper json;

    private PageSnapshot page;

    @BeforeEach
    void startOnFreshPageWithPassingServers() {
        page = FreshPage.start(context, clock);
        CHATKHU.status.set(200);
        CHATKHU.body.set(chatkhu("{\"verdict\":\"PASS\",\"category\":null,\"reason\":\"인사\"}"));
        OPENAI.status.set(200);
        OPENAI.body.set(OpenAiTextCheckTest.answer());
    }

    @Test
    void aTextEveryCheckPassesIsPublishedPassed() throws Exception {
        long id = read(publish(TEXT).andExpect(status().isCreated())).at("/doodle/id").asLong();

        assertThat(state(id)).isEqualTo("PASSED");
        assertThat(outcomes(id)).isEqualTo(Map.of("personal-info", "PASS", "word-filter", "PASS",
                "chatkhu", "PASS", "openai-text", "PASS"));
        assertThat(json.readTree(OPENAI.received.get()).get("input").asText()).isEqualTo("안녕");
    }

    @Test
    void aDrawingOpenAiPassesIsPublishedPassed() throws Exception {
        long id = read(publish(DRAWING).andExpect(status().isCreated())).at("/doodle/id").asLong();

        assertThat(state(id)).isEqualTo("PASSED");
        assertThat(json.readTree(OPENAI.received.get()).at("/input/0/image_url/url").asText())
                .startsWith("data:image/png;base64,");
    }

    @Test
    void aChatKhuBlockRejectsTheText() throws Exception {
        CHATKHU.body.set(chatkhu("{\"verdict\":\"BLOCK\",\"category\":\"HATE\",\"reason\":\"비하\"}"));

        publish(TEXT).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.details.category").value("HATE"));
    }

    @Test
    void aFailingChatKhuPublishesUncheckedInsteadOfHolding() throws Exception {
        CHATKHU.status.set(503);

        long id = read(publish(TEXT).andExpect(status().isCreated())).at("/doodle/id").asLong();

        assertThat(state(id)).isEqualTo("UNCHECKED");
        assertThat(outcomes(id)).containsEntry("chatkhu", "ERROR").containsEntry("openai-text", "PASS");
    }

    @Test
    void openAiHarassmentShrinksTheText() throws Exception {
        OPENAI.body.set(OpenAiTextCheckTest.answer("harassment"));

        JsonNode body = read(publish(TEXT).andExpect(status().isCreated()));

        assertThat(body.get("shrunk").asBoolean()).isTrue();
        assertThat(body.at("/doodle/payload/size").asInt()).isEqualTo(10);
        assertThat(state(body.at("/doodle/id").asLong())).isEqualTo("PASSED");
    }

    @Test
    void openAiViolenceInADrawingPublishesItUnchecked() throws Exception {
        OPENAI.body.set(OpenAiTextCheckTest.answer("violence"));

        long id = read(publish(DRAWING).andExpect(status().isCreated())).at("/doodle/id").asLong();

        assertThat(state(id)).isEqualTo("UNCHECKED");
        assertThat(outcomes(id)).containsEntry("openai-image", "REVIEW");
    }

    private String state(long id) {
        return jdbc.queryForObject("SELECT moderation_state FROM doodle WHERE id = ?", String.class, id);
    }

    private Map<String, String> outcomes(long id) throws Exception {
        JsonNode checks = json.readTree(jdbc.queryForObject("SELECT moderation_detail FROM doodle WHERE id = ?",
                String.class, id)).get("checks");
        Map<String, String> result = new java.util.LinkedHashMap<>();
        checks.forEach(c -> result.put(c.get("check").asText(), c.get("outcome").asText()));
        return result;
    }

    // A new author each time, so the cooldown never refuses.
    private ResultActions publish(String body) throws Exception {
        User user = accounts.loginOrRegister("sub-" + UUID.randomUUID(), "x-" + UUID.randomUUID() + "@khu.ac.kr");
        accounts.consent(user.getId());
        return mvc.perform(post("/api/pages/{p}/doodles", page.id())
                .cookie(new Cookie("XSRF-TOKEN", CSRF_TOKEN)).header("X-XSRF-TOKEN", CSRF_TOKEN)
                .with(authentication(UsernamePasswordAuthenticationToken.authenticated(
                        new SessionUser(user.getId()), null, List.of(new SimpleGrantedAuthority("ROLE_USER")))))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private JsonNode read(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private String chatkhu(String content) {
        return json.createObjectNode().set("choices", json.createArrayNode().add(json.createObjectNode()
                .set("message", json.createObjectNode().put("role", "assistant").put("content", content))))
                .toString();
    }
}
