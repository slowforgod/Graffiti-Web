package kr.ac.khu.graffiti.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;

/**
 * Remembers in the browser that its user logged out on purpose. The next login then asks Google to
 * show the account chooser instead of signing the last account in silently. Login clears it.
 */
public final class LogoutMark {

    public static final String COOKIE = "GRAFFITI_LOGGED_OUT";

    private LogoutMark() {
    }

    public static void set(HttpServletResponse response) {
        write(response, "1", -1);
    }

    public static void clear(HttpServletResponse response) {
        write(response, "", 0);
    }

    public static boolean isSet(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return false;
        }
        for (Cookie c : cookies) {
            if (COOKIE.equals(c.getName())) {
                return true;
            }
        }
        return false;
    }

    // Same hardening as the session cookie (SEC-03). A negative max age makes it last until the browser closes.
    private static void write(HttpServletResponse response, String value, long maxAgeSeconds) {
        ResponseCookie cookie = ResponseCookie.from(COOKIE, value)
                .httpOnly(true).secure(true).sameSite("Lax").path("/").maxAge(maxAgeSeconds).build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
