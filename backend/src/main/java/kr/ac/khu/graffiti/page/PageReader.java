package kr.ac.khu.graffiti.page;

import java.util.concurrent.CompletionException;
import kr.ac.khu.graffiti.common.error.ApiException;
import kr.ac.khu.graffiti.common.error.ErrorCode;
import org.springframework.stereotype.Component;

/**
 * Read access to the current page for request threads. Reads go through the queue like writes, so a
 * reader never sees a half-applied command (INV-01); failures surface as the command's own exception.
 */
@Component
public class PageReader {

    private final PageCommandProcessor processor;

    PageReader(PageCommandProcessor processor) {
        this.processor = processor;
    }

    public PageSnapshot current() {
        return await(new PageCommand.GetPageSnapshot());
    }

    /** The current page, or {@code PAGE_ENDED} when {@code pageId} is any other page (INV-04, D-17). */
    public PageSnapshot requireCurrent(long pageId) {
        PageSnapshot snapshot = current();
        if (snapshot.id() != pageId) {
            throw new ApiException(ErrorCode.PAGE_ENDED);
        }
        return snapshot;
    }

    public Minimap minimap(long pageId) {
        return await(new PageCommand.GetMinimap(pageId));
    }

    private <R> R await(PageCommand<R> command) {
        try {
            return processor.submit(command).join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw e;
        }
    }
}
