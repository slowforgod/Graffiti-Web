package kr.ac.khu.graffiti.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import kr.ac.khu.graffiti.auth.KhuOidcUserService;
import kr.ac.khu.graffiti.auth.LoginFailureHandler;
import kr.ac.khu.graffiti.auth.LoginSuccessHandler;
import kr.ac.khu.graffiti.common.error.ApiError;
import kr.ac.khu.graffiti.common.error.ErrorCode;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfException;

/**
 * Google OIDC login, session cookies and CSRF (SEC-03, SEC-04). Every {@code /api/**} path needs a
 * session except the public reads {@code GET /api/me} and {@code GET /api/pages/**}; finer checks
 * (consent, ban, admin) live in {@code @CurrentUser}. Other paths are the OAuth endpoints, health
 * and the error page.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
class SecurityConfig {

    private static final String AUTHORIZATION_BASE_URI = "/oauth2/authorization";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, KhuOidcUserService oidcUserService,
            ClientRegistrationRepository registrations, GraffitiProperties props, ObjectMapper json)
            throws Exception {
        CookieCsrfTokenRepository csrfTokens = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfTokens.setCookieCustomizer(c -> c.secure(true).sameSite("Lax").path("/"));

        DefaultOAuth2AuthorizationRequestResolver authorizationRequests =
                new DefaultOAuth2AuthorizationRequestResolver(registrations, AUTHORIZATION_BASE_URI);
        // Hint Google to show school accounts only; the server still verifies (SEC-04).
        authorizationRequests.setAuthorizationRequestCustomizer(
                c -> c.additionalParameters(p -> p.put("hd", props.auth().allowedDomain())));

        http
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.GET, "/api/me", "/api/pages/**").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .csrf(c -> c
                        .csrfTokenRepository(csrfTokens)
                        .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler()))
                .oauth2Login(o -> o
                        .loginPage("/")
                        .authorizationEndpoint(e -> e
                                .baseUri(AUTHORIZATION_BASE_URI)
                                .authorizationRequestResolver(authorizationRequests))
                        .authorizedClientRepository(new NoAuthorizedClients())
                        .userInfoEndpoint(u -> u.oidcUserService(oidcUserService))
                        .successHandler(new LoginSuccessHandler(new HttpSessionSecurityContextRepository()))
                        .failureHandler(new LoginFailureHandler()))
                .logout(l -> l.disable())
                .requestCache(r -> r.disable())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> write(json, res, ErrorCode.UNAUTHENTICATED))
                        .accessDeniedHandler((req, res, ex) -> write(json, res,
                                ex instanceof CsrfException ? ErrorCode.CSRF_INVALID : ErrorCode.FORBIDDEN)));
        return http.build();
    }

    private static void write(ObjectMapper json, HttpServletResponse response, ErrorCode code) throws IOException {
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        json.writeValue(response.getOutputStream(), ApiError.of(code, code.defaultMessage(), null));
    }

    /** Google's access token is never needed after login, so it is not kept in the session. */
    private static final class NoAuthorizedClients implements OAuth2AuthorizedClientRepository {

        @Override
        public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(String registrationId,
                Authentication principal, HttpServletRequest request) {
            return null;
        }

        @Override
        public void saveAuthorizedClient(OAuth2AuthorizedClient client, Authentication principal,
                HttpServletRequest request, HttpServletResponse response) {
            // Intentionally not stored.
        }

        @Override
        public void removeAuthorizedClient(String registrationId, Authentication principal,
                HttpServletRequest request, HttpServletResponse response) {
            // Nothing is stored.
        }
    }
}
