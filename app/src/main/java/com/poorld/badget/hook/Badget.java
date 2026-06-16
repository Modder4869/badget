package com.poorld.badget.hook;

import static com.poorld.badget.utils.ConfigUtils.DBAGET_PKG_NAME;

import android.app.Application;
import android.content.Context;
import android.util.Log;

import com.poorld.badget.MainActivity;
import com.poorld.badget.entity.ConfigEntity;
import com.poorld.badget.entity.InteractionType;
import com.poorld.badget.utils.CommonUtils;
import com.poorld.badget.utils.ConfigUtils;
import com.poorld.badget.utils.LoadLibraryUtil;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class Badget implements IXposedHookLoadPackage {

    public static final String TAG = "Badget#";

    private ConfigEntity mConfig;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) throws Throwable {
        Log.d(TAG, "handleLoadPackage: " + loadPackageParam.packageName);

        // Handle own package (UI hook)
        if (DBAGET_PKG_NAME.equals(loadPackageParam.packageName)) {
            XposedHelpers.findAndHookMethod("com.poorld.badget.MainActivity",
                    loadPackageParam.classLoader, "isModuleActive",
                    new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam methodHookParam) {
                            return true;
                        }
                    });
            return;
        }

        // Load config
        if (mConfig == null) {
            if (ConfigUtils.mConfigCache == null) {
                ConfigUtils.initConfig();
            }
            mConfig = ConfigUtils.mConfigCache;
        }
        if (mConfig == null || !mConfig.isEnabled()) {
            Log.d(TAG, "Config disabled or null");
            return;
        }

        Map<String, ConfigEntity.PkgConfig> pkgConfigs = mConfig.getPkgConfigs();
        ConfigEntity.PkgConfig pkgConfig = pkgConfigs.get(loadPackageParam.packageName);
        if (pkgConfig == null || !pkgConfig.isEnabled()) {
            Log.d(TAG, loadPackageParam.packageName + " not enabled.");
            return;
        }

        // --- Get Application Context (same as FridaXposed module) ---
        Context context = getApplicationContext();
        if (context == null) {
            Log.e(TAG, "Failed to get Application context");
            return;
        }

        // --- Perform injection directly (no hooks) ---
        try {
            String applibDir = ConfigUtils.getAppPrivLibDir(context);
            String ABI = android.os.Process.is64Bit() ? ConfigUtils.ABI_V8A : ConfigUtils.ABI_V7A;

            File appGadgetLib = ConfigUtils.getAppGadgetLibPath(context, pkgConfig.getSoName());
            if (!appGadgetLib.exists()) {
                String gadgetLibName = ConfigUtils.getGadgetLibName(pkgConfig.getSoName());
                CommonUtils.copyFile(ConfigUtils.getBadgetDataPath() + ABI, applibDir, gadgetLibName);
                Log.d(TAG, "Copied gadget .so to " + appGadgetLib.getAbsolutePath());
            }

            ConfigUtils.saveAppGadgetConfig(context, pkgConfig);
            Log.d(TAG, "Saved config .so");

            File appGadgetConfigLibPath = ConfigUtils.getAppGadgetConfigPath(context, pkgConfig.getSoName());
            if (!appGadgetConfigLibPath.exists()) {
                Log.e(TAG, "Config .so not found: " + appGadgetConfigLibPath.getAbsolutePath());
                return;
            }

            if (pkgConfig.getType() == InteractionType.Script) {
                File hookJsFile = new File(pkgConfig.getJsPath());
                if (!hookJsFile.exists()) {
                    Log.e(TAG, "Hook script not found: " + hookJsFile.getAbsolutePath());
                    return;
                }
            } else if (pkgConfig.getType() == InteractionType.ScriptDirectory) {
                File[] scripts = ConfigUtils.getDirScripts(pkgConfig.getPkgName());
                if (scripts == null || scripts.length == 0) {
                    Log.e(TAG, "No scripts found in script directory");
                    return;
                }
            }

            if (appGadgetLib.exists()) {
                System.load(appGadgetLib.getAbsolutePath());
                Log.d(TAG, "Gadget loaded successfully from " + appGadgetLib.getAbsolutePath());
            } else {
                Log.e(TAG, "Gadget .so missing: " + appGadgetLib.getAbsolutePath());
            }

        } catch (Throwable t) {
            Log.e(TAG, "Injection failed", t);
        }
    }

    // --- Helper to get Application Context (mirrors AppUtils.createAppContext) ---
    private Context getApplicationContext() {
        try {
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Method currentActivityThreadMethod = activityThreadClass.getDeclaredMethod("currentActivityThread");
            currentActivityThreadMethod.setAccessible(true);
            Object activityThreadObj = currentActivityThreadMethod.invoke(null);

            Field boundApplicationField = activityThreadClass.getDeclaredField("mBoundApplication");
            boundApplicationField.setAccessible(true);
            Object mBoundApplication = boundApplicationField.get(activityThreadObj);

            Field infoField = mBoundApplication.getClass().getDeclaredField("info");
            infoField.setAccessible(true);
            Object loadedApkObj = infoField.get(mBoundApplication);

            Class<?> contextImplClass = Class.forName("android.app.ContextImpl");
            Method createAppContextMethod = contextImplClass.getDeclaredMethod(
                    "createAppContext", activityThreadClass, loadedApkObj.getClass());
            createAppContextMethod.setAccessible(true);
            Object context = createAppContextMethod.invoke(null, activityThreadObj, loadedApkObj);

            if (context instanceof Context) {
                return (Context) context;
            }
        } catch (Exception e) {
            Log.e(TAG, "getApplicationContext failed: " + e.getMessage());
        }
        return null;
    }

    // --- Optional logging hook (kept for compatibility) ---
    public void hookLog(String method) {
        XposedHelpers.findAndHookMethod(Log.class, method, String.class, String.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                super.beforeHookedMethod(param);
                XposedBridge.log("hookLog[" + param.args[0] + "] " + param.args[1]);
            }
        });
    }
}
