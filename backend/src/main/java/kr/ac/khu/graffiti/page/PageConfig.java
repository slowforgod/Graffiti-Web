package kr.ac.khu.graffiti.page;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
class PageConfig {

    static final String PAGE_SCHEDULER = "pageTaskScheduler";

    // Uses the injected Clock so schedules follow the same time source as the page rules.
    @Bean(PAGE_SCHEDULER)
    ThreadPoolTaskScheduler pageTaskScheduler(Clock clock) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix("page-timer-");
        scheduler.setClock(clock);
        return scheduler;
    }
}
