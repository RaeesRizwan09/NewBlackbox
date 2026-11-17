package top.niunaijun.blackboxa.app

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackboxa.view.main.BlackBoxLoader


object AppManager {
    private const val TAG = "AppManager"
    private const val KEY_PREFER_MICROG = "prefer_microg"

    @JvmStatic
    val mBlackBoxLoader by lazy {
        try {
            BlackBoxLoader()
        } catch (e: Exception) {
            Log.e(TAG, "Error creating BlackBoxLoader: ${e.message}")

            BlackBoxLoader() 
        }
    }

    @JvmStatic
    val mBlackBoxCore by lazy {
        try {
            mBlackBoxLoader.getBlackBoxCore()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting BlackBoxCore: ${e.message}")
            throw e 
        }
    }

    @JvmStatic
    val mRemarkSharedPreferences: SharedPreferences by lazy {
        try {
            App.getContext().getSharedPreferences("UserRemark", Context.MODE_PRIVATE)
        } catch (e: Exception) {
            Log.e(TAG, "Error creating SharedPreferences: ${e.message}")
            throw e 
        }
    }

    @JvmStatic
    val mGmsSettingsSharedPreferences: SharedPreferences by lazy {
        try {
            App.getContext().getSharedPreferences("GmsSettings", Context.MODE_PRIVATE)
        } catch (e: Exception) {
            Log.e(TAG, "Error creating GmsSettings SharedPreferences: ${e.message}")
            throw e
        }
    }

    fun doAttachBaseContext(context: Context) {
        try {
            mBlackBoxLoader.attachBaseContext(context)
            mBlackBoxLoader.addLifecycleCallback()
        } catch (e: Exception) {
            Log.e(TAG, "Error in doAttachBaseContext: ${e.message}")
            
        }
    }

    fun doOnCreate(context: Context) {
        try {
            mBlackBoxLoader.doOnCreate(context)
            initThirdService(context)
            applyPreferMicroG()
        } catch (e: Exception) {
            Log.e(TAG, "Error in doOnCreate: ${e.message}")
            
        }
    }

    /**
     * Re-applies the persisted "prefer microG" choice to the engine on every
     * process start (the engine flag itself is process-local).
     */
    private fun applyPreferMicroG() {
        try {
            val prefer = mGmsSettingsSharedPreferences.getBoolean(KEY_PREFER_MICROG, false)
            BlackBoxCore.setPreferMicroG(prefer)
        } catch (e: Exception) {
            Log.e(TAG, "Error applying prefer-microG: ${e.message}")
        }
    }

    private fun initThirdService(context: Context) {
        try {
            
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in initThirdService: ${e.message}")
        }
    }
}
