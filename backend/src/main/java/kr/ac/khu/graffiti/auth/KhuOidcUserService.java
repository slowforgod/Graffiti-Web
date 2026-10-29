package kr.ac.khu.graffiti.auth;

import java.util.List;
import kr.ac.khu.graffiti.config.GraffitiProperties;
import kr.ac.khu.graffiti.user.User;
import kr.ac.khu.graffiti.user.UserAccountService;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;

/**
 * Turns a Google ID token (already validated by Spring Security: signature, issuer, audience, expiry,
 * nonce) into our account. Uses ID token claims only, so no call to the userinfo endpoint is made.
 */
@Component
public class KhuOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {

    static final String DOMAIN_NOT_ALLOWED = "domain_not_allowed";

    private final KhuDomainVerifier verifier;
    private final UserAccountService accounts;

    public KhuOidcUserService(GraffitiProperties props, UserAccountService accounts) {
        this.verifier = new KhuDomainVerifier(props.auth().allowedDomain(), props.auth().adminEmails());
        this.accounts = accounts;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest request) {
        OidcIdToken idToken = request.getIdToken();
        if (!verifier.isAllowed(idToken.getClaims())) {
            throw new OAuth2AuthenticationException(new OAuth2Error(DOMAIN_NOT_ALLOWED));
        }
        User user = accounts.loginOrRegister(idToken.getSubject(), idToken.getEmail());
        return new RegisteredOidcUser(
                List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())), idToken, user.getId());
    }
}
