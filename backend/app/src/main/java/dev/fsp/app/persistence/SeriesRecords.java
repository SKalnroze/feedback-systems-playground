package dev.fsp.app.persistence;

import java.util.List;

/** Time-series shapes returned to the charting layer. */
public final class SeriesRecords {

    private SeriesRecords() {
    }

    /**
     * @param category one of {@code variable}, {@code memory}, {@code aggregate}; lets the UI group
     *                 the series picker without parsing keys
     */
    public record SeriesDefinition(long id, String seriesKey, String objectId, String variable, String category) {
    }

    /** A single reading. */
    public record Point(long tick, double value) {
    }

    /**
     * One series over a tick range.
     *
     * @param resolution ticks per returned point; 1 means raw samples, higher means bucketed
     * @param bucketed   whether the points came from rollups rather than raw samples
     */
    public record Series(String seriesKey, List<Point> points, int resolution, boolean bucketed) {
    }
}
