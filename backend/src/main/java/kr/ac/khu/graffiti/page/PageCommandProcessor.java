package kr.ac.khu.graffiti.page;

import java.util.concurrent.CompletableFuture;

/**
 * The only way to change page state (D-03, INV-01). Commands run one at a time in submission order.
 * Implementations may be replaced (e.g. a distributed queue) without changing callers.
 */
public interface PageCommandProcessor {

    <R> CompletableFuture<R> submit(PageCommand<R> command);
}
