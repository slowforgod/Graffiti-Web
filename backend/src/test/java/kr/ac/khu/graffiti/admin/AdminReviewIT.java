package kr.ac.khu.graffiti.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import kr.ac.khu.graffiti.auth.SessionUser;
import kr.ac.khu.graffiti.config.GraffitiProperties;
import kr.ac.khu.graffiti.moderation.ModerationService;
import kr.ac.khu.graffiti.moderation.ModerationState;
import kr.ac.khu.graffiti.page.FreshPage;
import kr.ac.khu.graffiti.page.PageCommand;
import kr.ac.khu.graffiti.page.PageCommandProcessor;
import kr.ac.khu.graffiti.page.PageSnapshot;
import kr.ac.khu.graffiti.page.event.DoodlePublished;
import kr.ac.khu.graffiti.support.MutableClock;
import kr.ac.khu.graffiti.support.WebIntegrationTest;
import kr.ac.khu.graffiti.user.User;
import kr.ac.khu.graffiti.user.UserAccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** The review part of {@code /api/admin/**} against MySQL: listing, publishing and rejecting withheld doodles. */
@Import(AdminReviewIT.Published.class)
class AdminReviewIT extends WebIntegrationTest {

    private static final String CSRF_TOKEN = "test-csrf-token";
    private static final String DRAWING = """
            {"kind": "DRAWING", "payload": {"v": 1, "strokes": [{"c": 3, "w": 8, "p": [470, 10, 490, 12]}]}}""";

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

    @Autowired
    private GraffitiProperties props;

    @Autowired
    private PageCommandProcessor processor;

    @Autowired
    private Published published;

    @MockitoSpyBean
    private ModerationService moderation;

    private PageSnapshot page;
    private User author;
    private User admin;

    @BeforeEach
    void startOnFreshPage() {
        page = FreshPage.start(context, clock);
        author = newUser();
        admin = newUser();
        jdbc.update("UPDATE users SET role = 'ADMIN' WHERE id = ?", admin.getId());
        published.received.clear();
    }

    @Test
    void heldDoodlesAreListedOldestFirstWithTheirAuthor() throws Exception {
        long first = withheld(ModerationState.HELD);
        clock.advance(props.postCooldown());
        long second = withheld(ModerationState.HELD);

        JsonNode body = read(mvc.perform(get("/api/admin/doodles").param("moderation", "HELD").param("size", "100")
                .with(authentication(session(admin)))).andExpect(status().isOk()));

        List<String> ids = body.get("items").findValuesAsText("id");
        assertThat(ids.indexOf(Long.toString(first))).isLessThan(ids.indexOf(Long.toString(second)));
        JsonNode item = itemWithId(body, first);
        assertThat(item.at("/author/email").asText()).isEqualTo(author.getEmail());
        assertThat(item.at("/author/nickname").asText()).isEqualTo(author.getNickname());
        assertThat(item.get("status").asText()).isEqualTo("HELD");
        assertThat(item.get("pageEnded").asBoolean()).isFalse();
        assertThat(item.at("/payload/strokes/0/c").asInt()).isEqualTo(3);
        assertThat(item.at("/moderationDetail/checks").isArray()).isTrue();
        assertThat(body.get("total").asLong()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void onlyAdminsMayReview() throws Exception {
        long id = withheld(ModerationState.HELD);

        mvc.perform(get("/api/admin/doodles")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/doodles").with(authentication(session(author))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mvc.perform(withCsrf(post("/api/admin/doodles/{id}/restore", id)).with(authentication(session(author))))
                .andExpect(status().isForbidden());
        mvc.perform(withCsrf(post("/api/admin/doodles/{id}/reject", id)).with(authentication(session(author))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/doodles/{id}/restore", id).with(authentication(session(admin))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("CSRF_INVALID"));
        mvc.perform(get("/api/admin/actions").with(authentication(session(author)))).andExpect(status().isForbidden());

        assertThat(statusOf(id)).isEqualTo("HELD");
    }

    @Test
    void publishingAHeldDoodlePutsItOnTheCanvasAndRecordsTheAdmin() throws Exception {
        long id = withheld(ModerationState.HELD);

        JsonNode body = read(mvc.perform(withCsrf(post("/api/admin/doodles/{id}/restore", id))
                .with(authentication(session(admin)))).andExpect(status().isOk()));

        assertThat(body.at("/doodle/id").asText()).isEqualTo(Long.toString(id));
        assertThat(statusOf(id)).isEqualTo("PUBLISHED");
        assertThat(jdbc.queryForObject("SELECT published_at IS NOT NULL FROM doodle WHERE id = ?", Boolean.class, id))
                .isTrue();
        assertThat(published.received).singleElement().satisfies(e -> {
            assertThat(e.doodleId()).isEqualTo(id);
            assertThat(e.chunks()).containsExactly(0, 1);
        });
        assertThat(processor.submit(new PageCommand.GetPageSnapshot()).join().occupancyPermille()).isPositive();
        assertThat(lastAction(id)).containsEntry("actor_type", "ADMIN").containsEntry("action", "APPROVE")
                .containsEntry("actor_id", admin.getId());
        mvc.perform(get("/api/pages/{p}/doodles", page.id()).param("chunks", "0"))
                .andExpect(jsonPath("$.doodles[0].id").value(Long.toString(id)));
    }

    @Test
    void aRejectedDoodleCanBeRestored() throws Exception {
        long id = withheld(ModerationState.REJECTED);

        mvc.perform(withCsrf(post("/api/admin/doodles/{id}/restore", id)).with(authentication(session(admin))))
                .andExpect(status().isOk());

        assertThat(statusOf(id)).isEqualTo("PUBLISHED");
        assertThat(lastAction(id)).containsEntry("action", "RESTORE");
    }

    @Test
    void aDoodleOfAnEndedPageStaysHeld() throws Exception {
        long id = withheld(ModerationState.HELD);
        page = FreshPage.start(context, clock);

        mvc.perform(withCsrf(post("/api/admin/doodles/{id}/restore", id)).with(authentication(session(admin))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("PAGE_ENDED"));

        assertThat(statusOf(id)).isEqualTo("HELD");
        assertThat(published.received).isEmpty();
        JsonNode body = read(mvc.perform(get("/api/admin/doodles").param("size", "100")
                .with(authentication(session(admin)))));
        assertThat(itemWithId(body, id).get("pageEnded").asBoolean()).isTrue();
    }

    @Test
    void rejectingAHeldDoodleKeepsItOffTheCanvas() throws Exception {
        long id = withheld(ModerationState.HELD);

        mvc.perform(withCsrf(post("/api/admin/doodles/{id}/reject", id)).with(authentication(session(admin))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));

        assertThat(statusOf(id)).isEqualTo("REJECTED");
        assertThat(lastAction(id)).containsEntry("actor_type", "ADMIN").containsEntry("action", "REJECT");
        mvc.perform(withCsrf(post("/api/admin/doodles/{id}/reject", id)).with(authentication(session(admin))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("STATE_CONFLICT"));
    }

    @Test
    void aPublishedDoodleCannotBePublishedAgain() throws Exception {
        long id = withheld(ModerationState.HELD);
        mvc.perform(withCsrf(post("/api/admin/doodles/{id}/restore", id)).with(authentication(session(admin))))
                .andExpect(status().isOk());

        mvc.perform(withCsrf(post("/api/admin/doodles/{id}/restore", id)).with(authentication(session(admin))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("STATE_CONFLICT"));
        mvc.perform(withCsrf(post("/api/admin/doodles/{id}/restore", Long.MAX_VALUE))
                .with(authentication(session(admin)))).andExpect(status().isNotFound());
        assertThat(published.received).hasSize(1);
    }

    @Test
    void theActionLogListsTheNewestFirst() throws Exception {
        long id = withheld(ModerationState.HELD);
        mvc.perform(withCsrf(post("/api/admin/doodles/{id}/reject", id)).with(authentication(session(admin))))
                .andExpect(status().isOk());

        JsonNode items = read(mvc.perform(get("/api/admin/actions").param("size", "2")
                .with(authentication(session(admin)))).andExpect(status().isOk())).get("items");

        assertThat(items.get(0).get("action").asText()).isEqualTo("REJECT");
        assertThat(items.get(0).get("actorType").asText()).isEqualTo("ADMIN");
        assertThat(items.get(1).get("action").asText()).isEqualTo("HOLD");
        assertThat(items.get(1).get("targetDoodleId").asText()).isEqualTo(Long.toString(id));
    }

    @Test
    void listParametersAreChecked() throws Exception {
        for (Map.Entry<String, String> bad : List.of(Map.entry("moderation", "PUBLISHED"), Map.entry("size", "0"),
                Map.entry("size", "101"), Map.entry("page", "-1"))) {
            mvc.perform(get("/api/admin/doodles").param(bad.getKey(), bad.getValue())
                    .with(authentication(session(admin)))).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.details.field").value(bad.getKey()));
        }
    }

    // Publishes as the author with moderation answering {@code state}; returns the stored doodle's id.
    private long withheld(ModerationState state) throws Exception {
        doReturn(new ModerationService.Verdict(state, "{\"checks\": []}", state == ModerationState.REJECTED
                ? "PROFANITY" : null)).when(moderation).moderate(anyString(), any());
        jdbc.update("UPDATE users SET last_posted_at = NULL WHERE id = ?", author.getId());
        mvc.perform(withCsrf(post("/api/pages/{p}/doodles", page.id())).with(authentication(session(author)))
                .contentType(MediaType.APPLICATION_JSON).content(DRAWING));
        return jdbc.queryForObject("SELECT MAX(id) FROM doodle WHERE author_id = ?", Long.class, author.getId());
    }

    private String statusOf(long id) {
        return jdbc.queryForObject("SELECT status FROM doodle WHERE id = ?", String.class, id);
    }

    private Map<String, Object> lastAction(long doodleId) {
        return jdbc.queryForMap("SELECT actor_type, actor_id, action FROM moderation_action "
                + "WHERE target_doodle_id = ? ORDER BY id DESC LIMIT 1", doodleId);
    }

    private JsonNode itemWithId(JsonNode body, long id) {
        for (JsonNode item : body.get("items")) {
            if (item.get("id").asText().equals(Long.toString(id))) {
                return item;
            }
        }
        throw new AssertionError("doodle " + id + " is not listed");
    }

    private User newUser() {
        User user = accounts.loginOrRegister("sub-" + UUID.randomUUID(), "a-" + UUID.randomUUID() + "@khu.ac.kr");
        accounts.consent(user.getId());
        return user;
    }

    private JsonNode read(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private static MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request) {
        return request.cookie(new Cookie("XSRF-TOKEN", CSRF_TOKEN)).header("X-XSRF-TOKEN", CSRF_TOKEN);
    }

    private static UsernamePasswordAuthenticationToken session(User user) {
        return UsernamePasswordAuthenticationToken.authenticated(new SessionUser(user.getId()), null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    @TestComponent
    static class Published {

        final List<DoodlePublished> received = new CopyOnWriteArrayList<>();

        @EventListener
        void on(DoodlePublished event) {
            received.add(event);
        }
    }
}
