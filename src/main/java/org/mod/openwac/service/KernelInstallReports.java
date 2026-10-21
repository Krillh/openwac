package org.mod.openwac.service;

import org.mod.openwac.runtime.KernelInstallFailure;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;

/** Bounded failure delivery and duplicate suppression for one runtime/power attempt. */
public final class KernelInstallReports {
    private final Set<String> seen = new HashSet<>();
    private final ArrayBlockingQueue<KernelInstallFailure> pending = new ArrayBlockingQueue<>(16);
    public synchronized void offer(KernelInstallFailure failure) {
        String key = failure.key();
        if (seen.size() >= 16 || seen.contains(key)) return;
        seen.add(key);
        pending.offer(failure);
    }
    public List<KernelInstallFailure> drain() {
        List<KernelInstallFailure> reports = new ArrayList<>();
        pending.drainTo(reports);
        return reports;
    }
}
