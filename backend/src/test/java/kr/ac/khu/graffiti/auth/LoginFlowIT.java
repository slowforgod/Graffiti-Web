package kr.ac.khu.graffiti.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import kr.ac.khu.graffiti.support.IntegrationTest;
import kr.ac.khu.graffiti.user.User;
import kr.ac.khu.graffiti.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

/**
 * The login path after Spring Security has validated Google's ID token: our user service with the
 * real Google registration and database, then the success and failure handlers.
 */
@ExtendWith(OutputCaptureExtension.class)
class LoginFlowIT extends IntegrationTest {

    private static final String TOKEN_VALUE = "id-token-value-" + UUID.randomUUID();

    @Autowired
    private KhuOidcUserService oidcUserService;

    @Autowired
    private ClientRegistrationRepository registrations;

    @Autowired
    private UserRepository users;

    @Autowired
    private JdbcTemplate jdbc;

    private OidcUserRequest request(String sub, Object hd, Object verified, String email) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", sub);
        claims.put("email", email);
        if (hd != null) {
            claims.put("hd", hd);
        }
        if (verified != null) {
            claims.put("email_verified", verified);
        }
        Instant now = Instant.now();
        OidcIdToken idToken = new OidcIdToken(TOKEN_VALUE, now, now.plusSeconds(300), claims);
        OAuth2AccessToken accessToken = new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER, "access", now, now.plusSeconds(300));
        return new OidcUserRequest(registrations.findByRegistrationId("google"), accessToken, idToken);
    }

    private int rowsFor(String sub) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE google_sub = ?", Integer.class, sub);
    }

    // AC-1, AC-2
    @Test
    void schoolLoginCreatesOneAccountAndRedirectsHome() throws Exception {
        String sub = "sub-" + UUID.randomUUID();
        String email = "a-" + UUID.randomUUID() + "@khu.ac.kr";

        OidcUser first = oidcUserService.loadUser(request(sub, "khu.ac.kr", true, email));
        MockHttpServletRequest http = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        new LoginSuccessHandler(new HttpSessionSecurityContextRepository()).onAuthenticationSuccess(http, response,
                new OAuth2AuthenticationToken(first, first.getAuthorities(), "google"));

        User user = users.findByGoogleSub(sub).orElseThrow();
        assertThat(user.getNickname()).matches("익명의 \\S+ #\\d{4}");
        assertThat(user.getCursorColor()).matches("#[0-9A-F]{6}");
        assertThat(response.getRedirectedUrl()).isEqualTo("/");

        oidcUserService.loadUser(request(sub, "khu.ac.kr", true, email));
        User again = users.findByGoogleSub(sub).orElseThrow();
        assertThat(rowsFor(sub)).isEqualTo(1);
        assertThat(again.getNickname()).isEqualTo(user.getNickname());
        assertThat(again.getCursorColor()).isEqualTo(user.getCursorColor());
    }

    // AC-3 (SEC-04): each condition on its own blocks the account.
    @ParameterizedTest
    @CsvSource(nullValues = "NULL", value = {
            "NULL,      true,  a@khu.ac.kr",
            "gmail.com, true,  a@khu.ac.kr",
            "khu.ac.kr, false, a@khu.ac.kr",
            "khu.ac.kr, true,  x@khu.ac.kr.evil.com"})
    void nonSchoolLoginCreatesNoAccount(String hd, Boolean verified, String email) throws Exception {
        String sub = "sub-" + UUID.randomUUID();

        assertThatThrownBy(() -> oidcUserService.loadUser(request(sub, hd, verified, email)))
                .isInstanceOfSatisfying(OAuth2AuthenticationException.class, e -> assertThat(
                        e.getError().getErrorCode()).isEqualTo(KhuOidcUserService.DOMAIN_NOT_ALLOWED));
        assertThat(rowsFor(sub)).isZero();

        MockHttpServletRequest http = new MockHttpServletRequest();
        http.getSession(true);
        MockHttpServletResponse response = new MockHttpServletResponse();
        try {
            oidcUserService.loadUser(request(sub, hd, verified, email));
        } catch (OAuth2AuthenticationException e) {
            new LoginFailureHandler().onAuthenticationFailure(http, response, e);
        }
        assertThat(http.getSession(false)).isNull();
        assertThat(response.getRedirectedUrl()).isEqualTo("/?loginError=DOMAIN_NOT_ALLOWED");
    }

    // AC-12
    @Test
    void loginLogsCarryNoEmailTokenOrSubject(CapturedOutput output) throws Exception {
        String sub = "sub-" + UUID.randomUUID();
        String email = "log-" + UUID.randomUUID() + "@khu.ac.kr";
        oidcUserService.loadUser(request(sub, "khu.ac.kr", true, email));
        String rejectedEmail = "rejected-" + UUID.randomUUID() + "@gmail.com";
        try {
            oidcUserService.loadUser(request(sub + "x", "gmail.com", true, rejectedEmail));
        } catch (OAuth2AuthenticationException e) {
            new LoginFailureHandler().onAuthenticationFailure(
                    new MockHttpServletRequest(), new MockHttpServletResponse(), e);
        }

        assertThat(output.getAll())
                .contains("logged in", "Login rejected: DOMAIN_NOT_ALLOWED")
                .doesNotContain(email, rejectedEmail, sub, TOKEN_VALUE);
    }
}
