package kr.ac.khu.graffiti.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class KhuDomainVerifierTest {

    private final KhuDomainVerifier verifier = new KhuDomainVerifier("khu.ac.kr");

    private static Map<String, Object> claims(Object hd, Object verified, Object email) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", "1");
        if (hd != null) {
            claims.put("hd", hd);
        }
        if (verified != null) {
            claims.put("email_verified", verified);
        }
        if (email != null) {
            claims.put("email", email);
        }
        return claims;
    }

    @Test
    void acceptsSchoolAccountWhenAllThreeHold() {
        assertThat(verifier.isSchoolAccount(claims("khu.ac.kr", true, "a@khu.ac.kr"))).isTrue();
        assertThat(verifier.isSchoolAccount(claims("KHU.AC.KR", true, "A@KHU.AC.KR"))).isTrue();
    }

    @Test
    void rejectsMissingHd() {
        assertThat(verifier.isSchoolAccount(claims(null, true, "a@khu.ac.kr"))).isFalse();
    }

    @Test
    void rejectsOtherHd() {
        assertThat(verifier.isSchoolAccount(claims("gmail.com", true, "a@khu.ac.kr"))).isFalse();
    }

    @Test
    void rejectsUnverifiedOrMissingEmailVerified() {
        assertThat(verifier.isSchoolAccount(claims("khu.ac.kr", false, "a@khu.ac.kr"))).isFalse();
        assertThat(verifier.isSchoolAccount(claims("khu.ac.kr", null, "a@khu.ac.kr"))).isFalse();
        assertThat(verifier.isSchoolAccount(claims("khu.ac.kr", "true", "a@khu.ac.kr"))).isFalse();
    }

    @Test
    void rejectsEmailThatOnlyContainsTheDomain() {
        assertThat(verifier.isSchoolAccount(claims("khu.ac.kr", true, "x@khu.ac.kr.evil.com"))).isFalse();
        assertThat(verifier.isSchoolAccount(claims("khu.ac.kr", true, "x@notkhu.ac.kr"))).isFalse();
        assertThat(verifier.isSchoolAccount(claims("khu.ac.kr", true, "x@gmail.com"))).isFalse();
        assertThat(verifier.isSchoolAccount(claims("khu.ac.kr", true, null))).isFalse();
    }
}
