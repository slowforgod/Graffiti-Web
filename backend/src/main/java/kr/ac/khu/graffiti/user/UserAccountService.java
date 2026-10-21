package kr.ac.khu.graffiti.user;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import kr.ac.khu.graffiti.config.GraffitiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Account lookup and first-login registration. Logs carry the user id only, never the email (SEC-13). */
@Service
public class UserAccountService {

    private static final Logger log = LoggerFactory.getLogger(UserAccountService.class);

    private final UserRepository users;
    private final NicknameGenerator nicknames;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Set<String> adminEmails;
    private final int maxAttempts;

    public UserAccountService(UserRepository users, NicknameGenerator nicknames, TransactionTemplate tx,
            Clock clock, GraffitiProperties props) {
        this.users = users;
        this.nicknames = nicknames;
        this.tx = tx;
        this.clock = clock;
        this.adminEmails = props.auth().adminEmails().stream()
                .map(UserAccountService::normalize)
                .filter(e -> !e.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        this.maxAttempts = props.auth().nicknameMaxAttempts();
    }

    /**
     * Returns the account for a verified school login, creating it on first login. The role follows
     * {@code ADMIN_EMAILS} on every login (D-23). Safe under concurrent first logins: the unique keys
     * decide, and the loser re-reads.
     */
    public User loginOrRegister(String googleSub, String email) {
        Role role = adminEmails.contains(normalize(email)) ? Role.ADMIN : Role.USER;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                User user = tx.execute(s -> users.findByGoogleSub(googleSub)
                        .map(existing -> syncRole(existing, role))
                        .orElseGet(() -> register(googleSub, email, role)));
                log.info("User {} logged in", user.getId());
                return user;
            } catch (DataIntegrityViolationException e) {
                // Another login created the same account, or the nickname was taken: try again.
                log.debug("Registration attempt {} hit a unique key", attempt);
            }
        }
        throw new IllegalStateException("could not register a user after " + maxAttempts + " attempts");
    }

    public Optional<User> find(long userId) {
        return users.findById(userId);
    }

    public User consent(long userId) {
        return tx.execute(s -> {
            User user = users.findById(userId).orElseThrow();
            user.consent(clock.instant().truncatedTo(ChronoUnit.MILLIS));
            return user;
        });
    }

    private User register(String googleSub, String email, Role role) {
        User user = new User(googleSub, email, nicknames.nickname(), nicknames.cursorColor(), role,
                clock.instant().truncatedTo(ChronoUnit.MILLIS));
        User saved = users.saveAndFlush(user);
        log.info("User {} registered", saved.getId());
        return saved;
    }

    private static User syncRole(User user, Role role) {
        if (user.getRole() != role) {
            user.changeRole(role);
        }
        return user;
    }

    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}
