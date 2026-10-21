package kr.ac.khu.graffiti.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import kr.ac.khu.graffiti.config.GraffitiProperties;
import kr.ac.khu.graffiti.user.Role;
import kr.ac.khu.graffiti.user.User;
import kr.ac.khu.graffiti.user.UserAccountService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** docs/API.md §1. The response never carries the email (SEC-13). */
@RestController
class MeController {

    private final UserAccountService accounts;
    private final Clock clock;
    private final Duration postCooldown;
    private static final MeResponse ANONYMOUS = new Anonymous(false);

    private final SecurityContextLogoutHandler logout = new SecurityContextLogoutHandler();

    MeController(UserAccountService accounts, Clock clock, GraffitiProperties props) {
        this.accounts = accounts;
        this.clock = clock;
        this.postCooldown = props.postCooldown();
    }

    @GetMapping("/api/me")
    MeResponse me(@AuthenticationPrincipal SessionUser principal) {
        if (principal == null) {
            return ANONYMOUS;
        }
        return accounts.find(principal.userId()).map(this::toResponse).orElse(ANONYMOUS);
    }

    @PostMapping("/api/me/consent")
    MeResponse consent(@CurrentUser(Access.SIGNED_IN) AuthUser user) {
        return toResponse(accounts.consent(user.id()));
    }

    @PostMapping("/api/auth/logout")
    ResponseEntity<Void> logout(@CurrentUser(Access.SIGNED_IN) AuthUser user, HttpServletRequest request,
            HttpServletResponse response) {
        logout.logout(request, response, SecurityContextHolder.getContext().getAuthentication());
        return ResponseEntity.noContent().build();
    }

    private MeResponse toResponse(User user) {
        return new SignedIn(true, Long.toString(user.getId()), user.getNickname(), user.getCursorColor(),
                user.getRole(), !user.hasConsented(), user.isBanned(), cooldownEndsAt(user));
    }

    private Instant cooldownEndsAt(User user) {
        if (user.getLastPostedAt() == null) {
            return null;
        }
        Instant ends = user.getLastPostedAt().plus(postCooldown);
        return ends.isAfter(clock.instant()) ? ends : null;
    }

    sealed interface MeResponse permits Anonymous, SignedIn {
    }

    record Anonymous(boolean authenticated) implements MeResponse {
    }

    /** {@code cooldownEndsAt} is null when the user may post now. */
    record SignedIn(boolean authenticated, String id, String nickname, String cursorColor, Role role,
            boolean consentRequired, boolean banned, Instant cooldownEndsAt) implements MeResponse {
    }
}
