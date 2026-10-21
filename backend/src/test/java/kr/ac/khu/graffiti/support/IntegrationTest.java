package kr.ac.khu.graffiti.support;

import java.time.Instant;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.testcontainers.containers.MySQLContainer;

/**
 * Base for integration tests: one MySQL 8.4 container for the whole run and a movable clock.
 * Subclasses share one Spring context when they add no other configuration.
 */
@SpringBootTest
@Import(IntegrationTest.ClockConfig.class)
public abstract class IntegrationTest {

    @ServiceConnection
    protected static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    static {
        MYSQL.start();
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class ClockConfig {

        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(Instant.parse("2026-10-01T00:00:00Z"));
        }
    }
}
