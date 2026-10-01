package com.xgj.rotatefix;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import java.util.Map;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * XTC ND08 (monaco_go / Android 11) 屏幕旋转解锁。
 *
 * 该 ROM 把旋转用三层配置关死：
 *   1) framework-res.apk  config_supportAutoRotation = false   -> DisplayRotation.mSupportAutoRotation
 *   2) SettingsProvider   def_accelerometer_rotation = false   -> 开机 accelerometer_rotation=0（锁定态）
 *   3) wearable_excluded_core_hardware.xml  unavailable-feature screen.landscape
 *
 * 本模块只 hook system_server（包名 "android"），运行时把这三处打开，不改任何 ROM 文件。
 */
public class RotateFixHook implements IXposedHookLoadPackage {

    private static final String TAG = "[XgjRotateFix] ";
    private static final String LOGCAT_TAG = "XgjRotateFix";

    /** 只注入 system_server（SystemUI 侧的旋转崩溃修复已移交 ReXTCsettings 模块的 BV） */
    private static final String TARGET_PKG = "android";

    private static final String CLS_DISPLAY_ROTATION = "com.android.server.wm.DisplayRotation";
    private static final String CLS_PMS = "com.android.server.pm.PackageManagerService";

    /** App / adb 发送控制命令的广播 */
    public static final String ACTION_SET = "com.xgj.rotatefix.SET";

    /** 开机是否默认打开自动旋转（1=是，默认；0=否）。由模块 App 或广播写入 Settings.System */
    public static final String KEY_DEFAULT_AUTO = "xgj_rotate_default_auto";
    /** 模块存活标记，App 读它来判断 hook 有没有生效 */
    public static final String KEY_HOOK_ALIVE = "xgj_rotate_hook_alive";

    private static final int USER_SYSTEM = 0;

    // ---- ActivityInfo 屏幕方向常量（android.jar 里可用，但这里显式写出来更直观） ----
    private static final int ORIENT_LANDSCAPE = 0;
    private static final int ORIENT_PORTRAIT = 1;
    private static final int ORIENT_SENSOR_LANDSCAPE = 6;
    private static final int ORIENT_SENSOR_PORTRAIT = 7;
    private static final int ORIENT_REVERSE_LANDSCAPE = 8;
    private static final int ORIENT_REVERSE_PORTRAIT = 9;
    private static final int ORIENT_UNSPECIFIED = -1;
    private static final int ORIENT_USER = 2;
    private static final int ORIENT_SENSOR = 4;
    private static final int ORIENT_NOSENSOR = 5;
    private static final int ORIENT_FULL_SENSOR = 10;
    private static final int ORIENT_USER_LANDSCAPE = 11;
    private static final int ORIENT_USER_PORTRAIT = 12;
    private static final int ORIENT_FULL_USER = 13;

    private static final int ORIENT_BEHIND = 3;
    /** WindowContainer.ORIENTATION_UNSET：聚合时"本容器没有意见，跳过" */
    private static final int ORIENTATION_UNSET = -2;

    private static final int USER_ROTATION_FREE = 0;
    private static final int USER_ROTATION_LOCKED = 1;

    /** 总闸是否成功改成 true（false 时走 rotationForOrientation 兜底逻辑） */
    private static volatile boolean sSwitchOk = false;
    private static volatile boolean sInited = false;
    private static volatile Object sPms = null;

    private static void log(String s) {
        try {
            XposedBridge.log(TAG + s);
        } catch (Throwable ignored) {
        }
        try {
            android.util.Log.i(LOGCAT_TAG, s);
        } catch (Throwable ignored) {
        }
    }

    // Settings.System.getIntForUser / putIntForUser 是 @hide，公开 android.jar 里没有，走反射。
    private static int sysGetIntForUser(ContentResolver cr, String key, int def, int user) {
        try {
            Object r = XposedHelpers.callStaticMethod(
                    Settings.System.class, "getIntForUser", cr, key, def, user);
            if (r instanceof Integer) {
                return (Integer) r;
            }
        } catch (Throwable t) {
            log("getIntForUser(" + key + ") 失败: " + t);
        }
        try {
            return Settings.System.getInt(cr, key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    private static void sysPutIntForUser(ContentResolver cr, String key, int value, int user) {
        try {
            XposedHelpers.callStaticMethod(
                    Settings.System.class, "putIntForUser", cr, key, value, user);
        } catch (Throwable t) {
            log("putIntForUser(" + key + ") 失败: " + t);
        }
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET_PKG.equals(lpparam.packageName)) {
            return;
        }
        hookSystemServer(lpparam);
    }

    // =====================================================================
    //  system_server：解除旋转封锁
    //
    //  注：让旋转生效后会连带触发 SystemUI 的
    //  RotationButtonController.shouldOverrideUserLockPrefs() →
    //  RotationPolicy.getNaturalRotation()（本 ROM framework 里没有）
    //  → NoSuchMethodError 崩溃循环。该修复已移到 ReXTCsettings 模块的
    //  「修复 BV」，本模块不再注入 com.android.systemui。
    // =====================================================================
    private void hookSystemServer(XC_LoadPackage.LoadPackageParam lpparam) {
        log("=== loaded in system_server, android " + android.os.Build.VERSION.SDK_INT
                + " (" + android.os.Build.DISPLAY + ") ===");

        final ClassLoader cl = lpparam.classLoader;

        // ---------- 0. 抓 PMS 实例（后面用来补 screen.landscape 特性） ----------
        try {
            Class<?> pmsCls = XposedHelpers.findClass(CLS_PMS, cl);
            XposedBridge.hookAllConstructors(pmsCls, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    sPms = param.thisObject;
                }
            });
        } catch (Throwable t) {
            log("hook PMS ctor failed: " + t);
        }

        // ---------- 1. DisplayRotation 构造器：把总闸打开 ----------
        Class<?> drCls = null;
        try {
            drCls = XposedHelpers.findClass(CLS_DISPLAY_ROTATION, cl);
        } catch (Throwable t) {
            log("FATAL: DisplayRotation not found: " + t);
            return;
        }

        final Class<?> drClass = drCls;
        try {
            XposedBridge.hookAllConstructors(drClass, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        applyFix(param.thisObject);
                        XposedHelpers.callMethod(param.thisObject, "updateOrientationListener");
                    } catch (Throwable t) {
                        log("ctor after-hook err: " + t);
                    }
                    if (!sInited) {
                        sInited = true;
                        try {
                            onFirstInit(param.thisObject);
                        } catch (Throwable t) {
                            log("onFirstInit err: " + t);
                        }
                    }
                }
            });
            log("hooked DisplayRotation constructors");
        } catch (Throwable t) {
            log("hook DisplayRotation ctor failed: " + t);
        }

        // ---------- 2. needSensorRunning() 永远放行 ----------
        // 原逻辑在 mUserRotationMode==LOCKED 且 mShowRotationSuggestions==0（本 ROM 因
        // ro.config.low_ram=true 强置 0）时返回 false，导致方向传感器监听永不开启。
        try {
            XposedHelpers.findAndHookMethod(drClass, "needSensorRunning", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object fixed = XposedHelpers.callMethod(param.thisObject, "isFixedToUserRotation");
                        param.setResult(!Boolean.TRUE.equals(fixed));
                    } catch (Throwable t) {
                        param.setResult(Boolean.TRUE);
                    }
                }
            });
            log("hooked needSensorRunning()");
        } catch (Throwable t) {
            log("hook needSensorRunning failed: " + t);
        }

        // ---------- 3. 兜底：万一 final 字段改不动，直接修正返回值 ----------
        try {
            XposedHelpers.findAndHookMethod(drClass, "rotationForOrientation",
                    int.class, int.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            // 有些 ROM 会把配置读回，这里每次再确认一遍（幂等，开销极小）
                            try {
                                applyFix(param.thisObject);
                            } catch (Throwable ignored) {
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!sSwitchOk) {
                                try {
                                    fallbackRotation(param);
                                } catch (Throwable t) {
                                    log("fallback err: " + t);
                                }
                            }
                            // 总闸生效与否都要做：用户显式锁定的方向不能被 NOSENSOR 压掉
                            try {
                                keepUserRotationForNoSensor(param);
                            } catch (Throwable t) {
                                log("NOSENSOR 兜底 err: " + t);
                            }
                            // 传感器没有方向提议（手表平放）时，别"沿用上次的旋转"
                            try {
                                fallbackToNaturalWhenSensorIdle(param);
                            } catch (Throwable t) {
                                log("传感器回落 err: " + t);
                            }
                        }
                    });
            log("hooked rotationForOrientation()");
        } catch (Throwable t) {
            log("hook rotationForOrientation failed: " + t);
        }

        // ---------- 4. 【关键】updateRotationUnchecked() 被厂商掏空，按 AOSP 11 原实现补回 ----------
        // 本 ROM 的 smali:
        //     .method updateRotationUnchecked(Z)Z
        //         .locals 0 / const/4 p1, 0x0 / return p1
        //     .end method
        // 它是整个旋转链路唯一的施加点，导致无论配置怎么改屏幕都不会转。
        try {
            XposedHelpers.findAndHookMethod(drClass, "updateRotationUnchecked",
                    boolean.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                param.setResult(doUpdateRotation(
                                        param.thisObject, (Boolean) param.args[0]));
                            } catch (Throwable t) {
                                log("doUpdateRotation 失败（回落到原空实现）: " + t);
                            }
                        }
                    });
            log("hooked updateRotationUnchecked() [BUILD 2]");
        } catch (Throwable t) {
            log("hook updateRotationUnchecked failed: " + t);
        }

        // ---------- 5.【关键·第三处阉割】补回 ATMS 删掉的 ensureVisibilityAndConfig ----------
        // 本 ROM 的 ActivityTaskManagerService.setRequestedOrientation(IBinder,int) 里，
        // AOSP 的这一句被厂商整行删掉了：
        //     r.setRequestedOrientation(requestedOrientation);
        //     mRootWindowContainer.ensureVisibilityAndConfig(r, r.getDisplayId(),
        //             false /*markFrozenIfConfigChanged*/, true /*deferResume*/);   <-- 没了
        //
        // 后果：App 调 Activity.setRequestedOrientation() 之后，方向只写进了
        // ActivityRecord.mOrientation（dumpsys 里能看到 mOrientation=0 横屏），
        // 但没人通知显示层 —— DisplayContent.updateOrientation() 从不被调用，
        // DisplayRotation.mCurrentAppOrientation 一直停在 SCREEN_ORIENTATION_USER，
        // 屏幕自然不转。这就是「App Settings 的屏幕方向覆盖不生效」的原因。
        //
        // 补的位置：ActivityRecord.setRequestedOrientation(I)V 的 after 钩子。
        // 全 ROM 只有 ATMS.setRequestedOrientation 一处调它（已核实），所以在这里补
        // 与 AOSP 在 ATMS 里补完全等价；而且此时 ATMS 的 synchronized(mGlobalLock)
        // 仍然持有，ensureVisibilityAndConfig 需要的锁是满足的
        // （挂在 ATMS 的 after 钩子里反而已经出了同步块，会缺锁）。
        try {
            Class<?> arCls = XposedHelpers.findClass(
                    "com.android.server.wm.ActivityRecord", cl);
            XposedHelpers.findAndHookMethod(arCls, "setRequestedOrientation",
                    int.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                final Object r = param.thisObject;
                                final Object atm = XposedHelpers.getObjectField(r, "mAtmService");
                                final Object root =
                                        XposedHelpers.getObjectField(atm, "mRootWindowContainer");
                                final int displayId =
                                        (Integer) XposedHelpers.callMethod(r, "getDisplayId");
                                XposedHelpers.callMethod(root, "ensureVisibilityAndConfig",
                                        r, displayId, false, true);
                            } catch (Throwable t) {
                                log("补 ensureVisibilityAndConfig 失败: " + t);
                            }
                        }
                    });
            log("hooked ActivityRecord.setRequestedOrientation() [BUILD 4]");
        } catch (Throwable t) {
            log("hook ActivityRecord.setRequestedOrientation failed: " + t);
        }

        // ---------- 6.【关键·真正的最后一道闸】取消「正方形显示区忽略 App 方向请求」启发式 ----------
        // DisplayContent.configureDisplayPolicy() 里：
        //     mIgnoreRotationForApps = isNonDecorDisplayCloseToSquare(0, w, h);
        // 这是 AOSP 为折叠屏加的启发式：显示区（去掉状态栏/导航栏后的非装饰区）
        // 长宽比落在 [0.909, 1.1] 就认定"接近正方形"，于是 DisplayContent.getOrientation()
        // 第一句直接：
        //     if (mIgnoreRotationForApps) return SCREEN_ORIENTATION_USER;   // 2
        // 本机屏幕 416x468，扣掉系统栏后非装饰区几乎是正方形 → 恒为 true →
        // 任何 App 请求的方向都传不到显示层（mCurrentAppOrientation 永远是 USER）。
        //
        // 手表本来就该允许 App 自己定方向，所以这里把这个标志清掉。
        // configureDisplayPolicy() 每次显示配置变化都会重算，所以两边都要兜：
        // 一个在重算之后清，一个在真正读它的 getOrientation() 之前清。
        try {
            Class<?> dcCls = XposedHelpers.findClass("com.android.server.wm.DisplayContent", cl);
            XposedBridge.hookAllMethods(dcCls, "configureDisplayPolicy", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    clearIgnoreRotationForApps(param.thisObject);
                }
            });
            XposedHelpers.findAndHookMethod(dcCls, "getOrientation", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    clearIgnoreRotationForApps(param.thisObject);
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    homeWinsWhenFocused(param);
                }
            });
            log("hooked DisplayContent.configureDisplayPolicy()/getOrientation() [BUILD 5]");
        } catch (Throwable t) {
            log("hook DisplayContent 失败: " + t);
        }

    }

    /**
     * 桌面在前台时，直接用桌面自己的方向，跳过那套会被下层任务带跑的方向聚合。
     *
     * DisplayContent.getOrientation() 最终走 WindowContainer.getOrientation(I) 的自上而下聚合，
     * 规则是「子节点返回 UNDEFINED(-1) 且 fillsParent()==true → 继续问下一个」。本 ROM 的：
     *
     *   桌面 HomeActivity  mOccludesParent=false（半透明）
     *     → 它的 Task#3 正确地返回 -1
     *       → 但外层 home root Task#1 看到「子 -1 + Task.fillsParent()==true」→ 继续往下问
     *         → 问到下层那个仍然 visible=true 的 App 任务（如 App Settings 设成横屏的 MT）
     *           → 拿到 USER_LANDSCAPE(11) → 循环结束返回 p1=UNSET(-2)（"我没意见"）
     *             → 聚合继续，MT 的 11 胜出 → **回到桌面也回不去竖屏**
     *
     * AOSP 在手机上不踩这个坑，是因为按 HOME 后下层任务会被置为不可见；这 ROM 的半透明
     * 桌面把它留成了可见。
     *
     * 修法不去动那套聚合（对上层其它容器有副作用），只在结果层面兜底：本显示区当前焦点
     * 是 home（带 android.intent.category.HOME）时，用桌面自己请求的方向。桌面请求的是
     * UNSPECIFIED(-1)，后续：
     *   · 自动旋转模式 → 走传感器分支，平放时由 fallbackToNaturalWhenSensorIdle 回落竖屏
     *   · 手动锁定模式 → 由 keepUserRotationForNoSensor / rotationForOrientation 返回用户方向
     * 两条路都不会把桌面顶成横屏。
     */
    private static void homeWinsWhenFocused(XC_MethodHook.MethodHookParam param) {
        try {
            final Object dc = param.thisObject;
            final Object wms = XposedHelpers.getObjectField(dc, "mWmService");
            final Object root = XposedHelpers.getObjectField(wms, "mRoot");
            // 判据必须用「真正 resumed 的那个 Activity」。
            // 不能用 TaskDisplayArea.getFocusedActivity()：本 ROM 的 LauncherKeyguardWindow
            // 常驻持有窗口焦点，App 在前台时它照样返回桌面，会把 App 的方向一起压掉。
            final Object resumed = XposedHelpers.callMethod(root, "getTopResumedActivity");
            if (resumed == null) {
                return;
            }
            final Object intent = XposedHelpers.getObjectField(resumed, "intent");
            if (intent == null || !Boolean.TRUE.equals(XposedHelpers.callMethod(
                    intent, "hasCategory", "android.intent.category.HOME"))) {
                return;
            }
            final int homeOrientation = XposedHelpers.getIntField(resumed, "mOrientation");
            if (homeOrientation != (Integer) param.getResult()) {
                param.setResult(homeOrientation);
                log("桌面在前台 → 用桌面自身方向 "
                        + (homeOrientation == ORIENT_UNSPECIFIED
                                ? "UNSPECIFIED(-1)" : String.valueOf(homeOrientation))
                        + "，跳过会被下层任务带跑的聚合");
            }
        } catch (Throwable t) {
            log("桌面方向兜底失败: " + t);
        }
    }

    /**
     * 清掉 DisplayContent.mIgnoreRotationForApps。
     * true 时 getOrientation() 无条件返回 SCREEN_ORIENTATION_USER，
     * App 通过 setRequestedOrientation 请求的方向（甚至 ActivityRecord.mOrientation
     * 已经写成 0=横屏）都会被丢掉。
     */
    private static void clearIgnoreRotationForApps(Object dc) {
        try {
            if (XposedHelpers.getBooleanField(dc, "mIgnoreRotationForApps")) {
                XposedHelpers.setBooleanField(dc, "mIgnoreRotationForApps", false);
                log("mIgnoreRotationForApps: true -> false（416x468 去装饰后近似正方，"
                        + "AOSP 的正方屏启发式会把 App 的方向请求全丢掉）");
            }
        } catch (Throwable t) {
            log("清 mIgnoreRotationForApps 失败: " + t);
        }
    }

    // =====================================================================
    //  核心：打开 DisplayRotation 的四个开关
    // =====================================================================

    private static void applyFix(Object dr) {
        // mSupportAutoRotation：总闸。原为 false，导致 rotationForOrientation 对
        // UNSPECIFIED/USER/SENSOR/FULL_SENSOR 等一律返回 ROTATION_0，且传感器监听不启动。
        try {
            if (!XposedHelpers.getBooleanField(dr, "mSupportAutoRotation")) {
                XposedHelpers.setBooleanField(dr, "mSupportAutoRotation", true);
                log("mSupportAutoRotation: false -> true");
            }
        } catch (Throwable t) {
            log("set mSupportAutoRotation failed: " + t);
        }

        // mSupportWristRotation：厂商判断里的附加条件（配合 ro.product.qti.qcom_watch=true），
        // 打开后即使锁定态也尊重 Settings.System.USER_ROTATION。
        try {
            if (!XposedHelpers.getBooleanField(dr, "mSupportWristRotation")) {
                XposedHelpers.setBooleanField(dr, "mSupportWristRotation", true);
                log("mSupportWristRotation: false -> true");
            }
        } catch (Throwable t) {
            log("set mSupportWristRotation failed: " + t);
        }

        // mAllowAllRotations：三态 int，-1=未初始化 / 0=关 / 1=开。置 1 允许 180° 翻转，
        // 并避免 updateSettings 之后又去读 config_allowAllRotations(false)。
        try {
            if (XposedHelpers.getIntField(dr, "mAllowAllRotations") != 1) {
                XposedHelpers.setIntField(dr, "mAllowAllRotations", 1);
                log("mAllowAllRotations -> 1 (允许 180 度)");
            }
        } catch (Throwable t) {
            log("set mAllowAllRotations failed: " + t);
        }

        try {
            sSwitchOk = XposedHelpers.getBooleanField(dr, "mSupportAutoRotation");
        } catch (Throwable t) {
            sSwitchOk = false;
        }
    }

    /**
     * 兜底路径：只有在 mSupportAutoRotation 改不动时才用。
     * 自己按 AOSP 的语义补出「系统决定的那些方向」应有的结果。
     */
    private static void fallbackRotation(XC_MethodHook.MethodHookParam param) {
        final Object dr = param.thisObject;
        final int orientation = (Integer) param.args[0];
        final int lastRotation = (Integer) param.args[1];

        // App 显式声明的横/竖屏，原逻辑已经能给出正确结果，不动
        switch (orientation) {
            case ORIENT_LANDSCAPE:
            case ORIENT_PORTRAIT:
            case ORIENT_SENSOR_LANDSCAPE:
            case ORIENT_SENSOR_PORTRAIT:
            case ORIENT_REVERSE_LANDSCAPE:
            case ORIENT_REVERSE_PORTRAIT:
            case ORIENT_USER_LANDSCAPE:
            case ORIENT_USER_PORTRAIT:
                return;
            default:
                break;
        }

        final int mode = XposedHelpers.getIntField(dr, "mUserRotationMode");
        final int userRotation = XposedHelpers.getIntField(dr, "mUserRotation");

        int want = -1;
        if (mode == USER_ROTATION_LOCKED) {
            // 手动锁定：用 Settings.System.USER_ROTATION
            want = userRotation;
        } else {
            // 自动旋转：取方向传感器给出的角度
            try {
                Object listener = XposedHelpers.getObjectField(dr, "mOrientationListener");
                if (listener != null) {
                    Object proposed = XposedHelpers.callMethod(listener, "getProposedRotation");
                    if (proposed instanceof Integer) {
                        want = (Integer) proposed;
                    }
                }
            } catch (Throwable ignored) {
            }
            if (want < 0) {
                want = lastRotation; // 传感器还没数据，保持现状
            }
        }

        if (want >= 0 && want != (Integer) param.getResult()) {
            param.setResult(want);
        }
    }

    // =====================================================================
    //  按 AOSP 11 DisplayRotation.updateRotationUnchecked() 原样补回旋转施加逻辑
    // =====================================================================
    private static final int WINDOWS_FREEZING_SCREENS_ACTIVE = 1;

    /** @return 是否发生了旋转（对应原方法的返回值语义） */
    private static boolean doUpdateRotation(Object dr, boolean forceUpdate) {
        final ClassLoader cl = dr.getClass().getClassLoader();
        final Object dc = XposedHelpers.getObjectField(dr, "mDisplayContent");
        final Object wms = XposedHelpers.getObjectField(dr, "mService");

        if (!forceUpdate) {
            if (XposedHelpers.getIntField(dr, "mDeferredRotationPauseCount") > 0) {
                return false;
            }
            try {
                Object anim = XposedHelpers.callMethod(dc, "getRotationAnimation");
                if (anim != null && Boolean.TRUE.equals(
                        XposedHelpers.callMethod(anim, "isAnimating"))) {
                    return false;   // 上一次旋转动画还没结束
                }
            } catch (Throwable ignored) {
            }
            if (XposedHelpers.getBooleanField(wms, "mDisplayFrozen")) {
                return false;
            }
            try {
                Object listener = XposedHelpers.getObjectField(
                        dc, "mFixedRotationTransitionListener");
                if (listener != null && Boolean.TRUE.equals(XposedHelpers.callMethod(
                        listener, "isTopFixedOrientationRecentsAnimating"))) {
                    return false;
                }
            } catch (Throwable ignored) {
            }
        }

        if (!XposedHelpers.getBooleanField(wms, "mDisplayEnabled")) {
            return false;   // 屏幕没开，不用算
        }

        final int oldRotation = XposedHelpers.getIntField(dr, "mRotation");
        final int lastOrientation = XposedHelpers.getIntField(dr, "mLastOrientation");
        final int rotation = (Integer) XposedHelpers.callMethod(
                dr, "rotationForOrientation", lastOrientation, oldRotation);

        if (oldRotation == rotation) {
            return false;
        }

        log("旋转: " + rotName(oldRotation) + " -> " + rotName(rotation)
                + " (orientation=" + lastOrientation
                + ", forceUpdate=" + forceUpdate + ")");

        // 180° 翻转不需要等新配置，其余要等
        boolean needWait = true;
        try {
            int delta = (Integer) XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("com.android.server.wm.DisplayContent", cl),
                    "deltaRotation", rotation, oldRotation);
            needWait = (delta != 2);
        } catch (Throwable ignored) {
        }
        try {
            XposedHelpers.setBooleanField(dc, "mWaitingForConfig", needWait);
        } catch (Throwable t) {
            log("set mWaitingForConfig 失败: " + t);
        }

        XposedHelpers.setIntField(dr, "mRotation", rotation);

        // 冻结屏幕，避免旋转过程中窗口尺寸错乱（best-effort，失败不影响旋转本身）
        try {
            XposedHelpers.setIntField(wms, "mWindowsFreezingScreen",
                    WINDOWS_FREEZING_SCREENS_ACTIVE);
            Object h = XposedHelpers.getObjectField(wms, "mH");
            int what = XposedHelpers.getStaticIntField(
                    XposedHelpers.findClass(
                            "com.android.server.wm.WindowManagerService$H", cl),
                    "WINDOW_FREEZE_TIMEOUT");
            long delay = 2000L;
            try {
                delay = XposedHelpers.getStaticIntField(
                        XposedHelpers.findClass(
                                "com.android.server.wm.WindowManagerService", cl),
                        "WINDOW_FREEZE_TIMEOUT_DURATION");
            } catch (Throwable ignored) {
            }
            XposedHelpers.callMethod(h, "sendNewMessageDelayed", what, dc, delay);
        } catch (Throwable t) {
            log("冻结步骤失败（忽略）: " + t);
        }

        try {
            XposedHelpers.callMethod(dc, "setLayoutNeeded");
        } catch (Throwable ignored) {
        }

        // 旋转动画：能无缝就无缝，否则走普通旋转动画
        try {
            boolean seamless = Boolean.TRUE.equals(XposedHelpers.callMethod(
                    dr, "shouldRotateSeamlessly", oldRotation, rotation, forceUpdate));
            if (seamless) {
                XposedHelpers.callMethod(dr, "prepareSeamlessRotation");
            } else {
                XposedHelpers.callMethod(dr, "prepareNormalRotationAnimation");
            }
        } catch (Throwable t) {
            log("旋转动画步骤失败（忽略）: " + t);
        }

        // 给 SystemUI 一点时间重新布局
        try {
            XposedHelpers.callMethod(dr, "startRemoteRotation", oldRotation, rotation);
        } catch (Throwable ignored) {
        }
        return true;
    }

    /**
     * 用户显式锁定方向时，不允许 SCREEN_ORIENTATION_NOSENSOR(5) 把它压掉。
     *
     * DisplayRotation.rotationForOrientation() 里，锁定态下有一串「这些方向不走
     * mUserRotation」的排除：NOSENSOR(5) / LANDSCAPE(0) / PORTRAIT(1) /
     * REVERSE_LANDSCAPE(8) / REVERSE_PORTRAIT(9)。其中 0/1/8/9 在后面的
     * switch 里各有归宿（返回 mLandscapeRotation / mPortraitRotation），
     * **只有 NOSENSOR 会掉进 default 分支**：
     *     if (v4 >= 0) return v4;
     *     return Surface.ROTATION_0;      // v4 = -1 → 恒竖屏
     *
     * 本 ROM 的桌面 / 表盘 / 充电界面（LauncherKeyguardChargeViewWindow）请求的
     * 正是 NOSENSOR —— 于是手表一回到表盘，面板/广播设的手动旋转就失效。
     * 用户既然显式锁了方向，就以用户为准。
     */
    private static void keepUserRotationForNoSensor(XC_MethodHook.MethodHookParam param) {
        if ((Integer) param.args[0] != ORIENT_NOSENSOR) {
            return;
        }
        final Object dr = param.thisObject;
        if (XposedHelpers.getIntField(dr, "mUserRotationMode") != USER_ROTATION_LOCKED) {
            return;
        }
        final int user = XposedHelpers.getIntField(dr, "mUserRotation");
        if (user != (Integer) param.getResult()) {
            param.setResult(user);
            log("NOSENSOR(5) 顶层下保留用户锁定方向 -> " + rotName(user));
        }
    }

    /**
     * 自动旋转开启（USER_ROTATION_FREE）且方向由传感器决定时，如果传感器**没有**
     * 给出方向提议（手表平放桌上就是这种，getProposedRotation() == -1），
     * AOSP 的做法是"沿用上一次的旋转"：
     *
     *     int rotation = mOrientationListener != null ? getProposedRotation() : -1;
     *     if (rotation < 0) rotation = lastRotation;      // ← 这里
     *
     * 后果：横屏 App（如 App Settings 把 MT 管理器设成横屏）退出回到桌面后，
     * 桌面请求的是 UNSPECIFIED，走传感器分支 → 无提议 → 沿用 lastRotation(=横屏)，
     * **桌面就一直卡在横屏回不来**。手机上这算 AOSP 正常行为（平放保持当前方向），
     * 但手表绝大多数时间平放，必须回落。
     *
     * 这里只处理「由传感器决定」的那组方向（UNSPECIFIED/USER/SENSOR/FULL_SENSOR/
     * FULL_USER）；显式横竖屏（0/1/6/7/8/9/11/12）在上面的 switch 里各有归宿，不碰。
     * 用户手动锁定方向时（USER_ROTATION_LOCKED）也不碰 —— 那是用户的明确选择。
     */
    private static void fallbackToNaturalWhenSensorIdle(XC_MethodHook.MethodHookParam param) {
        switch ((Integer) param.args[0]) {
            case ORIENT_UNSPECIFIED:
            case ORIENT_USER:
            case ORIENT_SENSOR:
            case ORIENT_FULL_SENSOR:
            case ORIENT_FULL_USER:
                break;
            default:
                return;
        }
        final Object dr = param.thisObject;
        if (XposedHelpers.getIntField(dr, "mUserRotationMode") != USER_ROTATION_FREE) {
            return;
        }
        Object listener;
        try {
            listener = XposedHelpers.getObjectField(dr, "mOrientationListener");
        } catch (Throwable t) {
            return;
        }
        if (listener == null) {
            return;
        }
        final int proposed = (Integer) XposedHelpers.callMethod(listener, "getProposedRotation");
        if (proposed >= 0) {
            return;   // 传感器有意见，听传感器的
        }
        final int natural = XposedHelpers.getIntField(dr, "mPortraitRotation");
        if (natural != (Integer) param.getResult()) {
            param.setResult(natural);
            log("传感器无方向提议（平放）→ 回落自然方向 " + rotName(natural));
        }
    }

    private static String rotName(int r) {        switch (r) {
            case 0: return "0°";
            case 1: return "90°";
            case 2: return "180°";
            case 3: return "270°";
            default: return String.valueOf(r);
        }
    }

    // =====================================================================
    //  首次初始化：默认值 / 控制广播 / 存活标记 / 横屏特性
    // =====================================================================

    private static void onFirstInit(Object dr) {
        Context sysCtx = null;
        try {
            Class<?> at = XposedHelpers.findClass("android.app.ActivityThread", dr.getClass().getClassLoader());
            Object cur = XposedHelpers.callStaticMethod(at, "currentActivityThread");
            sysCtx = (Context) XposedHelpers.callMethod(cur, "getSystemContext");
        } catch (Throwable t) {
            log("getSystemContext failed: " + t);
        }
        if (sysCtx == null) {
            try {
                sysCtx = (Context) XposedHelpers.getObjectField(dr, "mContext");
            } catch (Throwable t) {
                log("get mContext failed: " + t);
            }
        }
        if (sysCtx == null) {
            log("FATAL: no Context, 后续初始化跳过");
            return;
        }

        final ContentResolver cr = sysCtx.getContentResolver();

        // 1) 开机默认打开自动旋转（本 ROM def_accelerometer_rotation=false）
        try {
            int def = Settings.System.getInt(cr, KEY_DEFAULT_AUTO, 1);
            if (def == 1) {
                int cur = sysGetIntForUser(cr, Settings.System.ACCELEROMETER_ROTATION, 1, USER_SYSTEM);
                if (cur != 1) {
                    sysPutIntForUser(cr, Settings.System.ACCELEROMETER_ROTATION, 1, USER_SYSTEM);
                    log("accelerometer_rotation: " + cur + " -> 1 (开机默认自动旋转)");
                }
            } else {
                log("开机默认自动旋转已关闭 (" + KEY_DEFAULT_AUTO + "=0)");
            }
        } catch (Throwable t) {
            log("set accelerometer_rotation failed: " + t);
        }

        // 2) 存活标记：写「当前时间戳」而不是 1。
        //    面板那边拿它和本次开机时刻比较，停用模块后不会残留假阳性
        //    （布尔标记一旦写进去就永远留着，模块停用了也还显示"已激活"）。
        try {
            Settings.System.putLong(cr, KEY_HOOK_ALIVE, System.currentTimeMillis());
        } catch (Throwable t) {
            log("write alive marker failed: " + t);
        }

        // 3) 注册控制广播
        try {
            IntentFilter f = new IntentFilter(ACTION_SET);
            sysCtx.registerReceiver(new CtrlReceiver(), f, null,
                    new Handler(Looper.getMainLooper()));
            log("control receiver registered: " + ACTION_SET);
        } catch (Throwable t) {
            log("registerReceiver failed: " + t);
        }

        // 4) 补回 android.hardware.screen.landscape 特性
        addLandscapeFeature();

        log("init done. mSupportAutoRotation=" + sSwitchOk);
    }

    /**
     * ROM 的 /system/etc/permissions/wearable_excluded_core_hardware.xml 把
     * android.hardware.screen.landscape 标记为 unavailable，很多 App 因此不认为设备
     * 支持横屏。这里直接往 PMS 的 mAvailableFeatures 里补一条。
     */
    private static void addLandscapeFeature() {
        final String FEATURE = "android.hardware.screen.landscape";
        try {
            if (sPms == null) {
                log("PMS 实例未捕获，跳过 landscape 特性补丁");
                return;
            }
            Object mapObj = XposedHelpers.getObjectField(sPms, "mAvailableFeatures");
            if (!(mapObj instanceof Map)) {
                log("mAvailableFeatures 类型异常: " + mapObj);
                return;
            }
            @SuppressWarnings("unchecked")
            Map<Object, Object> map = (Map<Object, Object>) mapObj;
            if (map.containsKey(FEATURE)) {
                log("feature " + FEATURE + " 已存在，无需补");
                return;
            }
            Class<?> fiCls = XposedHelpers.findClass(
                    "android.content.pm.FeatureInfo", RotateFixHook.class.getClassLoader());
            // 注意：FeatureInfo(String,int) 是 @hide，这里用公开的无参构造 + 公开字段 name/version
            Object fi = XposedHelpers.newInstance(fiCls);
            XposedHelpers.setObjectField(fi, "name", FEATURE);
            XposedHelpers.setIntField(fi, "version", 0);
            map.put(FEATURE, fi);
            log("已补回 feature: " + FEATURE);
        } catch (Throwable t) {
            log("addLandscapeFeature failed: " + t);
        }
    }

    /** 运行期控制：由模块 App 或 adb 广播触发，在 system_server 里直接写 Settings（无需 WRITE_SETTINGS） */
    private static class CtrlReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                final String cmd = intent.getStringExtra("cmd");
                if (cmd == null) {
                    return;
                }
                final int value = intent.getIntExtra("value", -1);
                final ContentResolver cr = context.getContentResolver();
                log("ctrl cmd=" + cmd + " value=" + value);

                if ("auto_on".equals(cmd)) {
                    sysPutIntForUser(cr, Settings.System.ACCELEROMETER_ROTATION, 1, USER_SYSTEM);

                } else if ("auto_off".equals(cmd)) {
                    sysPutIntForUser(cr, Settings.System.ACCELEROMETER_ROTATION, 0, USER_SYSTEM);

                } else if ("rot".equals(cmd)) {
                    int v = (value < 0 || value > 3) ? 0 : value;
                    sysPutIntForUser(cr, Settings.System.ACCELEROMETER_ROTATION, 0, USER_SYSTEM);
                    sysPutIntForUser(cr, Settings.System.USER_ROTATION, v, USER_SYSTEM);
                    log("手动旋转 -> " + v);

                } else if ("default_auto".equals(cmd)) {
                    int v = (value == 1) ? 1 : 0;
                    Settings.System.putInt(cr, KEY_DEFAULT_AUTO, v);
                    log("开机默认自动旋转 -> " + v);
                }
            } catch (Throwable t) {
                log("receiver err: " + t);
            }
        }
    }
}
