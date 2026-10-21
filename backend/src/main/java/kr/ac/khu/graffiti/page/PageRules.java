package kr.ac.khu.graffiti.page;

import java.time.Duration;

/** Turn rules of one page, snapshotted from configuration when the page is created (D-06, D-15). */
public record PageRules(int countdownThresholdPermille, Duration countdownDuration, Duration maxLifetime) {

    public static int thresholdToPermille(double threshold) {
        return (int) Math.round(threshold * 1000);
    }
}
