package top.niunaijun.blackbox.fake.service;

import android.app.AppOpsManager;
import android.app.SyncNotedAppOp;
import android.content.Context;
import android.os.Build;
import android.os.IBinder;

import java.lang.reflect.Method;

import black.android.app.BRAppOpsManager;
import black.android.os.BRServiceManager;
import black.com.android.internal.app.BRIAppOpsServiceStub;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.utils.MethodParameterUtils;
import top.niunaijun.blackbox.utils.Slog;


/**
 * Proxy for the IAppOpsService binder.
 * <p>
 * Since Android 12 (API 31) the system binder methods {@code checkOperation},
 * {@code checkOperationForDevice}, {@code noteOperation}, {@code noteProxyOperation} and
 * {@code startOperation} return a {@link SyncNotedAppOp} object instead of the raw int mode.
 * Returning {@code AppOpsManager.MODE_ALLOWED} from those hooks crashes on API 31+ clients
 * with {@code ClassCastException: Couldn't convert result of type java.lang.Integer to
 * android.app.SyncNotedAppOp} (observed on WhatsApp v2.26 inside the sandbox, worker thread
 * calling {@code Environment.isExternalStorageLegacy() -> AppOpsManager.noteOpNoThrow}).
 * <p>
 * This proxy keeps the "always allow" semantics of the sandbox but returns correctly typed
 * results: on API 31+ a {@link SyncNotedAppOp} forced to MODE_ALLOWED, on older APIs the
 * int mode.
 */
public class IAppOpsManagerProxy extends BinderInvocationStub {
    public IAppOpsManagerProxy() {
        super(BRServiceManager.get().getService(Context.APP_OPS_SERVICE));
    }

    @Override
    protected Object getWho() {
        IBinder call = BRServiceManager.get().getService(Context.APP_OPS_SERVICE);
        return BRIAppOpsServiceStub.get().asInterface(call);
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        if (BRAppOpsManager.get(null)._check_mService() != null) {
            AppOpsManager appOpsManager = (AppOpsManager) BlackBoxCore.getContext().getSystemService(Context.APP_OPS_SERVICE);
            try {
                BRAppOpsManager.get(appOpsManager)._set_mService(getProxyInvocation());
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        replaceSystemService(Context.APP_OPS_SERVICE);
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String methodName = method.getName();

        try {
            // check*/note*/start* are dispatched through the hook map (super.invoke) so that
            // the registered MethodHooks run and the result coercion below applies.
            // Returning a bare int here (the old behaviour) breaks API 31+ clients.
            if (methodName.startsWith("check") ||
                methodName.startsWith("note") ||
                methodName.startsWith("start")) {
                Object result = super.invoke(proxy, method, args);
                return coerceAllowed(args, result);
            }

            if (methodName.startsWith("finish")) {
                return null;
            }

            MethodParameterUtils.replaceFirstAppPkg(args);
            MethodParameterUtils.replaceLastUid(args);
            return super.invoke(proxy, method, args);
        } catch (SecurityException e) {
            Slog.w(TAG, "AppOps invoke: SecurityException caught for " + methodName + ", allowing operation", e);
            return allowedResult(method, args);
        } catch (Throwable e) {
            Slog.e(TAG, "AppOps invoke: Error in method " + methodName, e);
            return allowedResult(method, args);
        }
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    /**
     * Force any op-related result into "allowed" while keeping its runtime type.
     */
    private static Object coerceAllowed(Object[] args, Object result) {
        try {
            if (result instanceof SyncNotedAppOp) {
                SyncNotedAppOp noted = (SyncNotedAppOp) result;
                if (noted.getOpMode() != AppOpsManager.MODE_ALLOWED) {
                    return new SyncNotedAppOp(AppOpsManager.MODE_ALLOWED,
                            noted.getOp(), noted.getOpPackageName(), noted.getAttributionTag());
                }
                return noted;
            }
            if (result instanceof Integer) {
                int mode = (Integer) result;
                if (mode == AppOpsManager.MODE_ALLOWED) {
                    return result;
                }
                return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                        ? allowedResult(null, args)
                        : AppOpsManager.MODE_ALLOWED;
            }
        } catch (Throwable t) {
            Slog.w(TAG, "AppOps coerceAllowed: failed to coerce, using legacy allow " + t);
        }
        return result;
    }

    /**
     * Synthesize a correctly-typed "allowed" result from the call arguments.
     * The service binder signatures are (op, packageName[, attributionTag[, ...]]).
     */
    private static Object allowedResult(Method method, Object[] args) {
        try {
            if (args != null && args.length > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Integer code = null;
                String packageName = null;
                String attributionTag = null;
                if (args[0] instanceof Integer) {
                    code = (Integer) args[0];
                }
                if (args.length > 1 && args[1] instanceof String) {
                    packageName = (String) args[1];
                }
                if (args.length > 2 && args[2] instanceof String) {
                    attributionTag = (String) args[2];
                }
                String op = code != null ? getOpPublicName(code) : null;
                return new SyncNotedAppOp(AppOpsManager.MODE_ALLOWED, op, packageName, attributionTag);
            }
        } catch (Throwable ignored) {
        }
        return AppOpsManager.MODE_ALLOWED;
    }

    /**
     * Call through to the real service and force the result to "allowed" with the
     * correct type for the running API level.
     */
    private static Object allowAll(Object who, Method method, Object[] args) throws Throwable {
        try {
            Object result = method.invoke(who, args);
            return coerceAllowed(args, result);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            Slog.w(TAG, "AppOps allowAll: real call failed for " + method.getName() + ", synthesizing allow " + cause);
            return allowedResult(method, args);
        }
    }

    @ProxyMethod("noteProxyOperation")
    public static class NoteProxyOperation extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return allowAll(who, method, args);
        }
    }

    @ProxyMethod("checkPackage")
    public static class CheckPackage extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            // IAppOpsService.checkPackage returns an int mode on all API levels.
            return AppOpsManager.MODE_ALLOWED;
        }
    }

    @ProxyMethod("checkOperation")
    public static class CheckOperation extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return allowAll(who, method, args);
        }
    }

    @ProxyMethod("checkOperationForDevice")
    public static class CheckOperationForDevice extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return allowAll(who, method, args);
        }
    }

    @ProxyMethod("noteOperation")
    public static class NoteOperation extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return allowAll(who, method, args);
        }
    }

    @ProxyMethod("startOperation")
    public static class StartOperation extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return allowAll(who, method, args);
        }
    }

    @ProxyMethod("finishOp")
    public static class FinishOp extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            // No registered hook can match the system method name; finishOp was previously
            // dead code. finishOperation() goes through the finish fast-path in invoke().
            return null;
        }
    }

// The following names exist only on the client-side AppOpsManager facade, not on the
// IAppOpsService binder; keep them for compatibility but they never fire.

    @ProxyMethod("checkOpNoThrow")
    public static class CheckOpNoThrow extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return allowAll(who, method, args);
        }
    }

    @ProxyMethod("noteOp")
    public static class NoteOp extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return allowAll(who, method, args);
        }
    }

    @ProxyMethod("noteOpNoThrow")
    public static class NoteOpNoThrow extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return allowAll(who, method, args);
        }
    }

    @ProxyMethod("startOp")
    public static class StartOp extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return allowAll(who, method, args);
        }
    }

    @ProxyMethod("startOpNoThrow")
    public static class StartOpNoThrow extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return allowAll(who, method, args);
        }
    }

    private static boolean isMediaStorageOrAudioOp(String opPublicNameOrStr) {
        if (opPublicNameOrStr == null) return false;

        String n = opPublicNameOrStr.toUpperCase();
        return n.contains("READ_MEDIA")
                || n.contains("READ_EXTERNAL_STORAGE")
                || n.contains("RECORD_AUDIO")
                || n.contains("CAPTURE_AUDIO_OUTPUT")
                || n.contains("MODIFY_AUDIO_SETTINGS")
                || n.contains("AUDIO")
                || n.contains("MICROPHONE")
                || n.contains("FOREGROUND_SERVICE")
                || n.contains("SYSTEM_ALERT_WINDOW")
                || n.contains("WRITE_SETTINGS")
                || n.contains("ACCESS_FINE_LOCATION")
                || n.contains("ACCESS_COARSE_LOCATION")
                || n.contains("CAMERA")
                || n.contains("BODY_SENSORS")
                || n.contains("BLUETOOTH_SCAN")
                || n.contains("BLUETOOTH_CONNECT")
                || n.contains("BLUETOOTH_ADVERTISE")
                || n.contains("NEARBY_WIFI_DEVICES")
                || n.contains("POST_NOTIFICATIONS");
    }

    private static String getOpPublicName(int op) {
        try {
            java.lang.reflect.Method m = AppOpsManager.class.getMethod("opToPublicName", int.class);
            Object name = m.invoke(null, op);
            return name != null ? name.toString() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
