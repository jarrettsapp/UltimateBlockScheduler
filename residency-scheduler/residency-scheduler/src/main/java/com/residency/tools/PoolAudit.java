package com.residency.tools;

import com.residency.cpsat.CpSatSchedulerEngine;

/**
 * Audits the Phase-0 feasible-assignment POOL (Option 1 cache) for the given year:
 *
 *  1. FEASIBILITY — hard-fixes each pooled assignment into a fresh model and solves it
 *     against the CURRENT model. FEASIBLE = genuinely valid now; STALE = the model changed
 *     since it was cached and the entry no longer solves.
 *  2. DIVERSITY — reports the pairwise Hamming distance (number of placements differing)
 *     across the pool, so you can see whether the cached seeds are genuinely distinct or
 *     clustered. Read-only measure; no insertion threshold is enforced.
 *
 * Usage:
 *   java -cp "target/classes;$(cat cp.txt)" com.residency.tools.PoolAudit &lt;year&gt; [--evict]
 *     --evict   remove STALE entries from the pool and re-stamp the model fingerprint
 *
 * Run this against a COPY of the DB unless you intend --evict to mutate the live pool.
 */
public final class PoolAudit {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: PoolAudit <year> [--evict]");
            System.exit(2);
        }
        int year = Integer.parseInt(args[0]);
        boolean evict = args.length > 1 && "--evict".equals(args[1]);

        CpSatSchedulerEngine engine = new CpSatSchedulerEngine();
        String report = engine.auditPool(year, evict);
        System.out.println(report);
    }

    private PoolAudit() {}
}
