package kr.ac.khu.graffiti.page;

import kr.ac.khu.graffiti.support.MutableClock;
import org.springframework.context.ApplicationContext;

/**
 * Test support: starts a test on a new, empty ACTIVE page created "now". Spring contexts of one
 * test run share the database, so another context may have turned the page meanwhile; recovering
 * first makes this context's queue agree with the database before the page is ended.
 */
public final class FreshPage {

    private FreshPage() {
    }

    /** Runs the startup recovery again, as after a restart (D-25). */
    public static PageSnapshot recover(ApplicationContext context) {
        return context.getBean(PageBootstrap.class).recover();
    }

    public static PageSnapshot start(ApplicationContext context, MutableClock clock) {
        PageSnapshot current = context.getBean(PageBootstrap.class).recover();
        clock.set(current.scheduledEndAt().plusSeconds(1));
        return context.getBean(PageCommandProcessor.class).submit(new PageCommand.EndPage(current.id())).join();
    }
}
