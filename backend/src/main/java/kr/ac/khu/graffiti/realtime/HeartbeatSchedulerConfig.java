package kr.ac.khu.graffiti.realtime;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

// A bean of its own so Spring starts and stops it with the context.
@Configuration(proxyBeanMethods = false)
class HeartbeatSchedulerConfig {

    @Bean(WebSocketConfig.HEARTBEAT_SCHEDULER)
    ThreadPoolTaskScheduler wsHeartbeatScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix("ws-heartbeat-");
        return scheduler;
    }
}
