package kr.ac.khu.graffiti.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.util.Optional;
import java.util.Set;
import kr.ac.khu.graffiti.common.error.ApiException;
import kr.ac.khu.graffiti.common.error.ErrorCode;
import kr.ac.khu.graffiti.config.GraffitiProperties;
import kr.ac.khu.graffiti.realtime.SendRateLimiter;
import kr.ac.khu.graffiti.user.User;
import kr.ac.khu.graffiti.user.UserAccountService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Checks that run before any REST handler (11-security-pass), registered by {@link AuthWebConfig}.
 * {@link #admin()} makes {@code /api/admin/**} admin-only by path, so a handler that forgets
 * {@code @CurrentUser(ADMIN)} is still closed (SEC-07), and caps each admin's requests per second.
 * {@link #writes()} caps each signed-in user's state-changing requests per second (SEC-08/09). Both limits
 * use the per-user fixed window of the STOMP SEND limit.
 */
@Component
public class ApiGuards {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private final UserAccountService accounts;
    private final SendRateLimiter adminLimit;
    private final SendRateLimiter writeLimit;

    ApiGuards(UserAccountService accounts, GraffitiProperties props, Clock clock) {
        this.accounts = accounts;
        this.adminLimit = new SendRateLimiter(props.web().adminRequestsPerSecond(), clock);
        this.writeLimit = new SendRateLimiter(props.web().writeRequestsPerSecond(), clock);
    }

    HandlerInterceptor admin() {
        return new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                // The role is read from the database on every request, like @CurrentUser (D-23).
                User user = sessionUserId().flatMap(accounts::find)
                        .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
                AccessCheck.check(user, Access.ADMIN);
                if (!adminLimit.tryAcquire(user.getId())) {
                    throw new ApiException(ErrorCode.RATE_LIMITED);
                }
                return true;
            }
        };
    }

    HandlerInterceptor writes() {
        return new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                if (SAFE_METHODS.contains(request.getMethod())) {
                    return true;
                }
                Optional<Long> userId = sessionUserId();
                if (userId.isPresent() && !writeLimit.tryAcquire(userId.get())) {
                    throw new ApiException(ErrorCode.RATE_LIMITED);
                }
                return true;
            }
        };
    }

    private static Optional<Long> sessionUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof SessionUser user) {
            return Optional.of(user.userId());
        }
        return Optional.empty();
    }
}
