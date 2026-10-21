package kr.ac.khu.graffiti.auth;

import java.util.Locale;
import java.util.Map;

/**
 * SEC-04: a login is a school login only when all three hold: {@code hd} equals the school domain,
 * {@code email_verified} is true, and the email ends with {@code "@" + domain}.
 */
public final class KhuDomainVerifier {

    private final String domain;

    public KhuDomainVerifier(String domain) {
        this.domain = domain.toLowerCase(Locale.ROOT);
    }

    public boolean isSchoolAccount(Map<String, Object> idTokenClaims) {
        Object hd = idTokenClaims.get("hd");
        Object verified = idTokenClaims.get("email_verified");
        Object email = idTokenClaims.get("email");
        return hd instanceof String h && h.toLowerCase(Locale.ROOT).equals(domain)
                && Boolean.TRUE.equals(verified)
                && email instanceof String e && e.toLowerCase(Locale.ROOT).endsWith("@" + domain);
    }
}
