package kr.ac.khu.graffiti.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

class LoginHandlersTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void successKeepsOnlyTheUserIdInTheSession() throws Exception {
        OidcIdToken idToken = new OidcIdToken("token-value", Instant.EPOCH, Instant.EPOCH.plusSeconds(60),
                Map.of("sub", "google-1", "email", "a@khu.ac.kr"));
        RegisteredOidcUser oidcUser =
                new RegisteredOidcUser(List.of(new SimpleGrantedAuthority("ROLE_USER")), idToken, 42L);
        Authentication oauth = new OAuth2AuthenticationToken(oidcUser, oidcUser.getAuthorities(), "google");
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        new LoginSuccessHandler(new HttpSessionSecurityContextRepository())
                .onAuthenticationSuccess(request, response, oauth);

        SecurityContext stored = (SecurityContext) request.getSession()
                .getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(stored.getAuthentication().getPrincipal()).isEqualTo(new SessionUser(42L));
        assertThat(stored.getAuthentication().getCredentials()).isNull();
        assertThat(stored.getAuthentication().getAuthorities()).extracting(Object::toString)
                .containsExactly("ROLE_USER");
        assertThat(stored.getAuthentication().toString()).doesNotContain("a@khu.ac.kr", "token-value");
        assertThat(response.getRedirectedUrl()).isEqualTo("/");
    }

    @Test
    void domainFailureInvalidatesSessionAndReportsDomainNotAllowed() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        request.setSession(session);
        MockHttpServletResponse response = new MockHttpServletResponse();

        new LoginFailureHandler().onAuthenticationFailure(request, response,
                new OAuth2AuthenticationException(new OAuth2Error(KhuOidcUserService.DOMAIN_NOT_ALLOWED)));

        assertThat(session.isInvalid()).isTrue();
        assertThat(response.getRedirectedUrl()).isEqualTo("/?loginError=DOMAIN_NOT_ALLOWED");
    }

    @Test
    void otherFailuresReportLoginFailed() throws Exception {
        MockHttpServletResponse cancelled = new MockHttpServletResponse();
        new LoginFailureHandler().onAuthenticationFailure(new MockHttpServletRequest(), cancelled,
                new OAuth2AuthenticationException(new OAuth2Error("access_denied")));
        assertThat(cancelled.getRedirectedUrl()).isEqualTo("/?loginError=LOGIN_FAILED");

        MockHttpServletResponse other = new MockHttpServletResponse();
        new LoginFailureHandler().onAuthenticationFailure(new MockHttpServletRequest(), other,
                new BadCredentialsException("x"));
        assertThat(other.getRedirectedUrl()).isEqualTo("/?loginError=LOGIN_FAILED");
    }
}
