package kr.ac.khu.graffiti.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import kr.ac.khu.graffiti.config.GraffitiProperties;
import kr.ac.khu.graffiti.support.IntegrationTest;
import kr.ac.khu.graffiti.support.MutableClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(OutputCaptureExtension.class)
class UserAccountServiceIT extends IntegrationTest {

    @Autowired
    private UserAccountService accounts;

    @Autowired
    private UserRepository users;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private MutableClock clock;

    @Autowired
    private JdbcTemplate jdbc;

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private int rowsFor(String sub) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE google_sub = ?", Integer.class, sub);
    }

    @Test
    void secondLoginReusesTheAccount() {
        String sub = unique("sub");
        User first = accounts.loginOrRegister(sub, unique("a") + "@khu.ac.kr");
        User second = accounts.loginOrRegister(sub, first.getEmail());

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(second.getNickname()).isEqualTo(first.getNickname());
        assertThat(second.getCursorColor()).isEqualTo(first.getCursorColor());
        assertThat(rowsFor(sub)).isEqualTo(1);
    }

    @Test
    void concurrentFirstLoginsCreateOneRow() throws Exception {
        String sub = unique("sub");
        String email = unique("c") + "@khu.ac.kr";
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Long>> logins = IntStream.range(0, threads)
                    .<Callable<Long>>mapToObj(i -> () -> accounts.loginOrRegister(sub, email).getId())
                    .toList();
            Set<Long> ids = pool.invokeAll(logins).stream().map(UserAccountServiceIT::get)
                    .collect(Collectors.toSet());
            assertThat(ids).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(rowsFor(sub)).isEqualTo(1);
    }

    @Test
    void nicknameCollisionRetriesWithAnotherNickname() {
        User existing = accounts.loginOrRegister(unique("sub"), unique("e") + "@khu.ac.kr");
        String fresh = "fresh " + UUID.randomUUID().toString().substring(0, 8);
        UserAccountService service = serviceWith(
                nicknames(existing.getNickname(), existing.getNickname(), fresh), adminEmails(List.of()), 10);

        User created = service.loginOrRegister(unique("sub"), unique("n") + "@khu.ac.kr");

        assertThat(created.getNickname()).isEqualTo(fresh);
    }

    @Test
    void givesUpAfterMaxAttempts() {
        User existing = accounts.loginOrRegister(unique("sub"), unique("e") + "@khu.ac.kr");
        String sub = unique("sub");
        UserAccountService service = serviceWith(
                nicknames(existing.getNickname(), existing.getNickname(), existing.getNickname()),
                adminEmails(List.of()), 3);

        assertThatThrownBy(() -> service.loginOrRegister(sub, unique("g") + "@khu.ac.kr"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(rowsFor(sub)).isZero();
    }

    // AC-9: the role follows ADMIN_EMAILS on every login.
    @Test
    void roleFollowsAdminEmailsOnEveryLogin() {
        String sub = unique("sub");
        String email = unique("admin") + "@khu.ac.kr";
        NicknameGenerator generator = new NicknameGenerator(new Random(), List.of("#000000"));

        User asAdmin = serviceWith(generator, adminEmails(List.of(" " + email.toUpperCase() + " ")), 10)
                .loginOrRegister(sub, email);
        assertThat(asAdmin.getRole()).isEqualTo(Role.ADMIN);

        User removed = serviceWith(generator, adminEmails(List.of()), 10).loginOrRegister(sub, email);
        assertThat(removed.getRole()).isEqualTo(Role.USER);
        assertThat(users.findById(removed.getId()).orElseThrow().getRole()).isEqualTo(Role.USER);
    }

    // AC-6: consent keeps the first time.
    @Test
    void consentKeepsTheFirstTime() {
        User user = accounts.loginOrRegister(unique("sub"), unique("k") + "@khu.ac.kr");
        User first = accounts.consent(user.getId());
        clock.advance(Duration.ofHours(1));
        User second = accounts.consent(user.getId());

        assertThat(first.getPrivacyConsentedAt()).isNotNull();
        assertThat(second.getPrivacyConsentedAt()).isEqualTo(first.getPrivacyConsentedAt());
    }

    // AC-12: logs carry the user id only.
    @Test
    void logsNeverContainEmailOrSubject(CapturedOutput output) {
        String sub = unique("secret-sub");
        String email = unique("private") + "@khu.ac.kr";
        accounts.loginOrRegister(sub, email);
        accounts.loginOrRegister(sub, email);

        assertThat(output.getAll()).contains("logged in").doesNotContain(email, sub);
    }

    private UserAccountService serviceWith(NicknameGenerator generator, GraffitiProperties props, int maxAttempts) {
        GraffitiProperties.Auth auth = props.auth();
        when(props.auth()).thenReturn(new GraffitiProperties.Auth(
                auth.allowedDomain(), auth.adminEmails(), auth.sessionTimeout(), maxAttempts));
        return new UserAccountService(users, generator, tx, clock, props);
    }

    private static GraffitiProperties adminEmails(List<String> emails) {
        GraffitiProperties props = mock(GraffitiProperties.class);
        when(props.auth()).thenReturn(new GraffitiProperties.Auth("khu.ac.kr", emails, Duration.ofDays(30), 10));
        return props;
    }

    private static NicknameGenerator nicknames(String... sequence) {
        Deque<String> queue = new ArrayDeque<>(List.of(sequence));
        return new NicknameGenerator(new Random(), List.of("#000000")) {
            @Override
            public String nickname() {
                return queue.size() > 1 ? queue.poll() : queue.peek();
            }
        };
    }

    private static <T> T get(Future<T> future) {
        try {
            return future.get();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
