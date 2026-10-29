package kr.ac.khu.graffiti.doodle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import kr.ac.khu.graffiti.auth.SessionUser;
import kr.ac.khu.graffiti.config.GraffitiProperties;
import kr.ac.khu.graffiti.moderation.ModerationService;
import kr.ac.khu.graffiti.moderation.ModerationState;
import kr.ac.khu.graffiti.page.FreshPage;
import kr.ac.khu.graffiti.page.PageCommand;
import kr.ac.khu.graffiti.page.PageCommandProcessor;
import kr.ac.khu.graffiti.page.PageSnapshot;
import kr.ac.khu.graffiti.page.PageStatus;
import kr.ac.khu.graffiti.page.PostStore;
import kr.ac.khu.graffiti.page.PublishedFootprintSource;
import kr.ac.khu.graffiti.page.event.DoodlePublished;
import kr.ac.khu.graffiti.page.event.OccupancyChanged;
import kr.ac.khu.graffiti.page.event.PageStatusChanged;
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
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** {@code POST /api/pages/{pageId}/doodles} of 06-publish against MySQL. */
@Import(PublishIT.EventLog.class)
class PublishIT extends WebIntegrationTest {

    private static final String CSRF_TOKEN = "test-csrf-token";
    private static final String DRAWING = """
            {"kind": "DRAWING", "payload": {"v": 1, "strokes": [{"c": 3, "w": 8, "p": [470, 10, 490, 12]}]}}""";
    private static final String TEXT = """
            {"kind": "TEXT", "payload": {"v": 1, "text": "안녕", "c": 0, "size": 24, "x": 1500, "y": 900},
             "bbox": {"w": 48, "h": 24}}""";

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
    private PublishedFootprintSource footprints;

    @Autowired
    private EventLog events;

    @MockitoSpyBean
    private ModerationService moderation;

    @MockitoSpyBean
    private PostStore posts;

    private PageSnapshot page;
    private User author;

    @BeforeEach
    void startOnFreshPage() {
        page = FreshPage.start(context, clock);
        author = newAuthor();
        events.clear();
    }

    @Test
    void aDrawingIsStoredWithTheServerBboxAndItsChunks() throws Exception {
        Instant pressed = clock.instant();

        JsonNode body = read(publish(author, DRAWING).andExpect(status().isCreated()));

        String id = body.at("/doodle/id").asText();
        assertThat(body.at("/doodle/kind").asText()).isEqualTo("DRAWING");
        assertThat(json.convertValue(body.at("/doodle/bbox"), Map.class))
                .isEqualTo(Map.of("x", 466, "y", 6, "w", 28, "h", 10));
        assertThat(body.at("/doodle/payload")).isEqualTo(json.readTree(
                "{\"v\": 1, \"strokes\": [{\"c\": 3, \"w\": 8, \"p\": [470, 10, 490, 12]}]}"));
        assertThat(fieldNames(body.get("doodle"))).containsExactlyInAnyOrder("id", "kind", "bbox", "payload");
        assertThat(body.get("cooldownEndsAt").asText()).isEqualTo(pressed.plus(props.postCooldown()).toString());

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM doodle WHERE id = ?", Long.parseLong(id));
        assertThat(row).containsEntry("page_id", page.id())
                .containsEntry("author_id", author.getId())
                .containsEntry("status", "PUBLISHED")
                .containsEntry("moderation_state", "UNCHECKED")
                .containsEntry("bbox_x", 466).containsEntry("bbox_w", 28);
        assertThat(utc(row.get("submitted_at"))).isEqualTo(pressed);
        assertThat(jdbc.queryForList("SELECT chunk_index FROM doodle_chunk WHERE doodle_id = ? ORDER BY chunk_index",
                Integer.class, Long.parseLong(id))).containsExactly(0, 1);
        assertThat(lastPostedAt(author)).isEqualTo(pressed);
    }

    @Test
    void aPublishedDoodleIsAnnouncedToItsChunksAndTheMinimap() throws Exception {
        String id = read(publish(author, TEXT).andExpect(status().isCreated())).at("/doodle/id").asText();

        DoodlePublished published = events.of(DoodlePublished.class).getFirst();
        assertThat(published.doodleId()).isEqualTo(Long.parseLong(id));
        assertThat(published.chunks()).containsExactly(16 + 3);
        assertThat(published.kind()).isEqualTo("TEXT");
        assertThat(events.of(OccupancyChanged.class)).hasSize(1);
        // And the chunk lookup returns it.
        mvc.perform(get("/api/pages/{p}/doodles", page.id()).param("chunks", "19"))
                .andExpect(jsonPath("$.doodles[0].id").value(id))
                .andExpect(jsonPath("$.doodles[0].payload.text").value("안녕"));
    }

    @Test
    void aTextKeepsItsFontAndATextWithoutOneIsStoredWithout() throws Exception {
        String withFont = TEXT.replace("\"y\": 900}", "\"y\": 900, \"f\": \"jalnan\"}");
        String id = read(publish(author, withFont).andExpect(status().isCreated())).at("/doodle/id").asText();
        clock.advance(props.postCooldown().plusSeconds(1));
        String plain = read(publish(author, TEXT.replace("1500", "2500")).andExpect(status().isCreated()))
                .at("/doodle/id").asText();

        assertThat(json.readTree(jdbc.queryForObject("SELECT payload FROM doodle WHERE id = ?", String.class,
                Long.parseLong(id))).get("f").asText()).isEqualTo("jalnan");
        assertThat(json.readTree(jdbc.queryForObject("SELECT payload FROM doodle WHERE id = ?", String.class,
                Long.parseLong(plain))).has("f")).isFalse();
    }

    @Test
    void aSecondPostWithinTheCooldownIsRefusedAndAdminsAreExempt() throws Exception {
        publish(author, DRAWING).andExpect(status().isCreated());
        Instant first = lastPostedAt(author);

        clock.advance(Duration.ofMinutes(29));
        publish(author, TEXT).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("COOLDOWN"))
                .andExpect(jsonPath("$.error.details.retryAfterSec").value(60))
                .andExpect(jsonPath("$.error.message").value("다음 게시까지 1분 남았어요."));
        assertThat(lastPostedAt(author)).isEqualTo(first);

        clock.advance(Duration.ofMinutes(1));
        publish(author, TEXT).andExpect(status().isCreated());

        User admin = newAuthor();
        jdbc.update("UPDATE users SET role = 'ADMIN' WHERE id = ?", admin.getId());
        publish(admin, DRAWING).andExpect(status().isCreated()).andExpect(jsonPath("$.cooldownEndsAt").isEmpty());
        publish(admin, TEXT).andExpect(status().isCreated());
        assertThat(lastPostedAt(admin)).isNull();
    }

    @Test
    void meReportsTheCooldown() throws Exception {
        publish(author, DRAWING).andExpect(status().isCreated());

        mvc.perform(get("/api/me").with(authentication(session(author))))
                .andExpect(jsonPath("$.cooldownEndsAt").value(clock.instant().plus(props.postCooldown()).toString()));
    }

    @Test
    void aPageThatTurnsDuringModerationGivesThePostBack() throws Exception {
        publish(author, DRAWING).andExpect(status().isCreated());
        Instant earlier = lastPostedAt(author);
        clock.advance(props.postCooldown());
        doAnswer(inv -> {
            clock.set(page.scheduledEndAt().plusSeconds(1));
            processor.submit(new PageCommand.EndPage(page.id())).join();
            return inv.callRealMethod();
        }).when(moderation).moderate(anyString(), any());

        publish(author, TEXT).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("PAGE_ENDED"));

        assertThat(lastPostedAt(author)).isEqualTo(earlier);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM doodle WHERE author_id = ?", Integer.class,
                author.getId())).isEqualTo(1);
    }

    @Test
    void aFailedModerationGivesThePostBack() throws Exception {
        doThrow(new IllegalStateException("provider down")).when(moderation).moderate(anyString(), any());

        publish(author, DRAWING).andExpect(status().isInternalServerError());

        assertThat(lastPostedAt(author)).isNull();
    }

    @Test
    void aHeldDoodleIsStoredOffTheCanvasAndKeepsTheCooldown() throws Exception {
        Instant pressed = clock.instant();
        doReturn(new ModerationService.Verdict(ModerationState.HELD, "{\"checks\": []}", null))
                .when(moderation).moderate(anyString(), any());

        JsonNode body = read(publish(author, DRAWING).andExpect(status().isAccepted()));

        assertThat(fieldNames(body)).containsExactlyInAnyOrder("held", "cooldownEndsAt");
        assertThat(body.get("held").asBoolean()).isTrue();
        assertThat(body.get("cooldownEndsAt").asText()).isEqualTo(pressed.plus(props.postCooldown()).toString());
        assertThat(lastPostedAt(author)).isEqualTo(pressed);

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM doodle WHERE author_id = ?", author.getId());
        assertThat(row).containsEntry("status", "HELD").containsEntry("moderation_state", "HELD");
        assertThat(row.get("published_at")).isNull();
        long id = ((Number) row.get("id")).longValue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM doodle_chunk WHERE doodle_id = ?", Integer.class, id))
                .isPositive();
        assertThat(jdbc.queryForMap("SELECT actor_type, action, target_user_id FROM moderation_action "
                + "WHERE target_doodle_id = ?", id)).containsEntry("actor_type", "AI").containsEntry("action", "HOLD")
                .containsEntry("target_user_id", author.getId());

        assertThat(events.of(Object.class)).isEmpty();
        assertThat(processor.submit(new PageCommand.GetPageSnapshot()).join().occupancyPermille()).isZero();
        mvc.perform(get("/api/pages/{p}/doodles", page.id()).param("chunks", "0"))
                .andExpect(jsonPath("$.doodles").isEmpty());
    }

    @Test
    void aRejectedDoodleIsRefusedWithItsCategoryAndKeepsTheCooldown() throws Exception {
        Instant pressed = clock.instant();
        doReturn(new ModerationService.Verdict(ModerationState.REJECTED, "{\"checks\": []}", "PROFANITY"))
                .when(moderation).moderate(anyString(), any());

        publish(author, TEXT).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("MODERATION_REJECTED"))
                .andExpect(jsonPath("$.error.details.category").value("PROFANITY"))
                .andExpect(jsonPath("$.error.details.cooldownEndsAt")
                        .value(pressed.plus(props.postCooldown()).toString()));

        assertThat(lastPostedAt(author)).isEqualTo(pressed);
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM doodle WHERE author_id = ?", author.getId());
        assertThat(row).containsEntry("status", "REJECTED").containsEntry("moderation_state", "REJECTED");
        assertThat(jdbc.queryForObject("SELECT action FROM moderation_action WHERE target_doodle_id = ?",
                String.class, row.get("id"))).isEqualTo("REJECT");
        assertThat(events.of(Object.class)).isEmpty();
    }

    @Test
    void aTextWithProfanityIsPublishedShrunkAndKeepsItsOriginalSizeInTheDetail() throws Exception {
        // The word list (resources/moderation) has 시발; the AI checks are stubs here (unchecked=PUBLISH).
        String profane = """
                {"kind": "TEXT", "payload": {"v": 1, "text": "시발 시험", "c": 0, "size": 24, "x": 1500, "y": 900},
                 "bbox": {"w": 115, "h": 33}}""";

        JsonNode body = read(publish(author, profane).andExpect(status().isCreated()));

        assertThat(body.get("shrunk").asBoolean()).isTrue();
        assertThat(body.at("/doodle/payload/size").asInt()).isEqualTo(10);
        assertThat(json.convertValue(body.at("/doodle/bbox"), Map.class))
                .isEqualTo(Map.of("x", 1500, "y", 900, "w", 48, "h", 14));
        long id = body.at("/doodle/id").asLong();
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM doodle WHERE id = ?", id);
        assertThat(row).containsEntry("status", "PUBLISHED").containsEntry("moderation_state", "UNCHECKED")
                .containsEntry("bbox_w", 48).containsEntry("bbox_h", 14);
        assertThat(json.readTree(row.get("payload").toString()).get("size").asInt()).isEqualTo(10);
        assertThat(json.readTree(row.get("moderation_detail").toString()).at("/shrunk/fromSize").asInt())
                .isEqualTo(24);
        assertThat(events.of(DoodlePublished.class).getFirst().doodleId()).isEqualTo(id);
    }

    @Test
    void aPhoneNumberIsRejectedAsPersonalInformation() throws Exception {
        String phone = """
                {"kind": "TEXT", "payload": {"v": 1, "text": "010 1234 5678", "c": 0, "size": 10, "x": 0, "y": 0},
                 "bbox": {"w": 130, "h": 14}}""";

        publish(author, phone).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.details.category").value("PERSONAL_INFO"));

        assertThat(jdbc.queryForObject("SELECT status FROM doodle WHERE author_id = ?", String.class,
                author.getId())).isEqualTo("REJECTED");
    }

    @Test
    void aPageThatTurnsBeforeAHoldIsStoredGivesThePostBack() throws Exception {
        doAnswer(inv -> {
            clock.set(page.scheduledEndAt().plusSeconds(1));
            processor.submit(new PageCommand.EndPage(page.id())).join();
            return new ModerationService.Verdict(ModerationState.HELD, null, null);
        }).when(moderation).moderate(anyString(), any());

        publish(author, TEXT).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("PAGE_ENDED"));

        assertThat(lastPostedAt(author)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM doodle WHERE author_id = ?", Integer.class,
                author.getId())).isZero();
    }

    @Test
    void aFailedHoldInsertGivesThePostBack() throws Exception {
        doReturn(new ModerationService.Verdict(ModerationState.HELD, null, null))
                .when(moderation).moderate(anyString(), any());
        doThrow(new DataAccessResourceFailureException("db down")).when(posts).recordAction(any(), any());

        publish(author, DRAWING).andExpect(status().isInternalServerError());

        assertThat(lastPostedAt(author)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM doodle WHERE author_id = ?", Integer.class,
                author.getId())).isZero();
    }

    @Test
    void aFailedInsertLeavesThePageAsItWasAndGivesThePostBack() throws Exception {
        doThrow(new DataAccessResourceFailureException("db down")).when(posts).insertPublished(any(), any());

        publish(author, DRAWING).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL"));

        assertThat(lastPostedAt(author)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM doodle WHERE author_id = ?", Integer.class,
                author.getId())).isZero();
        assertThat(processor.submit(new PageCommand.GetPageSnapshot()).join().occupancyPermille()).isZero();
        assertThat(events.of(Object.class)).isEmpty();
    }

    @Test
    void anEndedPageTakesNoPost() throws Exception {
        long ended = page.id();
        page = FreshPage.start(context, clock);

        publish(author, DRAWING, ended).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PAGE_ENDED"));
        assertThat(lastPostedAt(author)).isNull();
    }

    @Test
    void invalidDoodlesAreRefusedWithoutTakingThePost() throws Exception {
        List<String[]> cases = List.of(
                bad("{\"kind\": \"DRAWING\", \"payload\": {\"v\": 1, \"strokes\": []}}", "strokes"),
                bad("{\"kind\": \"DRAWING\", \"payload\": {\"v\": 1, \"strokes\": [{\"c\": 0, \"w\": 2, \"p\": [1, 2, 3]}]}}", "p"),
                bad("{\"kind\": \"DRAWING\", \"payload\": {\"v\": 1, \"strokes\": [{\"c\": 0, \"w\": 2, \"p\": [7680, 2]}]}}", "p"),
                bad("{\"kind\": \"DRAWING\", \"payload\": {\"v\": 1, \"strokes\": [{\"c\": 12, \"w\": 2, \"p\": [1, 2]}]}}", "c"),
                bad("{\"kind\": \"DRAWING\", \"payload\": {\"v\": 1, \"strokes\": [{\"c\": 0, \"w\": 17, \"p\": [1, 2]}]}}", "w"),
                bad("{\"kind\": \"DRAWING\", \"payload\": {\"v\": 1, \"strokes\": [{\"c\": 0, \"w\": 2, \"p\": [0, 0, 1100, 0]}]}}", "strokes"),
                bad("{\"kind\": \"DRAWING\", \"payload\": {\"v\": 1, \"strokes\": [{\"c\": 0, \"w\": 2, \"p\": [1.5, 2]}]}}", "body"),
                bad("{\"kind\": \"DRAWING\", \"payload\": {\"v\": 1, \"strokes\": [{\"c\": \"0\", \"w\": 2, \"p\": [1, 2]}]}}", "body"),
                bad("{\"kind\": \"DRAWING\", \"payload\": {\"v\": 1, \"strokes\": [{\"c\": 0, \"w\": 2, \"p\": [1, 2], \"z\": 1}]}}", "z"),
                bad("{\"kind\": \"DRAWING\", \"payload\": {\"v\": 1, \"strokes\": [{\"c\": 0, \"w\": 2, \"p\": [1, 2]}]}, \"bbox\": {\"w\": 1, \"h\": 1}}", "bbox"),
                bad("{\"kind\": \"DRAWING\", \"userId\": 1, \"payload\": {\"v\": 1, \"strokes\": [{\"c\": 0, \"w\": 2, \"p\": [1, 2]}]}}", "userId"),
                bad("{\"kind\": \"IMAGE\", \"payload\": {}}", "kind"),
                bad("{\"kind\": \"TEXT\", \"payload\": {\"v\": 1, \"text\": \"\", \"c\": 0, \"size\": 24, \"x\": 0, \"y\": 0}, \"bbox\": {\"w\": 1, \"h\": 24}}", "text"),
                bad("{\"kind\": \"TEXT\", \"payload\": {\"v\": 1, \"text\": \"" + "가".repeat(51) + "\", \"c\": 0, \"size\": 10, \"x\": 0, \"y\": 0}, \"bbox\": {\"w\": 510, \"h\": 10}}", "text"),
                bad("{\"kind\": \"TEXT\", \"payload\": {\"v\": 1, \"text\": \"   \", \"c\": 0, \"size\": 24, \"x\": 0, \"y\": 0}, \"bbox\": {\"w\": 72, \"h\": 24}}", "text"),
                bad("{\"kind\": \"TEXT\", \"payload\": {\"v\": 1, \"text\": \"a\\u0007\", \"c\": 0, \"size\": 24, \"x\": 0, \"y\": 0}, \"bbox\": {\"w\": 48, \"h\": 24}}", "text"),
                bad("{\"kind\": \"TEXT\", \"payload\": {\"v\": 1, \"text\": 5, \"c\": 0, \"size\": 24, \"x\": 0, \"y\": 0}, \"bbox\": {\"w\": 48, \"h\": 24}}", "body"),
                bad("{\"kind\": \"TEXT\", \"payload\": {\"v\": 1, \"text\": \"ab\", \"c\": 0, \"size\": 25, \"x\": 0, \"y\": 0}, \"bbox\": {\"w\": 48, \"h\": 24}}", "size"),
                bad("{\"kind\": \"TEXT\", \"payload\": {\"v\": 1, \"text\": \"ab\", \"c\": 0, \"size\": 24, \"x\": 0, \"y\": 0}, \"bbox\": {\"w\": 61, \"h\": 24}}", "bbox"),
                bad("{\"kind\": \"TEXT\", \"payload\": {\"v\": 1, \"text\": \"ab\", \"c\": 0, \"size\": 24, \"x\": 0, \"y\": 0, \"f\": \"papyrus\"}, \"bbox\": {\"w\": 48, \"h\": 24}}", "f"),
                bad("{\"kind\": \"TEXT\", \"payload\": {\"v\": 1, \"text\": \"ab\", \"c\": 0, \"size\": 24, \"x\": 0, \"y\": 0}}", "bbox"),
                bad("{\"kind\": \"TEXT\", \"kind\": \"TEXT\"}", "body"),
                bad("not json", "body"));
        for (String[] c : cases) {
            publish(author, c[0]).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.error.details.field").value(c[1]));
        }
        assertThat(lastPostedAt(author)).isNull();
    }

    @Test
    void aBodyOverTwoMegabytesIsRefused() throws Exception {
        int max = props.doodle().maxPayloadBytes();
        String points = IntStream.range(0, max / 2 + 1).mapToObj(i -> "1").collect(Collectors.joining(","));
        String body = "{\"kind\": \"DRAWING\", \"payload\": {\"v\": 1, \"strokes\": [{\"c\": 0, \"w\": 2, \"p\": ["
                + points + "]}]}}";
        assertThat(body.length()).isGreaterThan(max);

        publish(author, body).andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error.code").value("PAYLOAD_TOO_LARGE"));
        assertThat(lastPostedAt(author)).isNull();
    }

    @Test
    void onlyAuthorsMayPublish() throws Exception {
        mvc.perform(withCsrf(post("/api/pages/{p}/doodles", page.id())).contentType(MediaType.APPLICATION_JSON)
                .content(DRAWING)).andExpect(status().isUnauthorized());

        User unconsented = accounts.loginOrRegister("sub-" + UUID.randomUUID(), "p-" + UUID.randomUUID() + "@khu.ac.kr");
        publish(unconsented, DRAWING).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("CONSENT_REQUIRED"));

        jdbc.update("UPDATE users SET banned_at = ? WHERE id = ?", LocalDateTime.now(ZoneOffset.UTC), author.getId());
        publish(author, DRAWING).andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("BANNED"));

        User other = newAuthor();
        mvc.perform(post("/api/pages/{p}/doodles", page.id()).with(authentication(session(other)))
                .contentType(MediaType.APPLICATION_JSON).content(DRAWING)).andExpect(status().isForbidden());
        assertThat(lastPostedAt(other)).isNull();
    }

    @Test
    void reachingTheThresholdStartsTheCountdown() throws Exception {
        // Text boxes of 1024 x 960 from an admin (no cooldown); 28 of them would cover 83% of the canvas.
        User admin = newAuthor();
        jdbc.update("UPDATE users SET role = 'ADMIN' WHERE id = ?", admin.getId());
        int published = 0;
        outer:
        for (int y = 0; y + 960 <= 4320; y += 960) {
            for (int x = 0; x + 1024 <= 7680; x += 1024) {
                String text = "{\"kind\": \"TEXT\", \"payload\": {\"v\": 1, \"text\": \"" + "가".repeat(11)
                        + "\\n".repeat(9) + "\", \"c\": 0, \"size\": 96, \"x\": " + x + ", \"y\": " + y
                        + "}, \"bbox\": {\"w\": 1024, \"h\": 960}}";
                JsonNode status = read(mvc.perform(get("/api/pages/current")));
                if ("COUNTDOWN".equals(status.get("status").asText())) {
                    break outer;
                }
                publish(admin, text).andExpect(status().isCreated());
                published++;
            }
        }

        assertThat(read(mvc.perform(get("/api/pages/current"))).get("status").asText()).isEqualTo("COUNTDOWN");
        assertThat(events.of(PageStatusChanged.class)).extracting(PageStatusChanged::status)
                .containsExactly(PageStatus.COUNTDOWN);
        assertThat(jdbc.queryForObject("SELECT status FROM page WHERE id = ?", String.class, page.id()))
                .isEqualTo("COUNTDOWN");
        assertThat(published).isBetween(20, 28);
    }

    @Test
    void recoveryRebuildsTheOccupancyFromPublishedDoodles() throws Exception {
        publish(author, DRAWING).andExpect(status().isCreated());
        User second = newAuthor();
        publish(second, TEXT).andExpect(status().isCreated());
        byte[] before = processor.submit(new PageCommand.GetMinimap(page.id())).join().bitmap();

        PageSnapshot recovered = FreshPage.recover(context);

        assertThat(recovered.id()).isEqualTo(page.id());
        assertThat(processor.submit(new PageCommand.GetMinimap(page.id())).join().bitmap()).isEqualTo(before);
        assertThat(footprints.load(page.id(), page.geometry())).hasSize(2);
    }

    private ResultActions publish(User user, String body) throws Exception {
        return publish(user, body, page.id());
    }

    private ResultActions publish(User user, String body, long pageId) throws Exception {
        return mvc.perform(withCsrf(post("/api/pages/{p}/doodles", pageId)).with(authentication(session(user)))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private User newAuthor() {
        User user = accounts.loginOrRegister("sub-" + UUID.randomUUID(), "w-" + UUID.randomUUID() + "@khu.ac.kr");
        accounts.consent(user.getId());
        return user;
    }

    private Instant lastPostedAt(User user) {
        return utc(jdbc.queryForObject("SELECT last_posted_at FROM users WHERE id = ?", LocalDateTime.class,
                user.getId()));
    }

    private static Instant utc(Object time) {
        return time == null ? null : ((LocalDateTime) time).toInstant(ZoneOffset.UTC);
    }

    private static String[] bad(String body, String field) {
        return new String[] {body, field};
    }

    private static UsernamePasswordAuthenticationToken session(User user) {
        return UsernamePasswordAuthenticationToken.authenticated(new SessionUser(user.getId()), null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    // A real double-submit token (see AuthorizationIT).
    private static MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request) {
        return request.cookie(new Cookie("XSRF-TOKEN", CSRF_TOKEN)).header("X-XSRF-TOKEN", CSRF_TOKEN);
    }

    private JsonNode read(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    @TestComponent
    static class EventLog {

        private final List<Object> received = new CopyOnWriteArrayList<>();

        @EventListener({DoodlePublished.class, OccupancyChanged.class, PageStatusChanged.class})
        void on(Object event) {
            received.add(event);
        }

        void clear() {
            received.clear();
        }

        <T> List<T> of(Class<T> type) {
            return received.stream().filter(type::isInstance).map(type::cast).toList();
        }
    }
}
