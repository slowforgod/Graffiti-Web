package kr.ac.khu.graffiti.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import kr.ac.khu.graffiti.common.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

/** Drops any session and sends the browser back to the app with a reason the UI can show. */
public class LoginFailureHandler implements AuthenticationFailureHandler {

    static final String LOGIN_FAILED = "LOGIN_FAILED";

    private static final Logger log = LoggerFactory.getLogger(LoginFailureHandler.class);

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException exception) throws IOException {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        String reason = exception instanceof OAuth2AuthenticationException oauth
                && KhuOidcUserService.DOMAIN_NOT_ALLOWED.equals(oauth.getError().getErrorCode())
                        ? ErrorCode.DOMAIN_NOT_ALLOWED.name()
                        : LOGIN_FAILED;
        // The exception message can contain provider details; log the reason code only.
        log.info("Login rejected: {}", reason);
        response.sendRedirect(request.getContextPath() + "/?loginError=" + reason);
    }
}
