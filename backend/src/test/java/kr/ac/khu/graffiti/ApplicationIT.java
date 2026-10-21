package kr.ac.khu.graffiti;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ApplicationIT {

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private Flyway flyway;

    @Test
    void appliesFlywayMigrationsOnStartup() {
        assertThat(flyway.info().applied()).isNotEmpty();
        assertThat(flyway.info().pending()).isEmpty();
    }

    @Test
    void healthIsUpWithoutDetails() {
        ResponseEntity<String> res = http.getForEntity("/actuator/health", String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isEqualTo("{\"status\":\"UP\"}");
    }

    // SEC-10: only health is exposed.
    @Test
    void otherActuatorEndpointsAreNotExposed() {
        for (String path : new String[] {"/actuator", "/actuator/env", "/actuator/beans", "/actuator/info",
                "/actuator/metrics", "/actuator/configprops", "/actuator/health/db"}) {
            assertThat(http.getForEntity(path, String.class).getStatusCode()).as(path).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }
}
