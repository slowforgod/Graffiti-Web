package kr.ac.khu.graffiti.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.context.SecurityContextRepository;

/**
 * Replaces the OAuth2 authentication (which carries the ID token and email) with one holding only
 * {@link SessionUser}, so the stored session has no personal data (SEC-13), then returns to the app.
 */
public class LoginSuccessHandler implements AuthenticationSuccessHandler {

    private final SecurityContextRepository contexts;

    public LoginSuccessHandler(SecurityContextRepository contexts) {
        this.contexts = contexts;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) throws IOException {
        RegisteredOidcUser oidcUser = (RegisteredOidcUser) authentication.getPrincipal();
        Authentication slim = UsernamePasswordAuthenticationToken.authenticated(
                new SessionUser(oidcUser.userId()), null, authentication.getAuthorities());

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(slim);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);

        response.sendRedirect(request.getContextPath() + "/");
    }
}
