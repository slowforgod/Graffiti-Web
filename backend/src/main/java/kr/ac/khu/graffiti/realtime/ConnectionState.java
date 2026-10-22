package kr.ac.khu.graffiti.realtime;

import java.security.Principal;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import kr.ac.khu.graffiti.auth.SessionUser;
import org.springframework.security.core.Authentication;

/**
 * What one STOMP connection keeps in its session attributes: its public {@code sid}, its
 * subscriptions, the author's display data after a SEND, the chunk of its last cursor, and the
 * metadata of its open draft. Nothing here is stored anywhere else (INV-06).
 */
final class ConnectionState {

    private static final String KEY = ConnectionState.class.getName();
    private static final SecureRandom RANDOM = new SecureRandom();

    record Author(String nickname, String color) {
    }

    record CursorAt(long pageId, int chunk) {
    }

    /**
     * The open draft without its content (INV-06): which chunks it reached, so a cancel reaches them
     * too, and how many bytes it has sent (D-11).
     */
    static final class Draft {
        final String id;
        final long pageId;
        final DraftRules.Kind kind;
        final Set<Integer> chunks = new HashSet<>();
        long bytes;

        Draft(String id, long pageId, DraftRules.Kind kind) {
            this.id = id;
            this.pageId = pageId;
            this.kind = kind;
        }
    }

    final String sid;
    final Map<String, String> subscriptions = new ConcurrentHashMap<>();
    volatile Author author;
    volatile CursorAt cursor;
    // Guarded by this: frames of one connection may be handled on several threads at once.
    Draft draft;
    String cancelledDraftId;

    private ConnectionState(String sid) {
        this.sid = sid;
    }

    static ConnectionState of(Map<String, Object> sessionAttributes) {
        if (sessionAttributes == null) {
            throw new IllegalStateException("STOMP message without session attributes");
        }
        return (ConnectionState) sessionAttributes.computeIfAbsent(KEY, k -> new ConnectionState(newSid()));
    }

    static Optional<ConnectionState> find(Map<String, Object> sessionAttributes) {
        return Optional.ofNullable(sessionAttributes).map(a -> (ConnectionState) a.get(KEY));
    }

    /** The logged-in user of the handshake request, if any. */
    static Optional<Long> userId(Principal principal) {
        if (principal instanceof Authentication authentication
                && authentication.getPrincipal() instanceof SessionUser user) {
            return Optional.of(user.userId());
        }
        if (principal instanceof SessionUser user) {
            return Optional.of(user.userId());
        }
        return Optional.empty();
    }

    private static String newSid() {
        byte[] bytes = new byte[6];
        RANDOM.nextBytes(bytes);
        return "s-" + HexFormat.of().formatHex(bytes);
    }
}
