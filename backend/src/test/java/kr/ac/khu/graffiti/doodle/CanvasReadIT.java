package kr.ac.khu.graffiti.doodle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import kr.ac.khu.graffiti.page.Footprint;
import kr.ac.khu.graffiti.page.PageCommand;
import kr.ac.khu.graffiti.page.PageCommandProcessor;
import kr.ac.khu.graffiti.page.PageSnapshot;
import kr.ac.khu.graffiti.support.MutableClock;
import kr.ac.khu.graffiti.support.WebIntegrationTest;
import kr.ac.khu.graffiti.user.UserAccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** The public read API of 03-canvas-read against MySQL, without a session. */
class CanvasReadIT extends WebIntegrationTest {

    private static final String DRAWING = "{\"v\": 1, \"strokes\": [{\"c\": 3, \"w\": 8, \"p\": [470, 10, 490, 12]}]}";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private PageCommandProcessor processor;

    @Autowired
    private MutableClock clock;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UserAccountService accounts;

    @Autowired
    private ObjectMapper json;

    private PageSnapshot page;
    private long authorId;

    @BeforeEach
    void startOnFreshPage() {
        PageSnapshot current = processor.submit(new PageCommand.GetPageSnapshot()).join();
        clock.set(current.scheduledEndAt().plusSeconds(1));
        page = processor.submit(new PageCommand.EndPage(current.id())).join();
        authorId = accounts.loginOrRegister("sub-" + UUID.randomUUID(), "r-" + UUID.randomUUID() + "@khu.ac.kr")
                .getId();
    }

    @Test
    void currentPageIsPublicAndCarriesTheSnapshotConfig() throws Exception {
        mvc.perform(get("/api/pages/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(Long.toString(page.id())))
                .andExpect(jsonPath("$.seq").value(page.seq()))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.occupancyPermille").value(0))
                .andExpect(jsonPath("$.countdownStartedAt").isEmpty())
                .andExpect(jsonPath("$.scheduledEndAt").value(page.scheduledEndAt().toString()))
                .andExpect(jsonPath("$.serverTime").value(clock.instant().toString()))
                .andExpect(jsonPath("$.config.width").value(7680))
                .andExpect(jsonPath("$.config.height").value(4320))
                .andExpect(jsonPath("$.config.chunkSize").value(480))
                .andExpect(jsonPath("$.config.cellSize").value(48))
                .andExpect(jsonPath("$.config.zoomMin").value(0.5))
                .andExpect(jsonPath("$.config.zoomMax").value(4.0))
                .andExpect(jsonPath("$.config.palette.length()").value(12))
                .andExpect(jsonPath("$.config.strokeWidths.length()").value(4))
                .andExpect(jsonPath("$.config.fontSizes.length()").value(15))
                .andExpect(jsonPath("$.config.defaultFontSize").value(24))
                .andExpect(jsonPath("$.config.textMaxLength").value(50))
                .andExpect(jsonPath("$.config.maxBbox").value(1024))
                .andExpect(jsonPath("$.config.maxChunks").value(60))
                .andExpect(jsonPath("$.config.postCooldownSec").value(1800))
                .andExpect(jsonPath("$.config.countdownWarningSec").value(300))
                .andExpect(jsonPath("$.config.draftIdleTimeoutSec").value(600));
    }

    @Test
    void minimapReturnsTheOccupancyBitmap() throws Exception {
        // Cells 0 and 161 (row 1, col 1) of the 160 x 90 grid.
        processor.submit(new PageCommand.ApplyFootprint(page.id(),
                Footprint.ofCells(new int[] {0, 161}, page.geometry()), +1)).join();

        JsonNode body = read(mvc.perform(get("/api/pages/{p}/minimap", page.id())).andExpect(status().isOk()));

        assertThat(body.get("cols").asInt()).isEqualTo(160);
        assertThat(body.get("rows").asInt()).isEqualTo(90);
        assertThat(body.get("occupancyPermille").asInt()).isZero();
        byte[] bits = Base64.getDecoder().decode(body.get("bitmap").asText());
        assertThat(bits).hasSize(1800);
        assertThat(bits[0]).isEqualTo((byte) 0x80);
        assertThat(bits[161 / 8]).isEqualTo((byte) (0x80 >>> (161 % 8)));
        assertThat(IntStream.range(0, bits.length).filter(i -> bits[i] != 0).count()).isEqualTo(2);
    }

    @Test
    void chunkLookupReturnsEachPublishedDoodleOnceWithoutAuthorData() throws Exception {
        long spanning = insertDoodle("PUBLISHED", DRAWING, 466, 6, 28, 10, 0, 1);
        insertDoodle("DELETED", DRAWING, 500, 6, 10, 10, 1);
        insertDoodle("HIDDEN", DRAWING, 520, 6, 10, 10, 1);
        insertDoodle("PUBLISHED", DRAWING, 2000, 6, 10, 10, 4);

        JsonNode body = read(mvc.perform(get("/api/pages/{p}/doodles", page.id()).param("chunks", "0,1"))
                .andExpect(status().isOk()));

        assertThat(body.get("pageId").asText()).isEqualTo(Long.toString(page.id()));
        JsonNode doodles = body.get("doodles");
        assertThat(doodles).hasSize(1);
        JsonNode doodle = doodles.get(0);
        assertThat(doodle.get("id").asText()).isEqualTo(Long.toString(spanning));
        assertThat(doodle.get("kind").asText()).isEqualTo("DRAWING");
        assertThat(json.writeValueAsString(doodle.get("bbox"))).isEqualTo("{\"x\":466,\"y\":6,\"w\":28,\"h\":10}");
        assertThat(doodle.get("payload")).isEqualTo(json.readTree(DRAWING));
        assertThat(doodle.properties()).extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder("id", "kind", "bbox", "payload");
        assertThat(body.toString()).doesNotContain("author", "email", "nickname");
    }

    @Test
    void chunkLookupIsLimitedToTheCurrentPage() throws Exception {
        long oldPage = page.id();
        clock.set(page.scheduledEndAt().plusSeconds(1));
        processor.submit(new PageCommand.EndPage(oldPage)).join();

        expectError(mvc.perform(get("/api/pages/{p}/doodles", oldPage).param("chunks", "0")), 409, "PAGE_ENDED");
        expectError(mvc.perform(get("/api/pages/{p}/minimap", oldPage)), 409, "PAGE_ENDED");
        expectError(mvc.perform(get("/api/pages/{p}/doodles", 999_999).param("chunks", "0")), 409, "PAGE_ENDED");
    }

    @Test
    void chunkLookupRejectsBadChunkLists() throws Exception {
        String sixtyOne = IntStream.rangeClosed(0, 60).mapToObj(Integer::toString).collect(Collectors.joining(","));
        for (String chunks : new String[] {sixtyOne, "144", "a", "0) OR (1=1", ""}) {
            expectError(mvc.perform(get("/api/pages/{p}/doodles", page.id()).param("chunks", chunks)), 400,
                    "VALIDATION_FAILED").andExpect(jsonPath("$.error.details.field").value("chunks"));
        }
        expectError(mvc.perform(get("/api/pages/{p}/doodles", page.id())), 400, "VALIDATION_FAILED");
    }

    private long insertDoodle(String status, String payload, int x, int y, int w, int h, int... chunks) {
        Timestamp now = Timestamp.from(clock.instant());
        Map<String, Object> row = new HashMap<>();
        row.put("page_id", page.id());
        row.put("author_id", authorId);
        row.put("kind", "DRAWING");
        row.put("status", status);
        row.put("moderation_state", "PASSED");
        row.put("bbox_x", x);
        row.put("bbox_y", y);
        row.put("bbox_w", w);
        row.put("bbox_h", h);
        row.put("payload", payload);
        row.put("payload_bytes", payload.length());
        row.put("submitted_at", now);
        row.put("published_at", now);
        long id = new SimpleJdbcInsert(jdbc).withTableName("doodle").usingGeneratedKeyColumns("id")
                .usingColumns(row.keySet().toArray(String[]::new))
                .executeAndReturnKey(row).longValue();
        for (int chunk : chunks) {
            jdbc.update("INSERT INTO doodle_chunk (page_id, chunk_index, doodle_id) VALUES (?, ?, ?)",
                    page.id(), chunk, id);
        }
        return id;
    }

    private JsonNode read(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private static ResultActions expectError(ResultActions result, int status, String code) throws Exception {
        return result.andExpect(status().is(status)).andExpect(jsonPath("$.error.code").value(code));
    }
}
