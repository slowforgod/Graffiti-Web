package kr.ac.khu.graffiti.auth;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kr.ac.khu.graffiti.support.WebIntegrationTest;
import kr.ac.khu.graffiti.user.User;
import kr.ac.khu.graffiti.user.UserAccountService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Access levels of {@code @CurrentUser}, decided from the database on each request (AC-9, AC-10). */
class AuthorizationIT extends WebIntegrationTest {

    private static final String CSRF_TOKEN = "test-csrf-token";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserAccountService accounts;

    @Autowired
    private JdbcTemplate jdbc;

    private User newUser() {
        return accounts.loginOrRegister("sub-" + UUID.randomUUID(), "z-" + UUID.randomUUID() + "@khu.ac.kr");
    }

    private static Authentication session(User user, String role) {
        return UsernamePasswordAuthenticationToken.authenticated(
                new SessionUser(user.getId()), null, List.of(new SimpleGrantedAuthority(role)));
    }

    // A real double-submit token instead of spring-security-test's csrf(), which swaps the CSRF
    // repository of the shared filter chain and would break SecurityIT in the same context.
    private static MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request) {
        return request.cookie(new Cookie("XSRF-TOKEN", CSRF_TOKEN)).header("X-XSRF-TOKEN", CSRF_TOKEN);
    }

    private ResultActions call(String path, User user, String sessionRole) throws Exception {
        return mvc.perform(withCsrf(post(path)).with(authentication(session(user, sessionRole))));
    }

    private void expectError(ResultActions result, int status, String code) throws Exception {
        result.andExpect(status().is(status)).andExpect(jsonPath("$.error.code").value(code));
    }

    @Test
    void signedInLevelAllowsUserBeforeConsent() throws Exception {
        User user = newUser();
        call("/api/test/signed-in", user, "ROLE_USER").andExpect(status().isOk())
                .andExpect(content().string(String.valueOf(user.getId())));
    }

    @Test
    void loginAndAuthorLevelsNeedConsent() throws Exception {
        User user = newUser();
        expectError(call("/api/test/login", user, "ROLE_USER"), 403, "CONSENT_REQUIRED");
        expectError(call("/api/test/author", user, "ROLE_USER"), 403, "CONSENT_REQUIRED");

        accounts.consent(user.getId());
        call("/api/test/login", user, "ROLE_USER").andExpect(status().isOk());
        call("/api/test/author", user, "ROLE_USER").andExpect(status().isOk());
    }

    @Test
    void bannedUserIsNotAnAuthorFromTheNextRequest() throws Exception {
        User user = newUser();
        accounts.consent(user.getId());
        jdbc.update("UPDATE users SET banned_at = ? WHERE id = ?", Timestamp.from(Instant.now()), user.getId());

        expectError(call("/api/test/author", user, "ROLE_USER"), 403, "BANNED");
        call("/api/test/login", user, "ROLE_USER").andExpect(status().isOk());
    }

    @Test
    void adminLevelFollowsTheDatabaseRoleNotTheSession() throws Exception {
        User user = newUser();
        accounts.consent(user.getId());

        expectError(call("/api/test/admin", user, "ROLE_ADMIN"), 403, "FORBIDDEN");

        jdbc.update("UPDATE users SET role = 'ADMIN' WHERE id = ?", user.getId());
        call("/api/test/admin", user, "ROLE_USER").andExpect(status().isOk());
    }

    @Test
    void anonymousGets401() throws Exception {
        expectError(mvc.perform(withCsrf(post("/api/test/signed-in"))), 401, "UNAUTHENTICATED");
    }
}
