package kr.ac.khu.graffiti.support;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import kr.ac.khu.graffiti.auth.Access;
import kr.ac.khu.graffiti.auth.AuthUser;
import kr.ac.khu.graffiti.auth.CurrentUser;
import kr.ac.khu.graffiti.auth.SessionUser;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only endpoints: a login shortcut that stores the same session a Google login stores, and one
 * endpoint per access level.
 */
@TestConfiguration(proxyBeanMethods = false)
@Import(AuthTestEndpoints.Endpoints.class)
public class AuthTestEndpoints {

    @RestController
    static class Endpoints {

        @GetMapping("/test/login/{userId}")
        ResponseEntity<Void> login(@PathVariable long userId, HttpServletRequest request,
                HttpServletResponse response) {
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                    new SessionUser(userId), null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
            new HttpSessionSecurityContextRepository().saveContext(context, request, response);
            return ResponseEntity.noContent().build();
        }

        @PostMapping("/api/test/signed-in")
        long signedIn(@CurrentUser(Access.SIGNED_IN) AuthUser user) {
            return user.id();
        }

        @PostMapping("/api/test/login")
        long login(@CurrentUser AuthUser user) {
            return user.id();
        }

        @PostMapping("/api/test/author")
        long author(@CurrentUser(Access.AUTHOR) AuthUser user) {
            return user.id();
        }

        @PostMapping("/api/test/admin")
        long admin(@CurrentUser(Access.ADMIN) AuthUser user) {
            return user.id();
        }
    }
}
