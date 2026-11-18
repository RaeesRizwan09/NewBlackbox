package top.niunaijun.blackbox.utils;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.utils.Slog;


public class AttributionSourceUtils {
    private static final String TAG = "AttributionSourceUtils";

    
    public static void fixAttributionSourceInArgs(Object[] args) {
        fixAttributionSourceInArgs(args, BlackBoxCore.getHostUid(), BlackBoxCore.getHostPkg());
    }

    /**
     * Rewrites every AttributionSource in the arguments to the given uid and
     * package. Sandbox-hosted providers need the caller's virtual uid (the
     * one Binder.getCallingUid() is virtualized to inside the sandbox);
     * host providers need the host uid.
     */
    public static void fixAttributionSourceInArgs(Object[] args, int uid, String pkg) {
        if (args == null) return;
        
        for (int i = 0; i < args.length; i++) {
            Object arg = args[i];
            if (arg != null && arg.getClass().getName().contains("AttributionSource")) {
                try {
                    fixAttributionSourceUid(arg, uid, pkg);
                    Slog.d(TAG, "Fixed AttributionSource UID in method arguments");
                } catch (Exception e) {
                    Slog.w(TAG, "Failed to fix AttributionSource in args: " + e.getMessage());
                }
            }
        }
        
        
        for (int i = 0; i < args.length; i++) {
            Object arg = args[i];
            if (arg != null && arg.getClass().getName().contains("Bundle")) {
                try {
                    fixAttributionSourceInBundle(arg, uid, pkg);
                } catch (Exception e) {
                    Slog.w(TAG, "Failed to fix AttributionSource in Bundle: " + e.getMessage());
                }
            }
        }
    }

    
    public static void fixAttributionSourceUid(Object attributionSource) {
        fixAttributionSourceUid(attributionSource, BlackBoxCore.getHostUid(), BlackBoxCore.getHostPkg());
    }

    public static void fixAttributionSourceUid(Object attributionSource, int uid, String pkg) {
        try {
            if (attributionSource == null) return;
            Class<?> attributionSourceClass = attributionSource.getClass();

            // Android 13 (API 33) wraps the real values in an
            // AttributionSourceState object; AttributionSource itself has no
            // uid or package field. Without this branch the rewrite silently
            // does nothing and the provider still sees the host uid.
            if (setAttributionSourceState(attributionSource, attributionSourceClass, uid, pkg)) {
                return;
            }

            String[] uidFieldNames = {"mUid", "uid", "mCallingUid", "callingUid", "mSourceUid", "sourceUid"};
            boolean uidSet = setField(attributionSource, attributionSourceClass, uidFieldNames, uid);

            if (!uidSet) {
                try {
                    java.lang.reflect.Method setUidMethod =
                            attributionSourceClass.getDeclaredMethod("setUid", int.class);
                    setUidMethod.setAccessible(true);
                    setUidMethod.invoke(attributionSource, uid);
                    uidSet = true;
                } catch (Throwable ignored) {
                }
            }

            String[] packageFieldNames = {"mPackageName", "packageName", "mSourcePackage", "sourcePackage"};
            setField(attributionSource, attributionSourceClass, packageFieldNames, pkg);

            if (uidSet) {
                Slog.d(TAG, "Fixed AttributionSource uid -> " + uid + " pkg -> " + pkg);
            } else {
                Slog.w(TAG, "Could not rewrite AttributionSource uid (no known field)");
            }
        } catch (Throwable e) {
            Slog.w(TAG, "Error fixing AttributionSource UID: " + e.getMessage());
        }
    }

    private static boolean setAttributionSourceState(Object attributionSource, Class<?> clazz,
                                                     int uid, String pkg) {
        try {
            java.lang.reflect.Field stateField = clazz.getDeclaredField("mAttributionSourceState");
            stateField.setAccessible(true);
            Object state = stateField.get(attributionSource);
            if (state == null) return false;
            Class<?> stateClass = state.getClass();
            boolean uidSet = setField(state, stateClass, new String[]{"uid", "mUid", "callingUid"}, uid);
            setField(state, stateClass, new String[]{"packageName", "mPackageName"}, pkg);
            if (uidSet) {
                Slog.d(TAG, "Fixed AttributionSource uid -> " + uid + " pkg -> " + pkg + " (state)");
            }
            return uidSet;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Sets the first field that exists on the class; returns whether one was set. */
    private static boolean setField(Object target, Class<?> clazz, String[] names, Object value) {
        for (String name : names) {
            try {
                java.lang.reflect.Field field = clazz.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    
    public static void fixAttributionSourceInBundle(Object bundle) {
        fixAttributionSourceInBundle(bundle, BlackBoxCore.getHostUid(), BlackBoxCore.getHostPkg());
    }

    public static void fixAttributionSourceInBundle(Object bundle, int uid, String pkg) {
        try {
            if (bundle == null) return;
            
            
            java.lang.reflect.Method keySetMethod = bundle.getClass().getMethod("keySet");
            java.util.Set<String> keys = (java.util.Set<String>) keySetMethod.invoke(bundle);
            
            for (String key : keys) {
                try {
                    java.lang.reflect.Method getMethod = bundle.getClass().getMethod("get", String.class);
                    Object value = getMethod.invoke(bundle, key);
                    
                    if (value != null && value.getClass().getName().contains("AttributionSource")) {
                        fixAttributionSourceUid(value, uid, pkg);
                        Slog.d(TAG, "Fixed AttributionSource UID in Bundle key: " + key);
                    }
                } catch (Exception e) {
                    
                }
            }
        } catch (Exception e) {
            Slog.w(TAG, "Error fixing AttributionSource in Bundle: " + e.getMessage());
        }
    }

    
    public static Object createSafeAttributionSource() {
        try {
            
            Class<?> attributionSourceClass = Class.forName("android.content.AttributionSource");
            
            
            Object attributionSource = null;
            
            try {
                
                java.lang.reflect.Constructor<?> constructor = attributionSourceClass.getDeclaredConstructor(int.class, String.class);
                constructor.setAccessible(true);
                attributionSource = constructor.newInstance(BlackBoxCore.getHostUid(), BlackBoxCore.getHostPkg());
            } catch (Exception e) {
                try {
                    
                    java.lang.reflect.Constructor<?> constructor = attributionSourceClass.getDeclaredConstructor();
                    constructor.setAccessible(true);
                    attributionSource = constructor.newInstance();
                    
                    
                    fixAttributionSourceUid(attributionSource);
                } catch (Exception e2) {
                    Slog.w(TAG, "Could not create safe AttributionSource: " + e2.getMessage());
                    return null;
                }
            }
            
            return attributionSource;
        } catch (Exception e) {
            Slog.w(TAG, "Error creating safe AttributionSource: " + e.getMessage());
            return null;
        }
    }

    
    public static boolean validateAttributionSource(Object attributionSource) {
        try {
            if (attributionSource == null) return false;
            
            
            Class<?> attributionSourceClass = attributionSource.getClass();
            String[] uidFieldNames = {"mUid", "uid", "mCallingUid", "callingUid", "mSourceUid", "sourceUid"};
            
            for (String fieldName : uidFieldNames) {
                try {
                    java.lang.reflect.Field uidField = attributionSourceClass.getDeclaredField(fieldName);
                    uidField.setAccessible(true);
                    Object uidValue = uidField.get(attributionSource);
                    if (uidValue instanceof Integer) {
                        int uid = (Integer) uidValue;
                        if (uid > 0) {
                            Slog.d(TAG, "AttributionSource UID validation passed: " + uid);
                            return true;
                        }
                    }
                } catch (Exception e) {
                    
                }
            }
            
            
            Slog.w(TAG, "AttributionSource validation failed, attempting to fix");
            fixAttributionSourceUid(attributionSource);
            return true;
            
        } catch (Exception e) {
            Slog.w(TAG, "Error validating AttributionSource: " + e.getMessage());
            return false;
        }
    }
}
