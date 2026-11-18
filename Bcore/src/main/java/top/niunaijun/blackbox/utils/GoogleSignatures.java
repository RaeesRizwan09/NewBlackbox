package top.niunaijun.blackbox.utils;

import android.content.pm.PackageInfo;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.util.Base64;

import java.lang.reflect.Method;

/**
 * Google package signature spoofing.
 *
 * <p>Apps verify that Google Play Services and the Play Store are genuine by
 * comparing the signing certificate reported by PackageManager against
 * Google's well known certificates. A sandboxed install cannot carry those
 * certificates: microG is self signed, and the Play Store is not installed in
 * the sandbox at all. Apps then treat the environment as tampered with.
 *
 * <p>Signature checks only compare the certificate, never a private key, so
 * reporting Google's public certificate is enough to satisfy them. Prefer the
 * certificate of the real package on the host (always current); fall back to
 * the embedded certificate on hosts without Google apps.
 */
public final class GoogleSignatures {
    private static final String TAG = "GoogleSignatures";

    /** Public signing certificate shared by GMS, GSF and the Play Store. */
    private static final String PLAY_CERT_B64 =
            "MIIEQzCCAyugAwIBAgIJAMLgh0ZkSjCNMA0GCSqGSIb3DQEBBAUAMHQxCzAJBgNVBAYTAlVTMRMw"
            + "EQYDVQQIEwpDYWxpZm9ybmlhMRYwFAYDVQQHEw1Nb3VudGFpbiBWaWV3MRQwEgYDVQQKEwtHb29n"
            + "bGUgSW5jLjEQMA4GA1UECxMHQW5kcm9pZDEQMA4GA1UEAxMHQW5kcm9pZDAeFw0wODA4MjEyMzEz"
            + "MzRaFw0zNjAxMDcyMzEzMzRaMHQxCzAJBgNVBAYTAlVTMRMwEQYDVQQIEwpDYWxpZm9ybmlhMRYw"
            + "FAYDVQQHEw1Nb3VudGFpbiBWaWV3MRQwEgYDVQQKEwtHb29nbGUgSW5jLjEQMA4GA1UECxMHQW5k"
            + "cm9pZDEQMA4GA1UEAxMHQW5kcm9pZDCCASAwDQYJKoZIhvcNAQEBBQADggENADCCAQgCggEBAKtW"
            + "LgDYO6IIrgqWbxJOKdoR8qtW0I9Y4sypEwPpt1TTcvZApxsdyxMJZ2JORland2qSGT2y5b+3JKke"
            + "dxiLDmpHpDsz2WCbdxgxRczfey5YZnTJ4VZbH0xqWVW/8lGmPav5xVwnIiJS6HXk+BVKZF+JcWjA"
            + "sb/GEuq/eFdpuzSqeYTcfi6idkyugwfYwXFU1+5fZKUaRKYCwkkFQVfcAs1fXA5V+++FGfvjJ/Cx"
            + "URaSxaBvGdGDhfXE28LWuT9ozCl5xw4Yq5OGazvV24mZVSoOO0yZ31j7kYvtwYK6NeADwbSxDdJE"
            + "qO4k//0zOHKrUiGYXtqw/A0LFFtqoZKFjnkCAQOjgdkwgdYwHQYDVR0OBBYEFMd9jMIhF1Ylmn/T"
            + "gt9r45jk14alMIGmBgNVHSMEgZ4wgZuAFMd9jMIhF1Ylmn/Tgt9r45jk14aloXikdjB0MQswCQYD"
            + "VQQGEwJVUzETMBEGA1UECBMKQ2FsaWZvcm5pYTEWMBQGA1UEBxMNTW91bnRhaW4gVmlldzEUMBIG"
            + "A1UEChMLR29vZ2xlIEluYy4xEDAOBgNVBAsTB0FuZHJvaWQxEDAOBgNVBAMTB0FuZHJvaWSCCQDC"
            + "4IdGZEowjTAMBgNVHRMEBTADAQH/MA0GCSqGSIb3DQEBBAUAA4IBAQBt0lLO74UwLDYKqs6Tm8/y"
            + "zKkEu116FmH4rkaymUIE0P9KaMftGlMexFlaYjzmB2OxZyl6euNXEsQH8gjwyxCUKRJNexBiGcCE"
            + "yj6z+a1fuHHvkiaai+KL8W1EyNmgjmyy8AW7P+LLlkR+ho5zEHatRbM/YAnqGcFh5iZBqpknHf1S"
            + "KMXFh4dd239FJ1jWYfbMDMy3NS5CTMQ2XFI1MvcyUTdZPErjQfTbQe3aDQsQcafEQPD+nqActifK"
            + "Z0Np0IS9L9kR/wbNvyz6ENwPiTrjV2KRkEjH78ZMcUQXg0L3BYHJ3lc69Vs5Ddf9uUGGMYldX3Wf"
            + "MBEmh/9iFBDAaTCK";

    private static volatile Signature sPlaySignature;

    private GoogleSignatures() {
    }

    public static boolean isGooglePackage(String packageName) {
        return "com.android.vending".equals(packageName)
                || "com.google.android.gms".equals(packageName)
                || "com.google.android.gsf".equals(packageName)
                || "com.google.android.gsf.login".equals(packageName);
    }

    public static Signature playSignature() {
        Signature signature = sPlaySignature;
        if (signature == null) {
            synchronized (GoogleSignatures.class) {
                signature = sPlaySignature;
                if (signature == null) {
                    signature = new Signature(Base64.decode(PLAY_CERT_B64, Base64.DEFAULT));
                    sPlaySignature = signature;
                }
            }
        }
        return signature;
    }

    /**
     * Replaces the reported signing certificate of a Google package with the
     * host's real one, falling back to the embedded certificate. The host
     * certificate always wins: it may be rotated, and it also carries the
     * SigningInfo that modern callers read.
     */
    public static void apply(PackageInfo target, PackageInfo host) {
        if (target == null) {
            return;
        }
        Signature[] signatures = host == null ? null : host.signatures;
        SigningInfo signingInfo = host == null ? null : host.signingInfo;
        if (signatures == null || signatures.length == 0) {
            signatures = new Signature[]{playSignature()};
        }
        target.signatures = signatures;
        if (signingInfo != null) {
            target.signingInfo = signingInfo;
        }
    }

    /**
     * Reads the real package info from the host package manager. Returns null
     * when the host has no such package (for example a device without Google
     * apps), in which case only the embedded certificate is available.
     */
    public static PackageInfo hostPackageInfo(Method method, Object who, Object[] args) {
        try {
            Object result = method.invoke(who, args);
            if (result instanceof PackageInfo) {
                return (PackageInfo) result;
            }
        } catch (Throwable ignored) {
            // PackageManager.NameNotFoundException and friends: no host copy.
        }
        return null;
    }
}
