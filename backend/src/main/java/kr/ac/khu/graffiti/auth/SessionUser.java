package kr.ac.khu.graffiti.auth;

import java.io.Serializable;
import java.security.Principal;

/**
 * What the session keeps about the logged-in user: the id only. No email, name or token is stored
 * in the session (SEC-13); everything else is read from the database per request.
 */
public record SessionUser(long userId) implements Principal, Serializable {

    @Override
    public String getName() {
        return Long.toString(userId);
    }
}
