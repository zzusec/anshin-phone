package com.anshin.phone;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Updates rules only. It never starts a VPN or obtains additional permissions. */
public final class RuleRefreshJobService extends JobService {
    private static final String TAG = "RuleRefreshJob";
    private static final int PERIODIC_ID = 0x415201;
    private static final int INITIAL_ID = 0x415202;
    private final Map<Integer, Thread> workers = new HashMap<>();

    /** Call from application startup, not from each status/UI poll. */
    public static synchronized void schedule(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null) throw new IllegalStateException("系统任务调度器不可用");
        ComponentName component = new ComponentName(context, RuleRefreshJobService.class);
        if (scheduler.getPendingJob(PERIODIC_ID) == null) {
            submit(scheduler, new JobInfo.Builder(PERIODIC_ID, component)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(TimeUnit.HOURS.toMillis(24)).setPersisted(true).build());
        }
        if (!RuleListStore.hasValidRules(context) && scheduler.getPendingJob(INITIAL_ID) == null) {
            submit(scheduler, new JobInfo.Builder(INITIAL_ID, component)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build());
        }
    }

    private static void submit(JobScheduler scheduler, JobInfo job) {
        if (scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS) {
            throw new IllegalStateException("规则自动更新任务调度失败");
        }
    }

    @Override public boolean onStartJob(JobParameters params) {
        synchronized (workers) {
            if (workers.containsKey(params.getJobId())) return false;
            Thread worker = new Thread(() -> {
                try {
                    RuleListStore.refresh(getApplicationContext());
                    if (!Thread.currentThread().isInterrupted()) {
                        DnsProtectionService.reloadRulesIfRunning(getApplicationContext());
                    }
                } catch (IOException | RuntimeException failure) {
                    Log.w(TAG, "规则更新未完成", failure);
                } finally {
                    synchronized (workers) {
                        if (workers.get(params.getJobId()) == Thread.currentThread()) {
                            workers.remove(params.getJobId());
                            jobFinished(params, false);
                        }
                    }
                }
            }, "ad-rule-refresh-" + params.getJobId());
            workers.put(params.getJobId(), worker);
            worker.start();
            return true;
        }
    }

    @Override public boolean onStopJob(JobParameters params) {
        synchronized (workers) {
            Thread worker = workers.remove(params.getJobId());
            if (worker != null) RuleListStore.cancelRefresh(worker);
        }
        return false; // No unbounded immediate retry; the next daily job or startup can try again.
    }

    @Override public void onDestroy() {
        synchronized (workers) {
            for (Thread worker : workers.values()) RuleListStore.cancelRefresh(worker);
            workers.clear();
        }
        super.onDestroy();
    }
}
