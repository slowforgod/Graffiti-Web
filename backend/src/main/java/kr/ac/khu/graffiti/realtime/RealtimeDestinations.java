package kr.ac.khu.graffiti.realtime;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** STOMP destinations of API.md §6. Parsing only; whether a page or chunk is current is checked elsewhere. */
public final class RealtimeDestinations {

    public static final String NOTICE_QUEUE = "/queue/notice";
    public static final String ERRORS_QUEUE = "/queue/errors";

    private static final Pattern CHUNK = Pattern.compile("/topic/page\\.(\\d{1,18})\\.chunk\\.(\\d{1,4})");
    private static final Pattern META = Pattern.compile("/topic/page\\.(\\d{1,18})\\.meta");

    public sealed interface Destination {
    }

    public record Chunk(long pageId, int index) implements Destination {
    }

    public record Meta(long pageId) implements Destination {
    }

    /** {@code /user/queue/notice} or {@code /user/queue/errors}: needs a logged-in connection. */
    public record UserQueue(String queue) implements Destination {
    }

    public record Unknown() implements Destination {
    }

    private RealtimeDestinations() {
    }

    public static Destination parse(String destination) {
        if (destination == null) {
            return new Unknown();
        }
        Matcher chunk = CHUNK.matcher(destination);
        if (chunk.matches()) {
            return new Chunk(Long.parseLong(chunk.group(1)), Integer.parseInt(chunk.group(2)));
        }
        Matcher meta = META.matcher(destination);
        if (meta.matches()) {
            return new Meta(Long.parseLong(meta.group(1)));
        }
        if (destination.equals("/user" + NOTICE_QUEUE) || destination.equals("/user" + ERRORS_QUEUE)) {
            return new UserQueue(destination.substring("/user".length()));
        }
        return new Unknown();
    }

    public static String chunkTopic(long pageId, int index) {
        return "/topic/page." + pageId + ".chunk." + index;
    }

    public static String metaTopic(long pageId) {
        return "/topic/page." + pageId + ".meta";
    }
}
