package kr.ac.khu.graffiti.auth;

import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * SEC-04: a login is a school login only when all three hold: {@code hd} equals the school domain,
 * {@code email_verified} is true, and the email ends with {@code "@" + domain}.
 *
 * <p>The only exception is an admin email outside the school (D-23a): it may log in when its email exactly
 * matches one of {@code ADMIN_EMAILS} and {@code email_verified} is true.
 */
public final class KhuDomainVerifier {

    private final String domain;
    private final Set<String> exemptEmails;

    public KhuDomainVerifier(String domain) {
        this(domain, Set.of());
    }

    public KhuDomainVerifier(String domain, Collection<String> exemptEmails) {
        this.domain = domain.toLowerCase(Locale.ROOT);
        this.exemptEmails = exemptEmails.stream()
                .map(e -> e.trim().toLowerCase(Locale.ROOT))
                .filter(e -> !e.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    /** A school account, or an exempt admin email with a verified address. */
    public boolean isAllowed(Map<String, Object> idTokenClaims) {
        return isSchoolAccount(idTokenClaims) || isExemptAdmin(idTokenClaims);
    }

    public boolean isSchoolAccount(Map<String, Object> idTokenClaims) {
        Object hd = idTokenClaims.get("hd");
        Object verified = idTokenClaims.get("email_verified");
        Object email = idTokenClaims.get("email");
        return hd instanceof String h && h.toLowerCase(Locale.ROOT).equals(domain)
                && Boolean.TRUE.equals(verified)
                && email instanceof String e && e.toLowerCase(Locale.ROOT).endsWith("@" + domain);
    }

    private boolean isExemptAdmin(Map<String, Object> idTokenClaims) {
        Object verified = idTokenClaims.get("email_verified");
        Object email = idTokenClaims.get("email");
        return Boolean.TRUE.equals(verified)
                && email instanceof String e && exemptEmails.contains(e.toLowerCase(Locale.ROOT));
    }
}
