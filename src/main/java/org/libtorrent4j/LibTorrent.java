/*
 * Copyright (c) 2018-2025, Alden Torres
 *
 * Licensed under the terms of the MIT license.
 * Copy of the license at https://opensource.org/licenses/MIT
 */

package org.libtorrent4j;

import org.libtorrent4j.swig.libtorrent;
import org.libtorrent4j.swig.libtorrent_ext;
import org.libtorrent4j.swig.stats_metric;
import org.libtorrent4j.swig.stats_metric_vector;

import java.util.ArrayList;
import java.util.List;

/**
 * @author gubatron
 * @author aldenml
 */
public final class LibTorrent {

    private LibTorrent() {
    }

    /**
     * The version string as reported by libtorrent
     *
     * @return the version string
     */
    public static String version() {
        return libtorrent.version();
    }

    /**
     * The git revision of libtorrent this jar was built against (the
     * revision of the libtorrent submodule at build time).
     * <p>
     * This is not the internal revision libtorrent reports, since
     * that string is updated from time to time.
     *
     * @return the git revision
     */
    public static String revision() {
        return NativeBuildInfo.LIBTORRENT_REVISION;
    }

    /**
     * The libtorrent the loaded native library was built from, as
     * {@code "<libtorrent version> <git revision>"}. Compare with
     * {@link #expectedNativeBuild()} to detect a native library from another
     * build. A native library too old to report it throws
     * {@link UnsatisfiedLinkError}.
     *
     * @return the libtorrent version and revision of the native library
     */
    public static String nativeBuild() {
        return libtorrent_ext.nativeBuild();
    }

    /**
     * The libtorrent the native library has to be built from for this jar,
     * as {@code "<libtorrent version> <git revision>"}.
     *
     * @return the expected result of {@link #nativeBuild()}
     */
    public static String expectedNativeBuild() {
        return NativeBuildInfo.LIBTORRENT_VERSION + " " + NativeBuildInfo.LIBTORRENT_REVISION;
    }

    /**
     * Returns the version of boost used in the build.
     *
     * @return the build boost version.
     */
    public static String boostVersion() {
        return libtorrent.boost_lib_version();
    }

    /**
     * Returns the version of openssl used in the build.
     *
     * @return the build openssl version.
     */
    public static String opensslVersion() {
        return libtorrent.openssl_version_text();
    }

    /**
     * Version of libtorrent4j. It should match your maven
     * artifact version.
     *
     * @return libtorrent4j version.
     */
    public static String libtorrent4jVersion() {
        return NativeBuildInfo.LIBTORRENT4J_VERSION;
    }

    /**
     * This free function returns the list of available metrics exposed by
     * libtorrent's statistics API. Each metric has a name and a *value index*.
     * The value index is the index into the array in session_stats_alert where
     * this metric's value can be found when the session stats is sampled (by
     * calling post_session_stats()).
     *
     * @return the list of all metrics
     */
    public static List<StatsMetric> sessionStatsMetrics() {
        stats_metric_vector v = libtorrent.session_stats_metrics();

        ArrayList<StatsMetric> l = new ArrayList<>(v.size());

        for (stats_metric m : v) {
            l.add(new StatsMetric(m));
        }

        return l;
    }

    /**
     * given a name of a metric, this function returns the counter index of it,
     * or -1 if it could not be found. The counter index is the index into the
     * values array returned by session_stats_alert.
     *
     * @param name the name of the metric
     * @return the index of the metric
     */
    public static int findMetricIdx(String name) {
        return libtorrent.find_metric_idx_ex(name);
    }

    /**
     * If the native library is an ARM architecture variant, returns true
     * if the running platform has NEON support.
     *
     * @return true if the running platform has NEON support
     */
    public static boolean hasArmNeonSupport() {
        return libtorrent.arm_neon_support();
    }
}
