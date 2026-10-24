package kr.ac.khu.graffiti.support;

import java.time.Instant;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.MySQLContainer;

/**
 * Base for integration tests: one MySQL 8.4 container for the whole run and a movable clock.
 * Subclasses share one Spring context when they add no other configuration.
 *
 * <p>The moderation checks are stubs until the criteria are settled, so the shipped default would hold every
 * doodle; tests publish unchecked ones like 06 did and stub {@code ModerationService} to test the other results.
 * The REST request caps are lifted; {@code ApiGuardsIT} tests them.
 */
@SpringBootTest
// The test clock stands still, so a per-second request cap would count a whole test as one second.
@TestPropertySource(properties = {"graffiti.moderation.unchecked=PUBLISH",
        "graffiti.web.admin-requests-per-second=100000", "graffiti.web.write-requests-per-second=100000"})
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
