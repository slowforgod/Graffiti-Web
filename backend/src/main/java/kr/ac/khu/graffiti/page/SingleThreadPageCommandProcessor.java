package kr.ac.khu.graffiti.page;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

/** Runs every page command on one dedicated thread. A failing command fails only its own future. */
@Component
public class SingleThreadPageCommandProcessor implements PageCommandProcessor, DisposableBean {

    static final String THREAD_NAME = "page-queue";

    private final PageCommandHandler handler;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, THREAD_NAME);
        thread.setDaemon(true);
        return thread;
    });

    public SingleThreadPageCommandProcessor(PageCommandHandler handler) {
        this.handler = handler;
    }

    @Override
    public <R> CompletableFuture<R> submit(PageCommand<R> command) {
        return CompletableFuture.supplyAsync(() -> command.executeOn(handler), executor);
    }

    @Override
    public void destroy() {
        // Already-queued commands still run; new submissions are rejected.
        executor.shutdown();
    }
}
