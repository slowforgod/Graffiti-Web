package kr.ac.khu.graffiti.common;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The only source of "now". Inject {@link Clock}; never call {@code now()} without it. Stored times are UTC. */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
