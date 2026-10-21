package kr.ac.khu.graffiti.auth;

/** Access levels from docs/API.md §0, plus {@link #SIGNED_IN} for the consent and logout endpoints. */
public enum Access {
    /** Logged in; consent may still be missing. */
    SIGNED_IN,
    /** API.md "로그인": logged in and consented. */
    LOGIN,
    /** API.md "작성자": logged in, consented and not banned. */
    AUTHOR,
    /** API.md "관리자". */
    ADMIN
}
