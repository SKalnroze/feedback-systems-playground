package dev.fsp.app.run;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How sampled data is aggregated for long runs.
 *
 * @param rollupBucketTicks size of a pre-aggregated bucket; a chart asking for this resolution or
 *                          coarser is answered from rollups rather than from raw samples
 * @param rollupEnabled     whether rollups are built at all; off means every query reads raw rows
 */
@ConfigurationProperties("fsp.metrics")
public record MetricsProperties(int rollupBucketTicks, boolean rollupEnabled) {

    public MetricsProperties {
        if (rollupBucketTicks <= 1) {
            rollupBucketTicks = 50;
        }
    }
}
