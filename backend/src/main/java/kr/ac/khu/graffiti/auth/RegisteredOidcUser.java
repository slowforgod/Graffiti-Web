package kr.ac.khu.graffiti.auth;

import java.util.Collection;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

/** A verified Google login mapped to our account. Lives only for the login request. */
class RegisteredOidcUser extends DefaultOidcUser {

    private final long userId;

    RegisteredOidcUser(Collection<? extends GrantedAuthority> authorities, OidcIdToken idToken, long userId) {
        super(authorities, idToken);
        this.userId = userId;
    }

    long userId() {
        return userId;
    }
}
