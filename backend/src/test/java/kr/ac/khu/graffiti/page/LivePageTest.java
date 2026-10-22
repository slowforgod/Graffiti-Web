package kr.ac.khu.graffiti.page;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import kr.ac.khu.graffiti.page.event.PageStatusChanged;
import kr.ac.khu.graffiti.page.event.PageTurned;
import org.junit.jupiter.api.Test;

class LivePageTest {

    private static final CellGeometry GEOMETRY = new CellGeometry(7680, 4320, 48);

    private final PageReader reader = mock(PageReader.class);
    private final LivePage live = new LivePage(reader);

    private static PageSnapshot page(long id) {
        Instant t = Instant.parse("2026-10-01T00:00:00Z");
        return new PageSnapshot(id, (int) id, PageStatus.ACTIVE, 0, t, null, t.plusSeconds(60), GEOMETRY, 480);
    }

    @Test
    void readsOnceAndServesFromTheCache() {
        when(reader.current()).thenReturn(page(1));

        assertThat(live.ifCurrent(1)).contains(page(1));
        assertThat(live.ifCurrent(1)).contains(page(1));
        verify(reader, times(1)).current();
    }

    @Test
    void anOlderPageIsRefusedWithoutAskingTheQueueAgain() {
        when(reader.current()).thenReturn(page(2));
        live.current();

        assertThat(live.ifCurrent(1)).isEmpty();
        verify(reader, times(1)).current();
    }

    @Test
    void aNewerPageThanTheCachedOneIsCheckedAgain() {
        when(reader.current()).thenReturn(page(1), page(2));
        live.current();

        assertThat(live.ifCurrent(2)).contains(page(2));
    }

    @Test
    void pageEventsInvalidateTheCache() {
        when(reader.current()).thenReturn(page(1), page(1), page(2));
        live.current();

        live.onStatusChanged(new PageStatusChanged(1, PageStatus.COUNTDOWN, 700, null, null));
        live.current();
        live.onTurned(new PageTurned(1, 2));

        assertThat(live.ifCurrent(1)).isEmpty();
        verify(reader, times(3)).current();
    }
}
