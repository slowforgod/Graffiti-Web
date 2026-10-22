package kr.ac.khu.graffiti.auth;

import kr.ac.khu.graffiti.common.error.ApiException;
import kr.ac.khu.graffiti.common.error.ErrorCode;
import kr.ac.khu.graffiti.user.Role;
import kr.ac.khu.graffiti.user.User;

/** The access levels of API.md §0 applied to the user as stored now. Shared by REST and STOMP (SEC-07). */
public final class AccessCheck {

    private AccessCheck() {
    }

    public static void check(User user, Access access) {
        if (access == Access.SIGNED_IN) {
            return;
        }
        if (!user.hasConsented()) {
            throw new ApiException(ErrorCode.CONSENT_REQUIRED);
        }
        if (access == Access.AUTHOR && user.isBanned()) {
            throw new ApiException(ErrorCode.BANNED);
        }
        if (access == Access.ADMIN && user.getRole() != Role.ADMIN) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
    }
}
