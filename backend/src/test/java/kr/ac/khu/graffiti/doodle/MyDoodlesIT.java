package kr.ac.khu.graffiti.doodle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import kr.ac.khu.graffiti.auth.SessionUser;
import kr.ac.khu.graffiti.page.FreshPage;
import kr.ac.khu.graffiti.page.PageCommand;
import kr.ac.khu.graffiti.page.PageCommandProcessor;
import kr.ac.khu.graffiti.page.PageSnapshot;
import kr.ac.khu.graffiti.page.event.DoodleRemoved;
import kr.ac.khu.graffiti.page.event.OccupancyChanged;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** The author's own doodles against MySQL (08, D-13): listing, deleting one, deleting several. */
@Import(MyDoodlesIT.Removed.class)
class MyDoodlesIT extends WebIntegrationTest {

    private static final String CSRF_TOKEN = "test-csrf-token";
    // Crosses the border of chunks 0 and 1 (x = 480).
    private static final String ACROSS = """
            {"kind": "DRAWING", "payload": {"v": 1, "strokes": [{"c": 3, "w": 8, "p": [470, 10, 490, 12]}]}}""";
    private static final String FAR = """
            {"kind": "TEXT", "payload": {"v": 1, "text": "안녕", "c": 0, "size": 24, "x": 2000, "y": 900},
             "bbox": {"w": 48, "h": 33}}""";

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
    private PageCommandProcessor processor;

    @Autowired
    private Removed removed;

    private PageSnapshot page;
    private User author;
    private User other;

    @BeforeEach
    void startOnFreshPage() {
        page = FreshPage.start(context, clock);
        author = newUser();
        other = newUser();
        removed.clear();
    }

    @Test
    void theListHoldsOnlyTheAuthorsPublishedDoodlesOfThePage() throws Exception {
        long first = publish(author, ACROSS);
        long second = publish(author, FAR);
        publish(other, ACROSS);

        JsonNode body = read(mvc.perform(get("/api/me/doodles").param("pageId", Long.toString(page.id()))
                .with(authentication(session(author)))).andExpect(status().isOk()));

        assertThat(body.get("pageId").asText()).isEqualTo(Long.toString(page.id()));
        assertThat(body.get("doodles").findValuesAsText("id"))
                .containsExactly(Long.toString(first), Long.toString(second));
        assertThat(body.at("/doodles/1/bbox").toString()).isEqualTo("{\"x\":2000,\"y\":900,\"w\":48,\"h\":33}");
        assertThat(body.toString()).doesNotContain("author").doesNotContain("payload");
    }

    @Test
    void theListNeedsALoginAndTheCurrentPage() throws Exception {
        mvc.perform(get("/api/me/doodles").param("pageId", Long.toString(page.id())))
                .andExpect(status().isUnauthorized());
        User fresh = accounts.loginOrRegister("sub-" + UUID.randomUUID(), "n-" + UUID.randomUUID() + "@khu.ac.kr");
        mvc.perform(get("/api/me/doodles").param("pageId", Long.toString(page.id()))
                .with(authentication(session(fresh))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("CONSENT_REQUIRED"));
        mvc.perform(get("/api/me/doodles").param("pageId", Long.toString(page.id() + 1000))
                .with(authentication(session(author))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("PAGE_ENDED"));
        for (String bad : List.of("", "abc", "-1", "1.5")) {
            mvc.perform(get("/api/me/doodles").param("pageId", bad).with(authentication(session(author))))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.details.field").value("pageId"));
        }
        mvc.perform(get("/api/me/doodles").with(authentication(session(author))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.details.reason").value("required"));
    }

    @Test
    void deletingTakesTheDoodleOffTheCanvasAndKeepsTheRow() throws Exception {
        long id = publish(author, ACROSS);
        Object postedAt = lastPostedAt(author);
        removed.clear();

        mvc.perform(withCsrf(delete("/api/doodles/{id}", id)).with(authentication(session(author))))
                .andExpect(status().isNoContent());

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, removed_by, removed_at IS NOT NULL AS stamped FROM doodle WHERE id = ?", id);
        assertThat(row).containsEntry("status", "DELETED").containsEntry("removed_by", author.getId());
        assertThat(((Number) row.get("stamped")).intValue()).isEqualTo(1);
        assertThat(removed.doodles).singleElement().satisfies(e -> {
            assertThat(e.doodleId()).isEqualTo(id);
            assertThat(e.chunks()).containsExactly(0, 1);
        });
        assertThat(removed.cells).singleElement().satisfies(e -> assertThat(e.clear()).isNotEmpty());
        assertThat(occupiedCells()).isZero();
        mvc.perform(get("/api/pages/{p}/doodles", page.id()).param("chunks", "0,1"))
                .andExpect(jsonPath("$.doodles").isEmpty());
        // Deleting gives no post back (INV-08).
        assertThat(lastPostedAt(author)).isEqualTo(postedAt);
    }

    @Test
    void cellsSharedWithAnotherDoodleStayOccupied() throws Exception {
        long mine = publish(author, ACROSS);
        publish(other, ACROSS);
        int both = occupiedCells();

        mvc.perform(withCsrf(delete("/api/doodles/{id}", mine)).with(authentication(session(author))))
                .andExpect(status().isNoContent());

        assertThat(occupiedCells()).isEqualTo(both);
        assertThat(removed.cells).isEmpty();
    }

    @Test
    void onlyTheAuthorMayDeleteAPublishedDoodleOnce() throws Exception {
        long id = publish(author, ACROSS);

        mvc.perform(delete("/api/doodles/{id}", id).with(authentication(session(author))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("CSRF_INVALID"));
        mvc.perform(withCsrf(delete("/api/doodles/{id}", id))).andExpect(status().isUnauthorized());
        mvc.perform(withCsrf(delete("/api/doodles/{id}", id)).with(authentication(session(other))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mvc.perform(withCsrf(delete("/api/doodles/{id}", Long.MAX_VALUE)).with(authentication(session(author))))
                .andExpect(status().isNotFound());
        mvc.perform(withCsrf(delete("/api/doodles/{id}", "x1")).with(authentication(session(author))))
                .andExpect(status().isBadRequest());
        assertThat(statusOf(id)).isEqualTo("PUBLISHED");

        mvc.perform(withCsrf(delete("/api/doodles/{id}", id)).with(authentication(session(author))))
                .andExpect(status().isNoContent());
        mvc.perform(withCsrf(delete("/api/doodles/{id}", id)).with(authentication(session(author))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("STATE_CONFLICT"));
        assertThat(removed.doodles).hasSize(1);
    }

    @Test
    void bannedAuthorsCannotDelete() throws Exception {
        long id = publish(author, ACROSS);
        jdbc.update("UPDATE users SET banned_at = ? WHERE id = ?", LocalDateTime.now(ZoneOffset.UTC), author.getId());

        mvc.perform(withCsrf(delete("/api/doodles/{id}", id)).with(authentication(session(author))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("BANNED"));
        assertThat(statusOf(id)).isEqualTo("PUBLISHED");
    }

    @Test
    void aDoodleOfAnEndedPageCannotBeDeleted() throws Exception {
        long id = publish(author, ACROSS);
        page = FreshPage.start(context, clock);

        mvc.perform(withCsrf(delete("/api/doodles/{id}", id)).with(authentication(session(author))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("PAGE_ENDED"));
        assertThat(statusOf(id)).isEqualTo("PUBLISHED");
    }

    @Test
    void aHeldDoodleIsNotTheAuthorsToDelete() throws Exception {
        long id = publish(author, ACROSS);
        jdbc.update("UPDATE doodle SET status = 'HELD' WHERE id = ?", id);

        mvc.perform(withCsrf(delete("/api/doodles/{id}", id)).with(authentication(session(author))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("STATE_CONFLICT"));
        assertThat(statusOf(id)).isEqualTo("HELD");
    }

    @Test
    void selectedDoodlesAreDeletedTogether() throws Exception {
        long first = publish(author, ACROSS);
        long second = publish(author, FAR);

        mvc.perform(deleteMany(author, "{\"ids\": [\"" + first + "\", \"" + second + "\", \"" + first + "\"]}"))
                .andExpect(status().isNoContent());

        assertThat(statusOf(first)).isEqualTo("DELETED");
        assertThat(statusOf(second)).isEqualTo("DELETED");
        assertThat(removed.doodles).extracting(DoodleRemoved::doodleId).containsExactly(first, second);
        assertThat(occupiedCells()).isZero();
    }

    @Test
    void oneDoodleOfSomeoneElseRefusesTheWholeSelection() throws Exception {
        long mine = publish(author, ACROSS);
        long theirs = publish(other, FAR);

        mvc.perform(deleteMany(author, "{\"ids\": [\"" + mine + "\", \"" + theirs + "\"]}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        assertThat(statusOf(mine)).isEqualTo("PUBLISHED");
        assertThat(statusOf(theirs)).isEqualTo("PUBLISHED");
        assertThat(removed.doodles).isEmpty();
    }

    @Test
    void aSelectionThatChangedMeanwhileIsRolledBack() throws Exception {
        long first = publish(author, ACROSS);
        long second = publish(author, FAR);
        mvc.perform(withCsrf(delete("/api/doodles/{id}", second)).with(authentication(session(author))));
        int before = occupiedCells();
        removed.clear();

        mvc.perform(deleteMany(author, "{\"ids\": [\"" + first + "\", \"" + second + "\"]}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("STATE_CONFLICT"));

        assertThat(statusOf(first)).isEqualTo("PUBLISHED");
        assertThat(occupiedCells()).isEqualTo(before);
        assertThat(removed.doodles).isEmpty();
    }

    @Test
    void theSelectionBodyIsChecked() throws Exception {
        String tooMany = IntStream.rangeClosed(1, 51).mapToObj(i -> "\"" + i + "\"")
                .collect(Collectors.joining(",", "{\"ids\": [", "]}"));
        Map<String, String> bad = Map.of(
                "{\"ids\": []}", "ids",
                "{}", "ids",
                tooMany, "ids",
                "{\"ids\": [1]}", "body",
                "{\"ids\": [\"01\"]}", "ids",
                "{\"ids\": [\"1\"], \"userId\": \"2\"}", "userId",
                "not json", "body");
        for (Map.Entry<String, String> e : bad.entrySet()) {
            mvc.perform(deleteMany(author, e.getKey())).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.details.field").value(e.getValue()));
        }
        mvc.perform(deleteMany(author, tooMany)).andExpect(jsonPath("$.error.details.reason").value("too_many"));
    }

    // --- helpers ---

    private long publish(User user, String body) throws Exception {
        jdbc.update("UPDATE users SET last_posted_at = NULL WHERE id = ?", user.getId());
        mvc.perform(withCsrf(post("/api/pages/{p}/doodles", page.id())).with(authentication(session(user)))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
        return jdbc.queryForObject("SELECT MAX(id) FROM doodle WHERE author_id = ?", Long.class, user.getId());
    }

    private MockHttpServletRequestBuilder deleteMany(User user, String body) {
        return withCsrf(post("/api/doodles/delete")).with(authentication(session(user)))
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private int occupiedCells() {
        byte[] bitmap = processor.submit(new PageCommand.GetMinimap(page.id())).join().bitmap();
        int n = 0;
        for (byte b : bitmap) {
            n += Integer.bitCount(b & 0xFF);
        }
        return n;
    }

    private Object lastPostedAt(User user) {
        return jdbc.queryForObject("SELECT last_posted_at FROM users WHERE id = ?", Object.class, user.getId());
    }

    private String statusOf(long id) {
        return jdbc.queryForObject("SELECT status FROM doodle WHERE id = ?", String.class, id);
    }

    private User newUser() {
        User user = accounts.loginOrRegister("sub-" + UUID.randomUUID(), "m-" + UUID.randomUUID() + "@khu.ac.kr");
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
    static class Removed {

        final List<DoodleRemoved> doodles = new CopyOnWriteArrayList<>();
        final List<OccupancyChanged> cells = new CopyOnWriteArrayList<>();

        @EventListener
        void on(DoodleRemoved event) {
            doodles.add(event);
        }

        @EventListener
        void on(OccupancyChanged event) {
            if (event.set().isEmpty()) {
                cells.add(event);
            }
        }

        void clear() {
            doodles.clear();
            cells.clear();
        }
    }
}
