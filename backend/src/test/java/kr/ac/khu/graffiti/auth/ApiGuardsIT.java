package kr.ac.khu.graffiti.auth;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import kr.ac.khu.graffiti.support.MutableClock;
import kr.ac.khu.graffiti.support.WebIntegrationTest;
import kr.ac.khu.graffiti.user.User;
import kr.ac.khu.graffiti.user.UserAccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The path guard of {@code /api/admin/**} and the per-user REST request caps (11, SEC-07/08/09). */
@Import(ApiGuardsIT.UnguardedAdminEndpoint.class)
@TestPropertySource(properties = {"graffiti.web.admin-requests-per-second=3",
        "graffiti.web.write-requests-per-second=2"})
class ApiGuardsIT extends WebIntegrationTest {

    private static final String CSRF_TOKEN = "test-csrf-token";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserAccountService accounts;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MutableClock clock;

    private User user;
    private User admin;

    @BeforeEach
    void users() {
        user = newUser();
        admin = newUser();
        jdbc.update("UPDATE users SET role = 'ADMIN' WHERE id = ?", admin.getId());
    }

    @Test
    void anAdminHandlerWithoutItsOwnCheckIsStillAdminOnly() throws Exception {
        mvc.perform(get("/api/admin/probe")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/probe").with(authentication(session(user))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mvc.perform(get("/api/admin/probe").with(authentication(session(admin)))).andExpect(status().isOk());

        // The role comes from the database, not the session.
        jdbc.update("UPDATE users SET role = 'USER' WHERE id = ?", admin.getId());
        mvc.perform(get("/api/admin/probe").with(authentication(session(admin)))).andExpect(status().isForbidden());
    }

    @Test
    void eachAdminHasARequestCapPerSecond() throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(get("/api/admin/probe").with(authentication(session(admin)))).andExpect(status().isOk());
        }
        mvc.perform(get("/api/admin/probe").with(authentication(session(admin))))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));

        User other = newUser();
        jdbc.update("UPDATE users SET role = 'ADMIN' WHERE id = ?", other.getId());
        mvc.perform(get("/api/admin/probe").with(authentication(session(other)))).andExpect(status().isOk());

        clock.advance(Duration.ofSeconds(1));
        mvc.perform(get("/api/admin/probe").with(authentication(session(admin)))).andExpect(status().isOk());
    }

    @Test
    void stateChangesOfOneUserAreCappedPerSecondAndReadsAreNot() throws Exception {
        mvc.perform(withCsrf(post("/api/me/consent")).with(authentication(session(user)))).andExpect(status().isOk());
        mvc.perform(withCsrf(post("/api/me/consent")).with(authentication(session(user)))).andExpect(status().isOk());
        mvc.perform(withCsrf(post("/api/me/consent")).with(authentication(session(user))))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
        for (int i = 0; i < 5; i++) {
            mvc.perform(get("/api/me").with(authentication(session(user)))).andExpect(status().isOk());
        }

        clock.advance(Duration.ofSeconds(1));
        mvc.perform(withCsrf(post("/api/me/consent")).with(authentication(session(user)))).andExpect(status().isOk());
    }

    private User newUser() {
        User created = accounts.loginOrRegister("sub-" + UUID.randomUUID(), "g-" + UUID.randomUUID() + "@khu.ac.kr");
        return accounts.consent(created.getId());
    }

    private static MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request) {
        return request.cookie(new Cookie("XSRF-TOKEN", CSRF_TOKEN)).header("X-XSRF-TOKEN", CSRF_TOKEN);
    }

    private static UsernamePasswordAuthenticationToken session(User user) {
        return UsernamePasswordAuthenticationToken.authenticated(new SessionUser(user.getId()), null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    /** An admin endpoint that forgot {@code @CurrentUser(ADMIN)}: the path guard alone must close it. */
    @TestComponent
    @RestController
    static class UnguardedAdminEndpoint {

        @GetMapping("/api/admin/probe")
        String probe() {
            return "ok";
        }
    }
}
