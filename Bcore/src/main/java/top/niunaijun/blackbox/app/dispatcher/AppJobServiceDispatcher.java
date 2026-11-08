package top.niunaijun.blackbox.app.dispatcher;

import android.app.Service;
import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Intent;
import android.content.res.Configuration;

import java.util.HashMap;
import java.util.Map;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.entity.JobRecord;
import top.niunaijun.blackbox.utils.Slog;


/**
 * Dispatches JobScheduler callbacks to the app's services inside the sandbox.
 * <p>
 * The framework may schedule ordinary Services (not JobServices) through the job
 * dispatcher in virtualized environments. WhatsApp's
 * {@code com.whatsapp.infra.push.RegistrationIntentService}, its alarm service and its
 * crash-upload service are plain Services: casting their instances to {@link JobService}
 * produced {@code ClassCastException} ("cannot be cast to android.app.job.JobService"),
 * which silently killed push registration inside the sandbox.
 * <p>
 * Instances are now created according to their real class: a {@link JobService} keeps the
 * full job lifecycle; a plain {@link Service} is attached and driven with the standard
 * service lifecycle instead.
 */
public class AppJobServiceDispatcher {
    private static final String TAG = "JobServiceDispatcher";

    private static final AppJobServiceDispatcher sServiceDispatcher = new AppJobServiceDispatcher();
    private final Map<Integer, JobRecord> mJobRecords = new HashMap<>();

    public static AppJobServiceDispatcher get() {
        return sServiceDispatcher;
    }

    public boolean onStartJob(JobParameters params) {
        try {
            JobRecord record = getJobRecord(params.getJobId());
            if (record == null) {
                return false;
            }
            JobService jobService = record.mJobService;
            if (jobService != null) {
                return jobService.onStartJob(params);
            }
            Service plainService = record.mPlainService;
            if (plainService != null) {
                try {
                    // Plain services have no job contract: hand them a standard
                    // onStartCommand with an explicit (empty) work intent.
                    Intent work = new Intent().setClassName(record.mServiceInfo.packageName,
                            record.mServiceInfo.name);
                    plainService.onStartCommand(work, 0, params.getJobId());
                    Slog.d(TAG, "Started plain service for jobId " + params.getJobId()
                            + ": " + record.mServiceInfo.name);
                    return true;
                } catch (Throwable t) {
                    Slog.w(TAG, "Plain service " + record.mServiceInfo.name
                            + " failed to handle job " + params.getJobId(), t);
                    destroyPlainService(record);
                }
            }
            return false;
        } catch (Throwable t) {
            Slog.w(TAG, "onStartJob failed", t);
        }
        return false;
    }

    public boolean onStopJob(JobParameters params) {
        try {
            int jobId = params.getJobId();
            JobRecord record;
            synchronized (mJobRecords) {
                record = mJobRecords.remove(jobId);
            }
            if (record == null) {
                return false;
            }
            if (record.mJobService != null) {
                boolean b = record.mJobService.onStopJob(params);
                record.mJobService.onDestroy();
                return b;
            }
            if (record.mPlainService != null) {
                destroyPlainService(record);
                return true;
            }
            return false;
        } catch (Throwable t) {
            Slog.w(TAG, "onStopJob failed", t);
        }
        return false;
    }

    private static void destroyPlainService(JobRecord record) {
        try {
            record.mPlainService.onDestroy();
        } catch (Throwable t) {
            Slog.w(TAG, "Plain service onDestroy failed: " + record.mServiceInfo.name, t);
        } finally {
            record.mPlainService = null;
        }
    }

    public void onConfigurationChanged(Configuration newConfig) {
        for (JobRecord jobRecord : mJobRecords.values()) {
            if (jobRecord.mJobService != null) {
                jobRecord.mJobService.onConfigurationChanged(newConfig);
            } else if (jobRecord.mPlainService != null) {
                try {
                    jobRecord.mPlainService.onConfigurationChanged(newConfig);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    public void onDestroy() {





    }

    public void onLowMemory() {
        for (JobRecord jobRecord : mJobRecords.values()) {
            if (jobRecord.mJobService != null) {
                jobRecord.mJobService.onLowMemory();
            } else if (jobRecord.mPlainService != null) {
                try {
                    jobRecord.mPlainService.onLowMemory();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    public void onTrimMemory(int level) {
        for (JobRecord jobRecord : mJobRecords.values()) {
            if (jobRecord.mJobService != null) {
                jobRecord.mJobService.onTrimMemory(level);
            } else if (jobRecord.mPlainService != null) {
                try {
                    jobRecord.mPlainService.onTrimMemory(level);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    JobService getJobService(int jobId) {
        JobRecord record = getJobRecord(jobId);
        return record != null ? record.mJobService : null;
    }

    JobRecord getJobRecord(int jobId) {
        synchronized (mJobRecords) {
            JobRecord jobRecord = mJobRecords.get(jobId);
            if (jobRecord != null && (jobRecord.mJobService != null || jobRecord.mPlainService != null)) {
                return jobRecord;
            }
            try {
                JobRecord record = BlackBoxCore.getBJobManager()
                        .queryJobRecord(BlackBoxCore.getAppProcessName(), jobId);
                if (record == null) {
                    return null;
                }
                record.mJobService = BlackBoxCore.currentActivityThread().createJobService(record.mServiceInfo);
                if (record.mJobService != null) {
                    mJobRecords.put(jobId, record);
                    return record;
                }
                // Not a JobService (or failed to load): fall back to a plain Service.
                record.mPlainService = BlackBoxCore.currentActivityThread()
                        .createService(record.mServiceInfo, null);
                if (record.mPlainService != null) {
                    Slog.d(TAG, "Job " + jobId + " targets a plain Service: "
                            + record.mServiceInfo.name);
                    mJobRecords.put(jobId, record);
                    return record;
                }
                return null;
            } catch (Throwable t) {
                Slog.w(TAG, "getJobRecord failed for jobId " + jobId, t);
            }
            return null;
        }
    }
}
