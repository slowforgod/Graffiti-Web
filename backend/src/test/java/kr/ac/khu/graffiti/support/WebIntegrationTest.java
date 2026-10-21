package kr.ac.khu.graffiti.support;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** Integration tests that go through the HTTP layer and the security filter chain. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(AuthTestEndpoints.class)
public abstract class WebIntegrationTest extends IntegrationTest {
}
