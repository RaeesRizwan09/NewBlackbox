package top.niunaijun.blackbox.core;

import android.content.pm.PackageManager;

import java.util.HashSet;
import java.util.Set;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.entity.pm.InstallResult;
import top.niunaijun.blackbox.utils.Slog;

public class GmsCore {
    private static final String TAG = "GmsCore";

    private static final HashSet<String> GOOGLE_APP = new HashSet<>();
    private static final HashSet<String> GOOGLE_SERVICE = new HashSet<>();
    public static final String GMS_PKG = "com.google.android.gms";
    public static final String GSF_PKG = "com.google.android.gsf";
    public static final String VENDING_PKG = "com.android.vending";

    static {
        GOOGLE_APP.add(VENDING_PKG);
        GOOGLE_APP.add("com.google.android.play.games");
        GOOGLE_APP.add("com.google.android.wearable.app");
        GOOGLE_APP.add("com.google.android.wearable.app.cn");

        
        GOOGLE_SERVICE.add(GMS_PKG);
        GOOGLE_SERVICE.add(GSF_PKG);
        GOOGLE_SERVICE.add("com.google.android.gsf.login");
        GOOGLE_SERVICE.add("com.google.android.backuptransport");
        GOOGLE_SERVICE.add("com.google.android.backup");
        GOOGLE_SERVICE.add("com.google.android.configupdater");
        GOOGLE_SERVICE.add("com.google.android.syncadapters.contacts");
        GOOGLE_SERVICE.add("com.google.android.feedback");
        GOOGLE_SERVICE.add("com.google.android.onetimeinitializer");
        GOOGLE_SERVICE.add("com.google.android.partnersetup");
        GOOGLE_SERVICE.add("com.google.android.setupwizard");
        GOOGLE_SERVICE.add("com.google.android.syncadapters.calendar");
    }

    public static boolean isGoogleService(String packageName) {
        return GOOGLE_SERVICE.contains(packageName);
    }

    public static boolean isGoogleAppOrService(String str) {
        return GOOGLE_APP.contains(str) || GOOGLE_SERVICE.contains(str);
    }

    private static InstallResult installPackages(Set<String> list, int userId) {
        BlackBoxCore blackBoxCore = BlackBoxCore.get();
        for (String packageName : list) {
            if (blackBoxCore.isInstalled(packageName, userId)) {
                continue;
            }
            try {
                BlackBoxCore.getContext().getPackageManager().getApplicationInfo(packageName, 0);
            } catch (PackageManager.NameNotFoundException e) {
                
                continue;
            }
            InstallResult installResult = blackBoxCore.installPackageAsUser(packageName, userId);
            if (!installResult.success) {
                return installResult;
            }
        }
        return new InstallResult();
    }

    private static void uninstallPackages(Set<String> list, int userId) {
        BlackBoxCore blackBoxCore = BlackBoxCore.get();
        for (String packageName : list) {
            blackBoxCore.uninstallPackageAsUser(packageName, userId);
        }
    }

    public static InstallResult installGApps(int userId) {
        Set<String> googleApps = new HashSet<>();

        googleApps.addAll(GOOGLE_SERVICE);
        googleApps.addAll(GOOGLE_APP);

        InstallResult installResult = installPackages(googleApps, userId);
        if (!installResult.success) {
            uninstallGApps(userId);
            return installResult;
        }
        return installResult;
    }

    public static void uninstallGApps(int userId) {
        uninstallPackages(GOOGLE_SERVICE, userId);
        uninstallPackages(GOOGLE_APP, userId);
    }

    public static void remove(String packageName) {
        GOOGLE_SERVICE.remove(packageName);
        GOOGLE_APP.remove(packageName);
    }


    public static boolean isSupportGms() {
        try {
            BlackBoxCore.getPackageManager().getPackageInfo(GMS_PKG, 0);
            return true;
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        return false;
    }

    /**
     * Asset-relative path of the bundled microG GmsCore APK. It uses the same
     * package name as real Google Play Services (com.google.android.gms), so
     * sandboxed apps resolve their GMS bindings transparently without any
     * extra patching. FOSS (microG Project), redistributed unmodified.
     */
    public static final String MICROG_APK_ASSET = "microg/gmscore.apk";

    /** Kept out of debug APKs to keep CI artifacts small; free space on the phone. */
    private static final long MIN_MICROG_BYTES = 50L * 1024 * 1024;

    /**
     * Installs microG GmsCore into the sandbox. Source order:
     * 1. a device-local copy at Android/data/&lt;pkg&gt;/files/microG.apk
     *    (adb-pushable, no internet or permission needed),
     * 2. the APK bundled in assets (release builds).
     */
    public static InstallResult installMicroG(int userId) {
        try {
            java.io.File apk = resolveMicroGApk();
            if (apk == null) {
                InstallResult error = new InstallResult();
                return error.installError("microG APK not found. Push microG.apk to Android/data/"
                        + BlackBoxCore.getHostPkg() + "/files/ and retry.");
            }
            Slog.i(TAG, "installMicroG: using " + apk.getAbsolutePath() + " (" + apk.length() + " bytes)");
            InstallResult result = BlackBoxCore.get().installPackageAsUser(apk, userId);
            if (!result.success) {
                Slog.e(TAG, "installMicroG failed: " + result.msg);
                return result;
            }
            // In a sandbox the normal microG triggers (BOOT_COMPLETED,
            // AIRPLANE_MODE broadcasts, periodic alarms) never arrive, so its
            // device check-in never runs and GCM registration answers
            // SERVICE_NOT_AVAILABLE. Kick the check-in and the FCM connection
            // explicitly right after install.
            kickMicroGBackgroundServices(userId);
            return result;
        } catch (Throwable t) {
            Slog.e(TAG, "installMicroG error", t);
            InstallResult error = new InstallResult();
            return error.installError(t.getMessage());
        }
    }

    private static java.io.File resolveMicroGApk() throws java.io.IOException {
        android.content.Context ctx = BlackBoxCore.getContext();
        java.io.File external = new java.io.File(ctx.getExternalFilesDir(null), "microG.apk");
        if (external.isFile() && external.length() > MIN_MICROG_BYTES) {
            return external;
        }
        // Fall back to the asset bundled into release builds.
        java.io.File staged = new java.io.File(ctx.getCacheDir(), "microg_gmscore.apk");
        try (java.io.InputStream in = ctx.getAssets().open(MICROG_APK_ASSET)) {
            try (java.io.OutputStream os = new java.io.FileOutputStream(staged)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                }
            }
            if (staged.length() > MIN_MICROG_BYTES) {
                return staged;
            }
        } catch (java.io.IOException ignored) {
            // No bundled asset in this build.
        }
        return null;
    }

    /**
     * Starts microG's check-in and FCM connection services inside the
     * sandbox user so registration can complete without system broadcasts.
     */
    private static void kickMicroGBackgroundServices(int userId) {
        try {
            android.content.Intent checkin = new android.content.Intent()
                    .setClassName(GMS_PKG, "org.microg.gms.checkin.CheckinService");
            BlackBoxCore.getBActivityManager().startService(checkin, null, false, userId);
            Slog.i(TAG, "installMicroG: CheckinService started for user " + userId);
        } catch (Throwable t) {
            Slog.w(TAG, "installMicroG: check-in kick failed", t);
        }
        try {
            android.content.Intent mcs = new android.content.Intent()
                    .setClassName(GMS_PKG, "org.microg.gms.gcm.McsService");
            BlackBoxCore.getBActivityManager().startService(mcs, null, false, userId);
            Slog.i(TAG, "installMicroG: McsService (FCM) started for user " + userId);
        } catch (Throwable t) {
            Slog.w(TAG, "installMicroG: MCS kick failed", t);
        }
    }

    public static boolean isInstalledGoogleService(int userId) {
        return BlackBoxCore.get().isInstalled(GMS_PKG, userId);
    }
}