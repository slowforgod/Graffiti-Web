package kr.ac.khu.graffiti.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class RealtimeDestinationsTest {

    @Test
    void parsesChunkMetaAndUserQueues() {
        assertThat(RealtimeDestinations.parse("/topic/page.12.chunk.143"))
                .isEqualTo(new RealtimeDestinations.Chunk(12, 143));
        assertThat(RealtimeDestinations.parse("/topic/page.12.meta")).isEqualTo(new RealtimeDestinations.Meta(12));
        assertThat(RealtimeDestinations.parse("/user/queue/notice"))
                .isEqualTo(new RealtimeDestinations.UserQueue("/queue/notice"));
        assertThat(RealtimeDestinations.parse("/user/queue/errors"))
                .isEqualTo(new RealtimeDestinations.UserQueue("/queue/errors"));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
            "", "/topic/page.12.chunk.-1", "/topic/page.12.chunk.a", "/topic/page.x.meta", "/topic/page.12.chunk.1.x",
            "/topic/page.12", "/topic/page.12.chunk.12345", "/topic/page.1234567890123456789.meta", "/topic/other",
            "/app/cursor", "/queue/notice", "/user/queue/other", "/user/42/queue/notice", "/topic/page.12.meta/"})
    void rejectsEverythingElse(String destination) {
        assertThat(RealtimeDestinations.parse(destination)).isEqualTo(new RealtimeDestinations.Unknown());
    }

    @Test
    void buildsTopicsThatParseBack() {
        assertThat(RealtimeDestinations.parse(RealtimeDestinations.chunkTopic(3, 17)))
                .isEqualTo(new RealtimeDestinations.Chunk(3, 17));
        assertThat(RealtimeDestinations.parse(RealtimeDestinations.metaTopic(3)))
                .isEqualTo(new RealtimeDestinations.Meta(3));
    }
}
