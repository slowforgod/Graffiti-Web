package kr.ac.khu.graffiti.auth;

import kr.ac.khu.graffiti.user.Role;

/** The requesting user as stored in the database right now, not as it was at login. */
public record AuthUser(long id, Role role, boolean consented, boolean banned) {
}
