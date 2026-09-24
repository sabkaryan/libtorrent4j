package org.libtorrent4j;

import org.junit.Test;
import org.libtorrent4j.swig.counters;

import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * @author gubatron
 * @author aldenml
 */
public class StatsMetricTest {

    @Test
    public void testListStatsMetric() {
        List<StatsMetric> metrics = LibTorrent.sessionStatsMetrics();

        for (StatsMetric m : metrics) {
            assertEquals(m.valueIndex, LibTorrent.findMetricIdx(m.name));
        }

        assertEquals(-1, LibTorrent.findMetricIdx("anything"));
    }

    // the counter indices in the generated bindings (counters.stats_counter_t)
    // must be those of the native library. A counter added to libtorrent
    // without regenerating the bindings shifts them
    @Test
    public void testGeneratedCounterIndicesMatchNativeMetrics() throws Exception {
        int nativeCounters = 0;
        for (StatsMetric m : LibTorrent.sessionStatsMetrics()) {
            if (m.type != StatsMetric.TYPE_COUNTER) {
                continue;
            }
            ++nativeCounters;
            String field = m.name.substring(m.name.indexOf('.') + 1);
            counters.stats_counter_t c = (counters.stats_counter_t)
                    counters.stats_counter_t.class.getField(field).get(null);
            assertEquals(m.name, m.valueIndex, c.swigValue());
        }
        assertEquals(nativeCounters, counters.stats_counter_t.num_stats_counters.swigValue());
    }
}
