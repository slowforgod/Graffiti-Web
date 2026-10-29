package kr.ac.khu.graffiti.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.WebSocketContainer;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import kr.ac.khu.graffiti.doodle.DoodleValidator;
import kr.ac.khu.graffiti.doodle.PublishService;
import kr.ac.khu.graffiti.page.Footprint;
import kr.ac.khu.graffiti.page.FreshPage;
import kr.ac.khu.graffiti.page.PageCommand;
import kr.ac.khu.graffiti.page.PageCommandProcessor;
import kr.ac.khu.graffiti.page.PageSnapshot;
import kr.ac.khu.graffiti.support.MutableClock;
import kr.ac.khu.graffiti.support.WebIntegrationTest;
import kr.ac.khu.graffiti.user.User;
import kr.ac.khu.graffiti.user.UserAccountService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.converter.CompositeMessageConverter;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/** STOMP over a real socket with several clients (04-realtime §8). */
class RealtimeIT extends WebIntegrationTest {

    private static final String ORIGIN = "http://localhost:5173";
    private static final long WAIT_SECONDS = 5;
    // A minimap:diff for most of the page is about 60 KB; the test client must accept it like a browser does.
    private static final int CLIENT_FRAME_LIMIT = 256 * 1024;

    @LocalServerPort
    private int port;

    @Autowired
    private PageCommandProcessor processor;

    @Autowired
    private MutableClock clock;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private UserAccountService accounts;

    @Autowired
    private JdbcTemplate jdbc;

    private final WebSocketStompClient stomp = new WebSocketStompClient(new StandardWebSocketClient(largeFrames()));
    private final ThreadPoolTaskScheduler receiptScheduler = new ThreadPoolTaskScheduler();
    private final List<Client> clients = new ArrayList<>();
    private PageSnapshot page;

    @BeforeEach
    void setUp() {
        receiptScheduler.initialize();
        stomp.setInboundMessageSizeLimit(CLIENT_FRAME_LIMIT);
        stomp.setTaskScheduler(receiptScheduler);
        stomp.setMessageConverter(new CompositeMessageConverter(
                List.of(new StringMessageConverter(), new MappingJackson2MessageConverter())));
        page = FreshPage.start(context, clock);
    }

    @AfterEach
    void tearDown() {
        clients.forEach(c -> {
            if (c.session.isConnected()) {
                try {
                    c.session.disconnect();
                } catch (IllegalStateException | MessageDeliveryException e) {
                    // The server closed it in the meantime (after an ERROR frame).
                }
            }
        });
        receiptScheduler.shutdown();
    }

    // --- connection and subscription checks ---

    @Test
    void sessionsSendTheirBufferBeforeClosing() {
        assertThat(context.getBean("subProtocolWebSocketHandler")).isInstanceOf(FlushBeforeClose.Handler.class);
    }

    @Test
    void handshakeFromAnotherOriginIsRefused() {
        assertThatThrownBy(() -> connect(null, "https://evil.example")).isInstanceOf(ExecutionException.class);
    }

    @Test
    void anonymousConnectionsMaySubscribeToCurrentChunksAndMeta() throws Exception {
        Client anonymous = connect(null, ORIGIN);
        anonymous.subscribe(chunk(0));
        anonymous.subscribe(meta());
        anonymous.subscribe(chunk(143));

        assertThat(anonymous.errors.poll(1, TimeUnit.SECONDS)).isNull();
    }

    @Test
    void subscriptionsOutsideTheRulesAreRefusedWithACode() throws Exception {
        long ended = page.id();
        turnPage();
        Map<String, String> expected = Map.of(
                RealtimeDestinations.chunkTopic(ended, 0), "PAGE_ENDED",
                RealtimeDestinations.metaTopic(ended), "PAGE_ENDED",
                chunk(144), "FORBIDDEN",
                "/topic/page." + page.id() + ".chunk.x", "FORBIDDEN",
                "/topic/anything", "FORBIDDEN",
                "/user/queue/notice", "UNAUTHENTICATED");
        for (Map.Entry<String, String> e : expected.entrySet()) {
            Client anonymous = connect(null, ORIGIN);
            anonymous.trySubscribe(e.getKey());
            assertThat(anonymous.nextError(e.getKey())).as(e.getKey()).isEqualTo(e.getValue());
        }
    }

    @Test
    void theSixtyFirstSubscriptionIsRefused() throws Exception {
        Client anonymous = connect(null, ORIGIN);
        List<StompSession.Subscription> subs = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            subs.add(anonymous.subscribe(chunk(i)));
        }
        subs.getFirst().unsubscribe();
        anonymous.subscribe(chunk(60));
        assertThat(anonymous.errors.poll(1, TimeUnit.SECONDS)).isNull();

        anonymous.trySubscribe(chunk(61));
        assertThat(anonymous.nextError()).isEqualTo("RATE_LIMITED");
    }

    @Test
    void loggedInConnectionsMaySubscribeToTheirQueues() throws Exception {
        Client author = connect(cookie(author()), ORIGIN);
        author.subscribe("/user/queue/notice");
        author.subscribe("/user/queue/errors");
        assertThat(author.errors.poll(1, TimeUnit.SECONDS)).isNull();
    }

    // --- SEND checks ---

    @Test
    void sendNeedsALoggedInConsentedUnbannedAuthor() throws Exception {
        Client anonymous = connect(null, ORIGIN);
        anonymous.send("/app/cursor", cursor(10, 10));
        assertThat(anonymous.nextError()).isEqualTo("UNAUTHENTICATED");

        User noConsent = accounts.loginOrRegister("sub-" + UUID.randomUUID(), "n-" + UUID.randomUUID() + "@khu.ac.kr");
        Client unconsented = connect(cookie(noConsent), ORIGIN);
        unconsented.send("/app/cursor", cursor(10, 10));
        assertThat(unconsented.nextError()).isEqualTo("CONSENT_REQUIRED");

        User banned = author();
        Client bannedClient = connect(cookie(banned), ORIGIN);
        jdbc.update("UPDATE users SET banned_at = ? WHERE id = ?", Timestamp.from(clock.instant()), banned.getId());
        bannedClient.send("/app/cursor", cursor(10, 10));
        assertThat(bannedClient.nextError()).isEqualTo("BANNED");
    }

    @Test
    void clientsCannotPublishStraightToATopic() throws Exception {
        Client watcher = connect(null, ORIGIN);
        BlockingQueue<JsonNode> received = watcher.subscribe(chunk(0)).events;
        Client author = connect(cookie(author()), ORIGIN);

        author.send(chunk(0), Map.of("type", "doodle:removed", "id", "1"));

        assertThat(author.nextError()).isEqualTo("FORBIDDEN");
        assertThat(received.poll(1, TimeUnit.SECONDS)).isNull();
    }

    @Test
    void theTwentyFirstSendInOneSecondIsRefused() throws Exception {
        Client author = connect(cookie(author()), ORIGIN);
        for (int i = 0; i < 20; i++) {
            author.send("/app/cursor", cursor(10 + i, 10));
        }
        assertThat(author.errors.poll(1, TimeUnit.SECONDS)).isNull();

        author.send("/app/cursor", cursor(50, 10));
        assertThat(author.nextError()).isEqualTo("RATE_LIMITED");
    }

    @Test
    void oversizedFramesCloseTheConnection() throws Exception {
        Client author = connect(cookie(author()), ORIGIN);
        try {
            author.send("/app/cursor", Map.of("pageId", Long.toString(page.id()), "pad", "x".repeat(70_000)));
        } catch (MessageDeliveryException e) {
            // The server may close the connection while the frame is still being written: that is the outcome.
        }

        long deadline = System.currentTimeMillis() + WAIT_SECONDS * 1000;
        while (author.session.isConnected() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(author.session.isConnected()).isFalse();
    }

    // --- cursors ---

    @Test
    void cursorsReachTheChunkUnderThemAndLeaveWhenTheyMoveOrDisconnect() throws Exception {
        User user = author();
        Client watcher = connect(null, ORIGIN);
        BlockingQueue<JsonNode> chunk0 = watcher.subscribe(chunk(0)).events;
        BlockingQueue<JsonNode> chunk1 = watcher.subscribe(chunk(1)).events;
        Client author = connect(cookie(user), ORIGIN);

        author.send("/app/cursor", cursor(500, 10));
        JsonNode moved = next(chunk1);
        assertThat(moved.get("type").asText()).isEqualTo("cursor");
        assertThat(moved.get("nickname").asText()).isEqualTo(user.getNickname());
        assertThat(moved.get("color").asText()).isEqualTo(user.getCursorColor());
        assertThat(moved.get("x").asInt()).isEqualTo(500);
        assertThat(moved.get("y").asInt()).isEqualTo(10);
        String sid = moved.get("sid").asText();
        assertThat(sid).startsWith("s-");
        assertThat(moved.properties()).extracting(Map.Entry::getKey)
                .containsExactly("type", "sid", "nickname", "color", "x", "y");

        author.send("/app/cursor", cursor(100, 10));
        assertThat(next(chunk1).toString()).isEqualTo("{\"type\":\"cursor:leave\",\"sid\":\"" + sid + "\"}");
        assertThat(next(chunk0).get("x").asInt()).isEqualTo(100);

        author.session.disconnect();
        assertThat(next(chunk0).get("type").asText()).isEqualTo("cursor:leave");
    }

    @Test
    void aRefusedCursorIsReportedToItsSenderAndKeepsTheConnection() throws Exception {
        Client author = connect(cookie(author()), ORIGIN);
        BlockingQueue<JsonNode> errors = author.subscribe("/user/queue/errors").events;
        Client watcher = connect(null, ORIGIN);
        BlockingQueue<JsonNode> chunk0 = watcher.subscribe(chunk(0)).events;

        author.send("/app/cursor", Map.of("pageId", "999999", "x", 10, "y", 10));
        assertThat(next(errors).get("code").asText()).isEqualTo("PAGE_ENDED");
        author.send("/app/cursor", cursor(-1, 10));
        assertThat(next(errors).get("code").asText()).isEqualTo("VALIDATION_FAILED");
        author.send("/app/cursor", cursor(7680, 10));
        assertThat(next(errors).get("code").asText()).isEqualTo("VALIDATION_FAILED");

        author.send("/app/cursor", cursor(10, 10));
        assertThat(next(chunk0).get("type").asText()).isEqualTo("cursor");
        assertThat(author.errors).isEmpty();
    }

    // --- drafts (05-draft) ---

    @Test
    void draftPointsReachEveryChunkTheyTouchWithoutUserIds() throws Exception {
        Client watcher = connect(null, ORIGIN);
        BlockingQueue<JsonNode> chunk0 = watcher.subscribe(chunk(0)).events;
        BlockingQueue<JsonNode> chunk1 = watcher.subscribe(chunk(1)).events;
        BlockingQueue<JsonNode> chunk16 = watcher.subscribe(chunk(16)).events;
        Client author = connect(cookie(author()), ORIGIN);
        String id = UUID.randomUUID().toString();

        author.send("/app/draft", points(id, 0, 3, 4, 470, 10, 490, 12));

        JsonNode update = next(chunk0);
        assertThat(update.properties()).extracting(Map.Entry::getKey)
                .containsExactly("type", "sid", "draftId", "op", "strokeIndex", "c", "w", "points");
        assertThat(update.get("type").asText()).isEqualTo("draft:update");
        assertThat(update.get("sid").asText()).startsWith("s-");
        assertThat(update.get("draftId").asText()).isEqualTo(id);
        assertThat(update.get("op").asText()).isEqualTo("points");
        assertThat(update.get("points").toString()).isEqualTo("[470,10,490,12]");
        assertThat(next(chunk1)).isEqualTo(update);
        assertThat(chunk16.poll(500, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void aPublishedDoodleReachesEveryChunkItTouchesWithoutItsAuthor() throws Exception {
        Client watcher = connect(null, ORIGIN);
        BlockingQueue<JsonNode> chunk0 = watcher.subscribe(chunk(0)).events;
        BlockingQueue<JsonNode> chunk1 = watcher.subscribe(chunk(1)).events;
        DoodleValidator.Limits limits = new DoodleValidator.Limits(page.geometry(), page.chunkSize(), 12,
                2, 16, List.of(24), 1024, 50, List.of("pretendard"), 125);
        DoodleValidator.Validated doodle = DoodleValidator.drawing(new DoodleValidator.DrawingPayload(1,
                List.of(new DoodleValidator.StrokeInput(3, 8, new int[] {470, 10, 490, 12}))), null, limits);

        long id = context.getBean(PublishService.class).publish(page.id(), author().getId(), doodle, limits).id();

        String expected = "{\"type\":\"doodle:published\",\"doodle\":{\"id\":\"" + id
                + "\",\"kind\":\"DRAWING\",\"bbox\":{\"x\":466,\"y\":6,\"w\":28,\"h\":10},"
                + "\"payload\":{\"v\":1,\"strokes\":[{\"c\":3,\"w\":8,\"p\":[470,10,490,12]}]}}}";
        assertThat(next(chunk0).toString()).isEqualTo(expected);
        assertThat(next(chunk1).toString()).isEqualTo(expected);
    }

    @Test
    void textDraftsRelayOnlyTheirBox() throws Exception {
        Client watcher = connect(null, ORIGIN);
        BlockingQueue<JsonNode> chunk0 = watcher.subscribe(chunk(0)).events;
        Client author = connect(cookie(author()), ORIGIN);
        BlockingQueue<JsonNode> errors = author.subscribe("/user/queue/errors").events;
        String id = UUID.randomUUID().toString();

        author.send("/app/draft", Map.of("pageId", pageId(), "draftId", id, "op", "text",
                "bbox", Map.of("x", 100, "y", 100, "w", 52, "h", 34)));
        JsonNode update = next(chunk0);
        assertThat(update.toString()).isEqualTo("{\"type\":\"draft:update\",\"sid\":\"" + update.get("sid").asText()
                + "\",\"draftId\":\"" + id + "\",\"op\":\"text\",\"bbox\":{\"x\":100,\"y\":100,\"w\":52,\"h\":34}}");

        author.send("/app/draft", Map.of("pageId", pageId(), "draftId", id, "op", "text", "text", "안녕",
                "bbox", Map.of("x", 100, "y", 100, "w", 52, "h", 34)));
        assertThat(next(errors).get("code").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(chunk0.poll(500, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void aCancelReachesEveryChunkTheDraftTouched() throws Exception {
        Client watcher = connect(null, ORIGIN);
        BlockingQueue<JsonNode> chunk0 = watcher.subscribe(chunk(0)).events;
        BlockingQueue<JsonNode> chunk1 = watcher.subscribe(chunk(1)).events;
        Client author = connect(cookie(author()), ORIGIN);
        String id = UUID.randomUUID().toString();

        author.send("/app/draft", points(id, 0, 0, 2, 10, 10));
        String sid = next(chunk0).get("sid").asText();
        author.send("/app/draft", points(id, 1, 0, 2, 600, 10));
        next(chunk1);
        author.send("/app/draft", Map.of("pageId", pageId(), "draftId", id, "op", "erase", "strokeIndex", 0));
        assertThat(next(chunk0).get("op").asText()).isEqualTo("erase");
        assertThat(next(chunk1).get("op").asText()).isEqualTo("erase");
        author.send("/app/draft", cancel(id));

        String expected = "{\"type\":\"draft:cancel\",\"sid\":\"" + sid + "\",\"draftId\":\"" + id + "\"}";
        assertThat(next(chunk0).toString()).isEqualTo(expected);
        assertThat(next(chunk1).toString()).isEqualTo(expected);
    }

    @Test
    void aNewDraftCancelsTheOpenOneAndADisconnectCancelsTheLast() throws Exception {
        Client watcher = connect(null, ORIGIN);
        BlockingQueue<JsonNode> chunk0 = watcher.subscribe(chunk(0)).events;
        Client author = connect(cookie(author()), ORIGIN);
        String first = UUID.randomUUID().toString();
        String second = UUID.randomUUID().toString();

        author.send("/app/draft", points(first, 0, 0, 2, 10, 10));
        next(chunk0);
        author.send("/app/draft", Map.of("pageId", pageId(), "draftId", second, "op", "text",
                "bbox", Map.of("x", 20, "y", 20, "w", 30, "h", 30)));
        JsonNode cancelled = next(chunk0);
        assertThat(cancelled.get("type").asText()).isEqualTo("draft:cancel");
        assertThat(cancelled.get("draftId").asText()).isEqualTo(first);
        assertThat(next(chunk0).get("draftId").asText()).isEqualTo(second);

        author.session.disconnect();
        JsonNode last = next(chunk0);
        assertThat(last.get("type").asText()).isEqualTo("draft:cancel");
        assertThat(last.get("draftId").asText()).isEqualTo(second);
    }

    @Test
    void framesOfACancelledDraftThatArriveLateAreDropped() throws Exception {
        Client watcher = connect(null, ORIGIN);
        BlockingQueue<JsonNode> chunk0 = watcher.subscribe(chunk(0)).events;
        Client author = connect(cookie(author()), ORIGIN);
        String id = UUID.randomUUID().toString();

        author.send("/app/draft", cancel(id));
        Thread.sleep(300);
        author.send("/app/draft", points(id, 0, 0, 2, 10, 10));

        assertThat(chunk0.poll(1, TimeUnit.SECONDS)).isNull();
    }

    @Test
    void refusedDraftsAreReportedToTheirSenderOnly() throws Exception {
        Client author = connect(cookie(author()), ORIGIN);
        BlockingQueue<JsonNode> errors = author.subscribe("/user/queue/errors").events;
        Client watcher = connect(null, ORIGIN);
        BlockingQueue<JsonNode> chunk0 = watcher.subscribe(chunk(0)).events;
        String id = UUID.randomUUID().toString();

        Map<String, Object> endedPage = new HashMap<>(points(id, 0, 0, 2, 10, 10));
        endedPage.put("pageId", "999999");
        author.send("/app/draft", endedPage);
        assertThat(next(errors).get("code").asText()).isEqualTo("PAGE_ENDED");
        for (Map<String, Object> bad : List.<Map<String, Object>>of(points(id, 0, 12, 2, 10, 10), points(id, 0, 0, 17, 10, 10),
                points(id, 0, 0, 2, 7680, 10), points(id, 0, 0, 2, 10, 10, 11), points(id, 0, 0, 2, 10, 10, 1100, 10),
                points("draft-1", 0, 0, 2, 10, 10), Map.of("pageId", pageId(), "draftId", id, "op", "start"),
                Map.of("pageId", pageId(), "draftId", id, "op", "cancel", "userId", "1"))) {
            author.send("/app/draft", bad);
            assertThat(next(errors).get("code").asText()).as("%s", bad).isEqualTo("VALIDATION_FAILED");
        }
        assertThat(chunk0.poll(500, TimeUnit.MILLISECONDS)).isNull();

        author.send("/app/draft", points(id, 0, 0, 2, 10, 10));
        assertThat(next(chunk0).get("type").asText()).isEqualTo("draft:update");
        assertThat(author.errors).isEmpty();
    }

    @Test
    void anonymousConnectionsCannotSendDrafts() throws Exception {
        Client anonymous = connect(null, ORIGIN);
        anonymous.send("/app/draft", points(UUID.randomUUID().toString(), 0, 0, 2, 10, 10));
        assertThat(anonymous.nextError()).isEqualTo("UNAUTHENTICATED");
    }

    @Test
    void aDraftStopsBeingRelayedAfterTwoMegabytes() throws Exception {
        Client author = connect(cookie(author()), ORIGIN);
        BlockingQueue<JsonNode> errors = author.subscribe("/user/queue/errors").events;
        Client watcher = connect(null, ORIGIN);
        BlockingQueue<JsonNode> chunk0 = watcher.subscribe(chunk(0)).events;
        String id = UUID.randomUUID().toString();
        // About 60 KB per frame, under the 64 KB frame limit: the 35th frame passes 2 MiB.
        int[] many = IntStream.range(0, 15_000).map(i -> 100 + i % 300).toArray();

        for (int i = 0; i < 40; i++) {
            if (i > 0 && i % 15 == 0) {
                Thread.sleep(200);
                clock.advance(Duration.ofSeconds(1));   // stay under 20 SENDs per second
            }
            author.send("/app/draft", points(id, i, 0, 2, many));
        }
        int relayed = 0;
        while (chunk0.poll(1, TimeUnit.SECONDS) != null) {
            relayed++;
        }
        String code = next(errors).get("code").asText();
        assertThat(code).isEqualTo("PAYLOAD_TOO_LARGE");
        assertThat(relayed).isBetween(30, 35);
        assertThat(author.errors).isEmpty();
    }

    // --- page events ---

    @Test
    void pageEventsReachTheMetaTopic() throws Exception {
        Client watcher = connect(null, ORIGIN);
        BlockingQueue<JsonNode> metaEvents = watcher.subscribe(meta()).events;
        long oldPage = page.id();

        processor.submit(new PageCommand.ApplyFootprint(oldPage,
                Footprint.ofCells(new int[] {0, 161}, page.geometry()), +1)).join();
        JsonNode diff = next(metaEvents);
        assertThat(diff.toString()).isEqualTo(
                "{\"type\":\"minimap:diff\",\"set\":[0,161],\"clear\":[],\"occupancyPermille\":0}");

        int[] most = IntStream.range(2, 10_100).filter(i -> i != 161).toArray();
        processor.submit(new PageCommand.ApplyFootprint(oldPage, Footprint.ofCells(most, page.geometry()), +1)).join();
        assertThat(next(metaEvents).get("type").asText()).isEqualTo("minimap:diff");
        JsonNode status = next(metaEvents);
        assertThat(status.get("type").asText()).isEqualTo("page:status");
        assertThat(status.get("status").asText()).isEqualTo("COUNTDOWN");
        assertThat(status.get("occupancyPermille").asInt()).isEqualTo(701);

        PageSnapshot next = turnPage();
        JsonNode turned = next(metaEvents);
        assertThat(turned.toString()).isEqualTo(
                "{\"type\":\"page:turned\",\"newPageId\":\"" + next.id() + "\"}");
    }

    @Test
    void aDraftOfTheTurnedPageIsRefusedAndTheNextPageWorksOnTheSameConnection() throws Exception {
        Client author = connect(cookie(author()), ORIGIN);
        BlockingQueue<JsonNode> errors = author.subscribe("/user/queue/errors").events;
        String id = UUID.randomUUID().toString();
        author.send("/app/draft", points(id, 0, 0, 2, 10, 10));
        String oldPage = pageId();

        turnPage();
        Client watcher = connect(null, ORIGIN);
        BlockingQueue<JsonNode> chunk0 = watcher.subscribe(chunk(0)).events;
        Map<String, Object> late = new HashMap<>(points(id, 0, 0, 2, 12, 12));
        late.put("pageId", oldPage);
        author.send("/app/draft", late);

        // D-16: no resend to the new page; the client drops the draft and starts over.
        assertThat(next(errors).get("code").asText()).isEqualTo("PAGE_ENDED");
        assertThat(chunk0.poll(500, TimeUnit.MILLISECONDS)).isNull();
        author.send("/app/draft", points(UUID.randomUUID().toString(), 0, 0, 2, 10, 10));
        assertThat(next(chunk0).get("type").asText()).isEqualTo("draft:update");
        assertThat(author.errors).isEmpty();
    }

    // --- helpers ---

    private static WebSocketContainer largeFrames() {
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        container.setDefaultMaxTextMessageBufferSize(CLIENT_FRAME_LIMIT);
        return container;
    }

    private PageSnapshot turnPage() {
        clock.set(page.scheduledEndAt().plusSeconds(1));
        page = processor.submit(new PageCommand.EndPage(page.id())).join();
        return page;
    }

    private User author() {
        User user = accounts.loginOrRegister("sub-" + UUID.randomUUID(), "w-" + UUID.randomUUID() + "@khu.ac.kr");
        return accounts.consent(user.getId());
    }

    private String cookie(User user) throws Exception {
        HttpResponse<Void> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/test/login/" + user.getId())).build(),
                HttpResponse.BodyHandlers.discarding());
        String setCookie = response.headers().allValues("Set-Cookie").stream()
                .filter(c -> c.startsWith("SESSION=")).findFirst().orElseThrow();
        return setCookie.substring(0, setCookie.indexOf(';'));
    }

    private String chunk(int index) {
        return RealtimeDestinations.chunkTopic(page.id(), index);
    }

    private String meta() {
        return RealtimeDestinations.metaTopic(page.id());
    }

    private Map<String, Object> cursor(int x, int y) {
        return Map.of("pageId", Long.toString(page.id()), "x", x, "y", y);
    }

    private String pageId() {
        return Long.toString(page.id());
    }

    private Map<String, Object> points(String draftId, int stroke, int c, int w, int... points) {
        return Map.of("pageId", pageId(), "draftId", draftId, "op", "points", "strokeIndex", stroke, "c", c,
                "w", w, "points", points);
    }

    private Map<String, Object> cancel(String draftId) {
        return Map.of("pageId", pageId(), "draftId", draftId, "op", "cancel");
    }

    private static JsonNode next(BlockingQueue<JsonNode> queue) throws InterruptedException {
        JsonNode node = queue.poll(WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(node).as("message within %ss", WAIT_SECONDS).isNotNull();
        return node;
    }

    private Client connect(String cookie, String origin) throws Exception {
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.setOrigin(origin);
        if (cookie != null) {
            headers.add("Cookie", cookie);
        }
        BlockingQueue<String> errors = new LinkedBlockingQueue<>();
        BlockingQueue<String> transportErrors = new LinkedBlockingQueue<>();
        StompSession session = stomp.connectAsync("ws://localhost:" + port + "/ws", headers,
                new StompSessionHandlerAdapter() {
                    @Override
                    public Type getPayloadType(StompHeaders frame) {
                        return String.class;
                    }

                    @Override
                    public void handleFrame(StompHeaders frame, Object payload) {
                        errors.add(frame.getFirst("message"));
                    }

                    @Override
                    public void handleTransportError(StompSession session, Throwable exception) {
                        transportErrors.add(exception.toString());
                    }
                }).get(WAIT_SECONDS, TimeUnit.SECONDS);
        Client client = new Client(session, errors, transportErrors);
        clients.add(client);
        return client;
    }

    private record Subscribed(StompSession.Subscription subscription, BlockingQueue<JsonNode> events)
            implements StompSession.Subscription {

        @Override
        public String getSubscriptionId() {
            return subscription.getSubscriptionId();
        }

        @Override
        public StompHeaders getSubscriptionHeaders() {
            return subscription.getSubscriptionHeaders();
        }

        @Override
        public void unsubscribe() {
            subscription.unsubscribe();
        }

        @Override
        public void unsubscribe(StompHeaders headers) {
            subscription.unsubscribe(headers);
        }

        @Override
        public String getReceiptId() {
            return subscription.getReceiptId();
        }

        @Override
        public void addReceiptTask(Runnable task) {
            subscription.addReceiptTask(task);
        }

        @Override
        public void addReceiptTask(Consumer<StompHeaders> task) {
            subscription.addReceiptTask(task);
        }

        @Override
        public void addReceiptLostTask(Runnable task) {
            subscription.addReceiptLostTask(task);
        }
    }

    private record Client(StompSession session, BlockingQueue<String> errors, BlockingQueue<String> transportErrors) {

        // Waits for the server's RECEIPT, so the subscription is registered before the test goes on.
        Subscribed subscribe(String destination) throws InterruptedException {
            CountDownLatch receipt = new CountDownLatch(1);
            Subscribed subscribed = subscribe(destination, true);
            subscribed.addReceiptTask(receipt::countDown);
            assertThat(receipt.await(WAIT_SECONDS, TimeUnit.SECONDS)).as("RECEIPT for %s", destination).isTrue();
            return subscribed;
        }

        // For subscriptions the server refuses: no RECEIPT comes, only an ERROR frame.
        Subscribed trySubscribe(String destination) {
            return subscribe(destination, false);
        }

        private Subscribed subscribe(String destination, boolean withReceipt) {
            BlockingQueue<JsonNode> events = new LinkedBlockingQueue<>();
            StompHeaders headers = new StompHeaders();
            headers.setDestination(destination);
            if (withReceipt) {
                headers.setReceipt(UUID.randomUUID().toString());
            }
            StompSession.Subscription sub = session.subscribe(headers, new StompFrameHandler() {
                @Override
                public Type getPayloadType(StompHeaders headers) {
                    return JsonNode.class;
                }

                @Override
                public void handleFrame(StompHeaders headers, Object payload) {
                    events.add((JsonNode) payload);
                }
            });
            return new Subscribed(sub, events);
        }

        void send(String destination, Object body) {
            session.send(destination, body);
        }

        String nextError() throws InterruptedException {
            return nextError("");
        }

        // When no ERROR comes, says whether the connection is still open (the frame was accepted) or was
        // closed without one, so a rare failure in CI can be told apart.
        String nextError(String context) throws InterruptedException {
            String code = errors.poll(WAIT_SECONDS, TimeUnit.SECONDS);
            assertThat(code).as("ERROR frame within %ss %s(connected: %s, transport error: %s)", WAIT_SECONDS,
                    context.isEmpty() ? "" : "for " + context + " ", session.isConnected(), transportErrors.peek())
                    .isNotNull();
            return code;
        }
    }
}
