package kr.ac.khu.graffiti.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.ac.khu.graffiti.support.MutableClock;
import kr.ac.khu.graffiti.support.WebIntegrationTest;
import kr.ac.khu.graffiti.user.User;
import kr.ac.khu.graffiti.user.UserAccountService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/** Real HTTP through the filter chain and Spring Session JDBC (SEC-03, AC-4..8, AC-11, AC-13). */
class SecurityIT extends WebIntegrationTest {

    @LocalServerPort
    private int port;

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .proxy(HttpClient.Builder.NO_PROXY)
            .build();

    @Autowired
    private UserAccountService accounts;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MutableClock clock;

    @Autowired
    private ObjectMapper json;

    private record Browser(String session, String xsrf) {

        HttpRequest.Builder apply(HttpRequest.Builder request, boolean withCsrfHeader) {
            StringBuilder cookie = new StringBuilder();
            if (session != null) {
                cookie.append("SESSION=").append(session).append("; ");
            }
            if (xsrf != null) {
                cookie.append("XSRF-TOKEN=").append(xsrf);
                if (withCsrfHeader) {
                    request.header("X-XSRF-TOKEN", xsrf);
                }
            }
            if (!cookie.isEmpty()) {
                request.header("Cookie", cookie.toString());
            }
            return request;
        }
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    }

    private static String setCookie(HttpResponse<?> res, String name) {
        return res.headers().allValues("Set-Cookie").stream()
                .filter(c -> c.startsWith(name + "=")).findFirst().orElse(null);
    }

    private static String value(String setCookie) {
        return setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';'));
    }

    private HttpResponse<String> get(String path) throws Exception {
        return send(request(path).GET());
    }

    private String xsrfToken() throws Exception {
        return value(setCookie(get("/api/me"), "XSRF-TOKEN"));
    }

    private Browser signIn(User user) throws Exception {
        HttpResponse<String> res = get("/test/login/" + user.getId());
        return new Browser(value(setCookie(res, "SESSION")), xsrfToken());
    }

    private User newUser() {
        return accounts.loginOrRegister("sub-" + UUID.randomUUID(), "s-" + UUID.randomUUID() + "@khu.ac.kr");
    }

    private HttpResponse<String> post(String path, Browser browser, boolean csrfHeader, String body)
            throws Exception {
        HttpRequest.Builder request = request(path).header("Content-Type", "application/json")
                .POST(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return send(browser.apply(request, csrfHeader));
    }

    private HttpResponse<String> me(Browser browser) throws Exception {
        return send(browser.apply(request("/api/me").GET(), false));
    }

    private String errorCode(HttpResponse<String> res) throws Exception {
        return json.readTree(res.body()).path("error").path("code").asText();
    }

    // AC-4
    @Test
    void anonymousMeIsPublicAndHandsOutCsrfCookie() throws Exception {
        HttpResponse<String> res = get("/api/me");

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).isEqualTo("{\"authenticated\":false}");
        assertThat(setCookie(res, "XSRF-TOKEN")).isNotNull().doesNotContainIgnoringCase("HttpOnly");
    }

    // AC-7 cookie attributes, AC-13 session in MySQL
    @Test
    void sessionCookieIsHardenedAndStoredInMysql() throws Exception {
        User user = newUser();
        HttpResponse<String> res = get("/test/login/" + user.getId());
        String cookie = setCookie(res, "SESSION");

        assertThat(cookie).contains("HttpOnly", "Secure", "SameSite=Lax");
        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM SPRING_SESSION WHERE PRINCIPAL_NAME = ?",
                Integer.class, String.valueOf(user.getId()));
        assertThat(rows).isEqualTo(1);
        Integer maxInactive = jdbc.queryForObject(
                "SELECT MAX_INACTIVE_INTERVAL FROM SPRING_SESSION WHERE PRINCIPAL_NAME = ?",
                Integer.class, String.valueOf(user.getId()));
        assertThat(maxInactive).isEqualTo((int) Duration.ofDays(30).toSeconds());
        List<byte[]> attributes = jdbc.queryForList("""
                SELECT a.ATTRIBUTE_BYTES FROM SPRING_SESSION_ATTRIBUTES a
                JOIN SPRING_SESSION s ON s.PRIMARY_ID = a.SESSION_PRIMARY_ID
                WHERE s.PRINCIPAL_NAME = ?""", byte[].class, String.valueOf(user.getId()));
        assertThat(attributes).isNotEmpty()
                .allSatisfy(bytes -> assertThat(new String(bytes, StandardCharsets.ISO_8859_1))
                        .doesNotContain(user.getEmail(), user.getGoogleSub()));
    }

    // AC-5
    @Test
    void signedInMeHasNoEmail() throws Exception {
        User user = newUser();
        HttpResponse<String> res = me(signIn(user));

        JsonNode body = json.readTree(res.body());
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(body.path("authenticated").asBoolean()).isTrue();
        assertThat(body.path("id").asText()).isEqualTo(String.valueOf(user.getId()));
        assertThat(body.path("nickname").asText()).isEqualTo(user.getNickname());
        assertThat(body.path("cursorColor").asText()).isEqualTo(user.getCursorColor());
        assertThat(body.path("role").asText()).isEqualTo("USER");
        assertThat(body.path("consentRequired").asBoolean()).isTrue();
        assertThat(body.path("banned").asBoolean()).isFalse();
        assertThat(body.has("cooldownEndsAt")).isTrue();
        assertThat(body.path("cooldownEndsAt").isNull()).isTrue();
        assertThat(res.body()).doesNotContain("@", "email", user.getGoogleSub());
    }

    // AC-7 CSRF
    @Test
    void stateChangeWithoutCsrfHeaderIsRejected() throws Exception {
        Browser browser = signIn(newUser());

        HttpResponse<String> res = post("/api/me/consent", browser, false, "{}");

        assertThat(res.statusCode()).isEqualTo(403);
        assertThat(errorCode(res)).isEqualTo("CSRF_INVALID");
    }

    // AC-6, AC-11
    @Test
    void consentUsesTheSessionUserAndKeepsTheFirstTime() throws Exception {
        User user = newUser();
        User other = newUser();
        Browser browser = signIn(user);
        String body = json.writeValueAsString(Map.of("userId", other.getId()));

        HttpResponse<String> first = post("/api/me/consent", browser, true, body);
        clock.advance(Duration.ofMinutes(5));
        HttpResponse<String> second = post("/api/me/consent", browser, true, body);

        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(json.readTree(first.body()).path("consentRequired").asBoolean()).isFalse();
        assertThat(json.readTree(second.body()).path("id").asText()).isEqualTo(String.valueOf(user.getId()));
        assertThat(accounts.find(other.getId()).orElseThrow().hasConsented()).isFalse();
        assertThat(accounts.find(user.getId()).orElseThrow().getPrivacyConsentedAt())
                .isEqualTo(clock.instant().minus(Duration.ofMinutes(5)));
    }

    // AC-8
    @Test
    void anonymousStateChangesGet401NotRedirect() throws Exception {
        Browser anonymous = new Browser(null, xsrfToken());

        for (String path : List.of("/api/auth/logout", "/api/me/consent")) {
            HttpResponse<String> res = post(path, anonymous, true, "{}");
            assertThat(res.statusCode()).as(path).isEqualTo(401);
            assertThat(errorCode(res)).as(path).isEqualTo("UNAUTHENTICATED");
        }
    }

    @Test
    void logoutEndsTheSession() throws Exception {
        User user = newUser();
        Browser browser = signIn(user);

        HttpResponse<String> res = post("/api/auth/logout", browser, true, null);

        assertThat(res.statusCode()).isEqualTo(204);
        assertThat(me(browser).body()).isEqualTo("{\"authenticated\":false}");
        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM SPRING_SESSION WHERE PRINCIPAL_NAME = ?",
                Integer.class, String.valueOf(user.getId()));
        assertThat(rows).isZero();
        assertThat(setCookie(res, LogoutMark.COOKIE))
                .contains("HttpOnly", "Secure", "SameSite=Lax", "Path=/")
                .doesNotContain("Max-Age");
    }

    @Test
    void oauthStartRedirectsToGoogleWithSchoolHint() throws Exception {
        HttpResponse<String> res = get("/oauth2/authorization/google");

        assertThat(res.statusCode()).isEqualTo(302);
        assertThat(res.headers().firstValue("Location").orElseThrow())
                .startsWith("https://accounts.google.com/")
                .contains("hd=khu.ac.kr", "scope=openid%20email%20profile", "nonce=")
                .doesNotContain("prompt=");
    }

    @Test
    void oauthStartAfterLogoutAsksGoogleToChooseTheAccountAgain() throws Exception {
        HttpResponse<String> res = send(request("/oauth2/authorization/google")
                .header("Cookie", LogoutMark.COOKIE + "=1").GET());

        assertThat(res.statusCode()).isEqualTo(302);
        assertThat(res.headers().firstValue("Location").orElseThrow())
                .startsWith("https://accounts.google.com/")
                .contains("hd=khu.ac.kr", "prompt=select_account");
    }
}
