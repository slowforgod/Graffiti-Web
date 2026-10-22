package kr.ac.khu.graffiti.realtime;

/** Server → client messages of API.md §6. Payloads must not carry user ids or emails (SEC-13, D-22). */
public interface Broadcaster {

    void toChunk(long pageId, int chunk, Object event);

    void toMeta(long pageId, Object event);

    /** {@code /user/queue/notice} of every connection of the user. */
    void toUser(long userId, Object event);
}
