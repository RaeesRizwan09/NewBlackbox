package top.niunaijun.blackbox.core;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.core.env.BEnvironment;
import top.niunaijun.blackbox.entity.pm.InstallResult;
import top.niunaijun.blackbox.fake.frameworks.BAccountManager;
import top.niunaijun.blackbox.utils.Slog;
import top.niunaijun.blackbox.utils.SpoofGoogleAccount;

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

    /** Removes only com.google.android.gms, leaving the other Google packages alone. */
    public static void uninstallGmsPackage(int userId) {
        Set<String> gms = new HashSet<>();
        gms.add(GMS_PKG);
        uninstallPackages(gms, userId);
    }

    /**
     * True when the sandboxed com.google.android.gms is the bundled microG
     * rather than a copy of the host's Google build.
     *
     * <p>The sandbox PackageManager merges the host's PackageInfo into its
     * answers, so asking it for GMS signatures always yields Google's
     * certificate even when microG is what is actually installed. Read the
     * signature of the APK file that the sandbox really installed instead.
     */
    public static boolean isInstalledMicroG(int userId) {
        try {
            if (!BlackBoxCore.get().isInstallGms(userId)) {
                return false;
            }
            File apk = BEnvironment.getBaseApkDir(GMS_PKG);
            if (!apk.isFile()) {
                return false;
            }
            PackageInfo sandboxed = BlackBoxCore.getPackageManager()
                    .getPackageArchiveInfo(apk.getAbsolutePath(), PackageManager.GET_SIGNATURES);
            PackageInfo host = BlackBoxCore.getPackageManager()
                    .getPackageInfo(GMS_PKG, PackageManager.GET_SIGNATURES);
            Signature[] sandboxedSignatures = sandboxed == null ? null : sandboxed.signatures;
            Signature[] hostSignatures = host == null ? null : host.signatures;
            if (sandboxedSignatures == null || sandboxedSignatures.length == 0
                    || hostSignatures == null || hostSignatures.length == 0) {
                return false;
            }
            return !sandboxedSignatures[0].equals(hostSignatures[0]);
        } catch (Throwable t) {
            Slog.w(TAG, "isInstalledMicroG: " + t);
            return false;
        }
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
            // SERVICE_NOT_AVAILABLE. Enable both features and kick the
            // check-in and the FCM connection explicitly right after install.
            seedMicroGSettings(userId);
            seedSpoofGoogleAccount(userId);
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
     * microG reads its defaults from /system/etc/microg.xml when installed as
     * a system app. A sandboxed install has no such file, so check-in and FCM
     * default to disabled and registration always fails with "Checkin
     * disabled" / SERVICE_NOT_AVAILABLE. Seed those two switches into microG's
     * default preferences, leaving any existing (user chosen) values alone.
     */
    /**
     * Registers the spoofed Google account in the sandbox account store, so
     * apps that ask the account service (rather than only the in-process
     * AccountManager cache) still see a signed-in Google account.
     */
    private static void seedSpoofGoogleAccount(int userId) {
        try {
            boolean added = BAccountManager.get().addAccountExplicitly(
                    SpoofGoogleAccount.primary(), null, new android.os.Bundle(), userId);
            Slog.i(TAG, "seedSpoofGoogleAccount: added=" + added + " for user " + userId);
        } catch (Throwable t) {
            Slog.w(TAG, "seedSpoofGoogleAccount failed: " + t);
        }
    }

    private static void seedMicroGSettings(int userId) {
        final String[] keys = {"checkin_enable_service", "gcm_enable_mcs_service"};
        try {
            java.io.File dir = new java.io.File(
                    top.niunaijun.blackbox.core.env.BEnvironment.getDataDir(GMS_PKG, userId),
                    "shared_prefs");
            if (!dir.isDirectory() && !dir.mkdirs()) {
                Slog.w(TAG, "seedMicroGSettings: cannot create " + dir);
                return;
            }
            java.io.File file = new java.io.File(dir, GMS_PKG + "_preferences.xml");
            String xml = file.isFile()
                    ? readFully(file)
                    : "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n</map>\n";
            boolean changed = false;
            for (String key : keys) {
                if (xml.contains("name=\"" + key + "\"")) {
                    continue;
                }
                String line = "    <boolean name=\"" + key + "\" value=\"true\" />\n";
                int end = xml.lastIndexOf("</map>");
                xml = end >= 0
                        ? xml.substring(0, end) + line + xml.substring(end)
                        : xml + line;
                changed = true;
            }
            if (!changed) {
                return;
            }
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) {
                out.write(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            Slog.i(TAG, "seedMicroGSettings: check-in and FCM enabled for user " + userId);
        } catch (Throwable t) {
            Slog.w(TAG, "seedMicroGSettings failed", t);
        }
    }

    private static String readFully(java.io.File file) throws java.io.IOException {
        byte[] buf = new byte[(int) file.length()];
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            int off = 0;
            int n;
            while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) {
                off += n;
            }
        }
        return new String(buf, java.nio.charset.StandardCharsets.UTF_8);
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