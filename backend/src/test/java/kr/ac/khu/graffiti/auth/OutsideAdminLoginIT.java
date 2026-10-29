package kr.ac.khu.graffiti.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import kr.ac.khu.graffiti.support.IntegrationTest;
import kr.ac.khu.graffiti.user.Role;
import kr.ac.khu.graffiti.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.test.context.TestPropertySource;

/** D-23a: an {@code ADMIN_EMAILS} address outside the school logs in as an admin; other outside ones do not. */
@TestPropertySource(properties = "graffiti.auth.admin-emails=Owner@Gmail.com")
class OutsideAdminLoginIT extends IntegrationTest {

    @Autowired
    private KhuOidcUserService oidcUserService;

    @Autowired
    private ClientRegistrationRepository registrations;

    @Autowired
    private UserRepository users;

    private OidcUserRequest request(String sub, boolean verified, String email) {
        Instant now = Instant.now();
        OidcIdToken idToken = new OidcIdToken("id-token-" + sub, now, now.plusSeconds(300),
                Map.of("sub", sub, "email", email, "email_verified", verified));
        OAuth2AccessToken accessToken = new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER, "access", now, now.plusSeconds(300));
        return new OidcUserRequest(registrations.findByRegistrationId("google"), accessToken, idToken);
    }

    @Test
    void listedOutsideEmailLogsInAsAdmin() {
        String sub = "sub-" + UUID.randomUUID();

        oidcUserService.loadUser(request(sub, true, "owner@gmail.com"));

        assertThat(users.findByGoogleSub(sub).orElseThrow().getRole()).isEqualTo(Role.ADMIN);
    }

    @Test
    void unlistedOrUnverifiedOutsideEmailIsRejected() {
        String other = "sub-" + UUID.randomUUID();
        String unverified = "sub-" + UUID.randomUUID();

        assertThatThrownBy(() -> oidcUserService.loadUser(request(other, true, "other@gmail.com")))
                .isInstanceOf(OAuth2AuthenticationException.class);
        assertThatThrownBy(() -> oidcUserService.loadUser(request(unverified, false, "owner@gmail.com")))
                .isInstanceOf(OAuth2AuthenticationException.class);
        assertThat(users.findByGoogleSub(other)).isEmpty();
        assertThat(users.findByGoogleSub(unverified)).isEmpty();
    }
}
