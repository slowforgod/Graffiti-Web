package kr.ac.khu.graffiti.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import kr.ac.khu.graffiti.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Behind nginx the app must see the browser's scheme and address (12a), so redirects such as the
 * OAuth redirect URI use https. Requests here come from 127.0.0.1, an internal proxy address.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ForwardedHeadersIT.EchoEndpoint.class)
class ForwardedHeadersIT extends IntegrationTest {

    @LocalServerPort
    private int port;

    private final HttpClient client = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();

    @TestConfiguration(proxyBeanMethods = false)
    @Import(EchoEndpoint.Controller.class)
    static class EchoEndpoint {

        @RestController
        static class Controller {

            @GetMapping("/test/request-info")
            String info(HttpServletRequest request) {
                return request.getScheme() + " " + request.isSecure() + " " + request.getRemoteAddr() + " "
                        + request.getServerName() + ":" + request.getServerPort();
            }
        }
    }

    private HttpResponse<String> send(String path, String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (headers.length > 0) {
            request.headers(headers);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String get(String... headers) throws Exception {
        return send("/test/request-info", headers).body();
    }

    @Test
    void trustsForwardedProtoAndForFromTheProxy() throws Exception {
        String info = get("X-Forwarded-Proto", "https", "X-Forwarded-For", "203.0.113.7");

        assertThat(info).isEqualTo("https true 203.0.113.7 localhost:443");
    }

    // AC-5: Google must send the browser back to https://<host>/login/oauth2/code/google (nginx keeps Host).
    @Test
    void oauthRedirectUriUsesTheForwardedSchemeAndHost() throws Exception {
        HttpResponse<String> res = send("/oauth2/authorization/google", "X-Forwarded-Proto", "https");

        assertThat(res.headers().firstValue("Location").orElseThrow())
                .contains("redirect_uri=https://localhost/login/oauth2/code/google&");
    }

    @Test
    void plainRequestStaysHttp() throws Exception {
        assertThat(get()).isEqualTo("http false 127.0.0.1 localhost:" + port);
    }
}
