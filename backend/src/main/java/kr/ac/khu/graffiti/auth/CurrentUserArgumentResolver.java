package kr.ac.khu.graffiti.auth;

import java.util.Optional;
import kr.ac.khu.graffiti.common.error.ApiException;
import kr.ac.khu.graffiti.common.error.ErrorCode;
import kr.ac.khu.graffiti.user.Role;
import kr.ac.khu.graffiti.user.User;
import kr.ac.khu.graffiti.user.UserAccountService;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Resolves {@link CurrentUser} parameters. Consent, ban and role are read from the database on every
 * request, so a ban or an admin removal applies without waiting for a new login.
 */
@Component
class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    private final UserAccountService accounts;

    CurrentUserArgumentResolver(UserAccountService accounts) {
        this.accounts = accounts;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentUser.class)
                && AuthUser.class.equals(parameter.getParameterType());
    }

    @Override
    public AuthUser resolveArgument(MethodParameter parameter, ModelAndViewContainer mav,
            NativeWebRequest request, WebDataBinderFactory binderFactory) {
        Access access = parameter.getParameterAnnotation(CurrentUser.class).value();
        User user = currentUser().orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
        check(user, access);
        return new AuthUser(user.getId(), user.getRole(), user.hasConsented(), user.isBanned());
    }

    private Optional<User> currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof SessionUser principal)) {
            return Optional.empty();
        }
        return accounts.find(principal.userId());
    }

    static void check(User user, Access access) {
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
