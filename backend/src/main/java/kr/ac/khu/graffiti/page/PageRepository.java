package kr.ac.khu.graffiti.page;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PageRepository extends JpaRepository<Page, Long> {

    /** The page that has not ended. At most one exists (INV-11). */
    @Query("select p from Page p where p.status <> kr.ac.khu.graffiti.page.PageStatus.ENDED")
    Optional<Page> findLive();

    @Query("select coalesce(max(p.seq), 0) from Page p")
    int findMaxSeq();

    /** ACTIVE -> COUNTDOWN. Returns the number of rows changed: 0 means the page was not ACTIVE (INV-03). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Page p
               set p.status = kr.ac.khu.graffiti.page.PageStatus.COUNTDOWN,
                   p.countdownStartedAt = :startedAt,
                   p.scheduledEndAt = :scheduledEndAt
             where p.id = :id and p.status = kr.ac.khu.graffiti.page.PageStatus.ACTIVE
            """)
    int startCountdown(@Param("id") long id, @Param("startedAt") Instant startedAt,
            @Param("scheduledEndAt") Instant scheduledEndAt);

    /** {@code from} -> ENDED. Returns 0 when the page was no longer in {@code from} (INV-03). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Page p
               set p.status = kr.ac.khu.graffiti.page.PageStatus.ENDED,
                   p.endedAt = :endedAt,
                   p.endReason = :reason,
                   p.finalOccupancyPermille = :permille,
                   p.finalOccupancyBitmap = :bitmap
             where p.id = :id and p.status = :from
            """)
    int end(@Param("id") long id, @Param("from") PageStatus from, @Param("endedAt") Instant endedAt,
            @Param("reason") PageEndReason reason, @Param("permille") int permille, @Param("bitmap") byte[] bitmap);
}
