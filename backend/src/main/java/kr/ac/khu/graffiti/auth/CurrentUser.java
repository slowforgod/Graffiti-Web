package kr.ac.khu.graffiti.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Injects the {@link AuthUser} of the session and enforces the access level. The user always comes
 * from the session, never from the request body (SEC-02).
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentUser {

    Access value() default Access.LOGIN;
}
