package dev.fsp.app.run;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tuning for the background run host.
 *
 * @param flushIntervalTicks  ticks between writes to Postgres
 * @param flushIntervalMillis wall-clock ceiling between writes, so a slow run still reaches the UI
 * @param maxTicksPerSecond   pacing ceiling; a run asking for more is treated as unthrottled
 * @param recoverOnStartup    whether runs left running at shutdown come back paused
 * @param keepAutoCheckpoints how many automatic checkpoints to retain per run
 */
@ConfigurationProperties("fsp.runs")
public record RunProperties(int flushIntervalTicks, long flushIntervalMillis, double maxTicksPerSecond,
        boolean recoverOnStartup, int keepAutoCheckpoints) {

    public RunProperties {
        if (flushIntervalTicks <= 0) {
            flushIntervalTicks = 25;
        }
        if (flushIntervalMillis <= 0) {
            flushIntervalMillis = 500;
        }
        if (maxTicksPerSecond <= 0) {
            maxTicksPerSecond = 2_000;
        }
        if (keepAutoCheckpoints <= 0) {
            keepAutoCheckpoints = 5;
        }
    }
}
