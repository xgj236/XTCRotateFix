# XgjRotateFix —— XTC ND08 (Z11) 旋转解锁 LSPosed 模块

针对 `XTC/ND08/monaco_go:11/RKQ1.220916.001`（DXY_2.7.5.26.8.7，实机 `Z11_SN`），
在运行时解除框架层的旋转封锁，**不修改任何 ROM 文件**。

产物：`XTCRotateFix.apk`（**v1.1 / versionCode 1000**）
作用域：**`android`（系统框架）** —— 旋转只需 hook system_server

> 已在实机 Z11_SN 上验证通过：手动四个方向 + 自动旋转链路打通，无崩溃、无 ANR。
>
> SystemUI 侧因旋转生效而暴露的崩溃（`RotationPolicy.getNaturalRotation()` 缺失）
> 已移交 **ReXTCsettings 模块的「修复 BV」**，本模块不再需要 systemui 作用域。

---

## 一、真正的根因（实测修正版）

前面静态分析找到的三处配置封锁是真的，但**都只是"开关"层面**。实测发现厂商在
**代码层面直接把旋转施加函数掏空**了，这才是致命的一刀：

### ① `DisplayRotation.updateRotationUnchecked(boolean)` 被改成空函数 ★ 关键

`services.jar` → `com/android/server/wm/DisplayRotation.smali`：

```smali
.method updateRotationUnchecked(Z)Z
    .locals 0
    .line 581
    const/4 p1, 0x0
    return p1
.end method
```

这是**整条旋转链路唯一的施加点**。调用链是：

```
WMS.updateRotation(ZZ)
  └─ WMS.updateRotationUnchecked(ZZ)
       └─ DisplayContent.updateRotationUnchecked()          // 完好，只是转发
            └─ DisplayRotation.updateRotationUnchecked(Z)   // ← 空函数，恒返回 false
```

所以无论 `config_supportAutoRotation`、`ACCELEROMETER_ROTATION`、`USER_ROTATION`
怎么改，`mRotation` 永远是 0，屏幕一动不动。
（`setRotation(int)` 也被简化成只有 `mRotation = i;`，但没有它致命。）

### ② framework-res 的三处配置封锁

| 位置 | 原值 | 影响 |
|---|---|---|
| `config_supportAutoRotation` (`0x011100da`) | false | `rotationForOrientation()` 对 UNSPECIFIED/USER/SENSOR 一律返回 ROTATION_0；`USER_ROTATION` 被完全忽略 |
| `config_allowAllRotations` (`0x0111000e`) | false | 不允许 180° |
| `config_supportWristRotation` (`0x011100e3`) | false | 关掉厂商判断里唯一的"手腕旋转"逃生口 |

### ③ SettingsProvider 默认值 + 特性屏蔽

- `def_accelerometer_rotation = false` → 开机 `accelerometer_rotation=0`（锁定态）
- `wearable_excluded_core_hardware.xml` 里 `<unavailable-feature screen.landscape>`

### ④ `RotationPolicy.getNaturalRotation()` 被删 → SystemUI 崩溃 ★ 连带坑（修复已迁至 ReXTCsettings）

`framework.jar` 里 `com.android.internal.view.RotationPolicy` **没有 `getNaturalRotation()`**
（AOSP 11 原生也没有，本机 SystemUI 是较新版本编译的，见下）。
而 `XTCSystemUI`（包名其实是 `com.android.systemui`）里：

```
java.lang.NoSuchMethodError: No static method getNaturalRotation()I
    in class Lcom/android/internal/view/RotationPolicy;
  at RotationButtonController.shouldOverrideUserLockPrefs(RotationButtonController.java:370)
  at RotationButtonController$1.lambda$onRotationChanged$0(RotationButtonController.java:97)
```

它只在**旋转锁定 + 屏幕旋转真正发生变化**时才会走到，ROM 原来旋转是死的，所以这颗雷
一直没炸。一旦我们让旋转生效，SystemUI 立刻崩溃循环 —— 这也是"关掉自动旋转后左下角
出现旋转标志但点击无效"时期的隐藏故障。

**这块修复已移到 `ReXTCsettings` 模块**（那份模块本来就持有 `com.android.systemui`
作用域，SystemUI 的所有修补都在那儿，放一起才合理）。本模块只负责 system_server 那一半。

### ⑤ `ActivityTaskManagerService.setRequestedOrientation` 删掉了 `ensureVisibilityAndConfig` ★

本 ROM 的 ATMS 里：

```smali
.line 1976
invoke-virtual {p1, p2}, Lcom/android/server/wm/ActivityRecord;->setRequestedOrientation(I)V
.line 1978
invoke-static {v1, v2}, Landroid/os/Binder;->restoreCallingIdentity(J)V     # ← 直接收尾
```

AOSP 11 原版这两句之间还有一句：

```java
mRootWindowContainer.ensureVisibilityAndConfig(r, r.getDisplayId(),
        false /* markFrozenIfConfigChanged */, true /* deferResume */);
```

被整行删掉了。后果：App 调 `Activity.setRequestedOrientation()` 之后，方向**只写进了
`ActivityRecord.mOrientation`**（`dumpsys activity activities` 里能看到 `mOrientation=0` 横屏），
但没人通知显示层 —— `DisplayContent.updateOrientation()` 从不被调用，
`DisplayRotation.mCurrentAppOrientation` 一直停在 `SCREEN_ORIENTATION_USER`，屏幕不转。
**这就是「App Settings 的屏幕方向覆盖不生效」的直接原因。**

### ⑥ `DisplayContent.mIgnoreRotationForApps`：AOSP 的「正方屏」启发式把手表误伤 ★ 真正的最后一道闸

`DisplayContent.configureDisplayPolicy()`：

```smali
.line 1798
invoke-direct {p0, v2, v0, v1}, DisplayContent;->isNonDecorDisplayCloseToSquare(III)Z
move-result v0
iput-boolean v0, p0, DisplayContent;->mIgnoreRotationForApps:Z
```

这是 AOSP 为折叠屏加的启发式：**去掉状态栏/导航栏后的非装饰区**长宽比落在
`[0.909, 1.1]` 就认定"接近正方形"，然后：

```smali
.method getOrientation()I
    iget-boolean v1, p0, ->mIgnoreRotationForApps:Z
    if-eqz v1, :cond_0
    return v2                 # v2 = 2 = SCREEN_ORIENTATION_USER
```

本机屏幕 416x468，扣掉系统栏后非装饰区几乎正方 → 该标志实测为 **true** →
`getOrientation()` 无条件返回 `SCREEN_ORIENTATION_USER`，**任何 App 请求的方向都被丢掉**。

注：这一条不是厂商阉割，是 AOSP 原生代码；但手表本来就该允许 App 自定方向，
所以本模块把它清掉。这也解释了厂商为什么还要额外掏空 `updateRotationUnchecked` ——
光靠这个启发式只能挡住 App 请求，挡不住系统级的用户旋转。

### ⑦ 清掉 ⑥ 之后暴露的连带问题：NOSENSOR 压下用户锁定方向 ★

⑥ 修好后 `getOrientation()` 开始返回顶层窗口**真实**请求的方向。本 ROM 的桌面 /
表盘 / 充电界面（`LauncherKeyguardChargeViewWindow`）请求的是
**`SCREEN_ORIENTATION_NOSENSOR`(5)**。

而 `rotationForOrientation()` 锁定态下有一串「不走 mUserRotation」的排除：

```
NOSENSOR(5) / LANDSCAPE(0) / PORTRAIT(1) / REVERSE_LANDSCAPE(8) / REVERSE_PORTRAIT(9)
```

其中 0/1/8/9 在后面 `:goto_a` 的 switch 里各有归宿（返回 mLandscapeRotation /
mPortraitRotation），**只有 NOSENSOR 会掉进 default 分支**：

```smali
if-ltz v4, :cond_20
return v4                  # v4 = -1（本分支没赋值）
:cond_20
const/4 v0, 0x0
return v0                  # 恒 ROTATION_0
```

于是**手表一回到表盘，面板/广播设的手动旋转就失效**。
（BUILD 5 之前看不出来，因为 `getOrientation()` 恒返回 USER(2)，永远走不到 NOSENSOR。）

修法：`rotationForOrientation` 的 after 钩子里，若 `orientation==NOSENSOR` 且
`mUserRotationMode==USER_ROTATION_LOCKED`，直接把结果改回 `mUserRotation` ——
用户既然显式锁了方向，就以用户为准。

### ⑧ 方向聚合会被"下层仍可见的任务"带跑 → 桌面回不去竖屏 ★

App 在前台时方向正常；**按 HOME 回桌面后屏幕留在横屏**。根因在方向聚合
`WindowContainer.getOrientation(I)`：它自上而下逐层问，规则是

```
子节点返回 UNSET(-2)                        -> 跳过，问下一个
子节点 !fillsParent()                       -> 返回它的值
子节点 fillsParent() 且返回 UNDEFINED(-1)   -> **继续问下一个**
其余                                        -> 返回它的值
```

本 ROM 的桌面 `HomeActivity` 是 `mOccludesParent=false`（半透明，`fillsParent()==false`），
于是：

```
HomeActivity 请求 UNSPECIFIED(-1)
  └─ 它的 Task#3 正确地返回 -1
       └─ 但外层 home root Task#1 看到「子 -1 + Task.fillsParent()==true」→ 继续往下问
            └─ 问到下层那个仍然 visible=true 的 App 任务（App Settings 设成横屏的 MT）
                 └─ 拿到 USER_LANDSCAPE(11) → 桌面被顶成横屏
```

AOSP 在手机上不踩这个坑，是因为按 HOME 后下层任务会被置为不可见；这 ROM 的半透明桌面
把它留成了可见。

修法不去动那套聚合（对其它容器有副作用），只在结果层兜底：`DisplayContent.getOrientation()`
的 after 钩子里，若 **`RootWindowContainer.getTopResumedActivity()` 是带
`android.intent.category.HOME` 的桌面**，就用桌面自己请求的方向。

- 判据必须用 `getTopResumedActivity()`（真正 resumed 的那个），**不能用
  `TaskDisplayArea.getFocusedActivity()`** —— 本 ROM 的 `LauncherKeyguardWindow`
  常驻持有窗口焦点，App 在前台时它照样返回桌面，会把 App 的方向一起压掉。
- 桌面请求 `UNSPECIFIED(-1)`，往后的两条路都不会把桌面顶成横屏：
  自动旋转模式 → 传感器分支 → 平放时由 ⑦ 的兜底回落竖屏；
  手动锁定模式 → 返回 `mUserRotation`。

---

## 二、模块做了什么

| Hook 目标 | 进程 | 动作 |
|---|---|---|
| `DisplayRotation` 构造器 | android | `mSupportAutoRotation=true`、`mSupportWristRotation=true`、`mAllowAllRotations=1`，并 `updateOrientationListener()` |
| `DisplayRotation#needSensorRunning()` | android | 返回 `!isFixedToUserRotation()`（原逻辑因 `ro.config.low_ram=true` 把 `mShowRotationSuggestions` 强置 0，锁定态恒 false） |
| `DisplayRotation#rotationForOrientation(II)` | android | 调用前幂等重确认总闸；总闸改不动时走兜底（锁定态用 `USER_ROTATION`，自由态用传感器 `getProposedRotation()`） |
| **`DisplayRotation#updateRotationUnchecked(Z)`** | android | **按 AOSP 11 原实现完整补回**（见下） |
| `PackageManagerService` 构造器 + `mAvailableFeatures` | android | 补回 `android.hardware.screen.landscape` |
| **`ActivityRecord#setRequestedOrientation(I)`** | android | after 钩子里补调 `mRootWindowContainer.ensureVisibilityAndConfig(r, r.getDisplayId(), false, true)`，等价于 AOSP 在 ATMS 里的那一句（全 ROM 只有 ATMS 一处调它，且此时仍持有 `mGlobalLock`） |
| **`DisplayContent#configureDisplayPolicy()` / `#getOrientation()`** | android | 清掉 `mIgnoreRotationForApps`（配置重算后清一次 + 每次取方向前清一次） |
| **`DisplayRotation#rotationForOrientation(II)`**（after） | android | 顶层请求 `NOSENSOR(5)` 且用户已锁定方向时，把结果改回 `mUserRotation`，避免表盘/充电界面把手动旋转压掉 |
| **`DisplayContent#getOrientation()`**（after） | android | 若 `getTopResumedActivity()` 是桌面（category HOME），用桌面自身方向覆盖聚合结果，修「回桌面停在横屏」 |

另外在 system_server 里注册广播接收器、开机把 `accelerometer_rotation` 置 1（可关）。

### `updateRotationUnchecked` 补回的实现

严格照抄 AOSP 11 `android-11.0.0_r48` 的 `DisplayRotation.updateRotationUnchecked()`：

```
① forceUpdate=false 时的四个提前返回：
   mDeferredRotationPauseCount > 0 / 旋转动画还在跑 / mService.mDisplayFrozen / 固定方向动画中
② !mService.mDisplayEnabled → return false
③ rotation = rotationForOrientation(mLastOrientation, mRotation)
④ rotation == oldRotation → return false
⑤ DisplayContent.deltaRotation(rotation, old) != 2（非 180°）→ mWaitingForConfig = true
⑥ mRotation = rotation
⑦ mWindowsFreezingScreen = ACTIVE + post WINDOW_FREEZE_TIMEOUT(2s) 超时消息
⑧ mDisplayContent.setLayoutNeeded()
⑨ shouldRotateSeamlessly() ? prepareSeamlessRotation() : prepareNormalRotationAnimation()
⑩ startRemoteRotation(old, new)
⑪ return true
```

其中 ⑦⑨⑩ 每一步都单独 try/catch，失败只记日志不影响旋转本身
（`startFreezingDisplay` / `ScreenRotationAnimation` 在本 ROM 里是完好的，已确认）。
后续的 `sendNewConfiguration()` / `performSurfacePlacement()` 由完好的
`WindowManagerService.updateRotationUnchecked(ZZ)` 负责，不需要我们管。

---

## 三、安装与作用域

```bash
adb install -r XTCRotateFix.apk
```

LSPosed 管理器里：
1. 启用「旋转修复」
2. **作用域勾选**：`系统框架`(android)。
   （**不要**勾 `com.android.systemui` —— 那个进程的旋转崩溃修复已经在 ReXTCsettings 里）
3. 重启

> 模块的 `xposedscope` 元数据已声明 `android`，但 LSPosed 不一定自动同步；
> 本次实测是直接改 `/data/adb/lspd/config/modules_config.db` 的 `scope` 表落地的。
> 那份表里本模块的 scope 现在是 `{system, com.xgj.rotatefix}`，**没有** systemui。

验证：

```bash
adb logcat -s XgjRotateFix
# 期望：
#   === loaded in system_server, android 30 (...) ===
#   hooked updateRotationUnchecked() [BUILD 2]
#   mSupportAutoRotation: false -> true
#
# 面板顶部状态：alive marker 是时间戳，见「四、使用」
#   （不再有 "loaded in SystemUI" —— 本模块不注入状态栏）
```

---

## 四、使用

### 面板（桌面「旋转修复」）

界面只有三块，顶部是模块状态：

```
        模块已激活            ← 顶部：绿=已激活 / 红=未激活（附一行提示）
      [ 自动旋转        ]      ← Switch
      [ 开机默认自动旋转 ]      ← Switch
       手动旋转（会关闭自动旋转）
      [ 竖屏 0°  ] [ 横屏 90° ]
      [ 反向竖屏 180° ] [ 反向横屏 270° ]
```

「模块已激活」的判定：hook 每次开机在 system_server 里往
`Settings.System.xgj_rotate_hook_alive` 写**当前时间戳**；App 拿它和
「本次开机时刻」（`currentTimeMillis() - elapsedRealtime()`）比较 ——
时间戳早于开机时刻就说明这次开机 hook 没起来（模块停用/没勾作用域）。
**不用布尔标记**，否则模块停用后那个 1 会一直留着，面板永远显示"已激活"。

界面里不再显示 `accelerometer_rotation` / `user_rotation` / WRITE_SETTINGS 等调试
数值，也不显示 adb 命令提示 —— 但**广播命令本身照旧可用**（见下）。

不需要 `WRITE_SETTINGS`：面板发广播 `com.xgj.rotatefix.SET`，由 system_server 里的
hook 直接写 Settings；若 App 恰好有该权限会同时直写一次作备份。

### adb 等效

```bash
adb shell am broadcast -a com.xgj.rotatefix.SET --es cmd auto_on
adb shell am broadcast -a com.xgj.rotatefix.SET --es cmd auto_off
adb shell am broadcast -a com.xgj.rotatefix.SET --es cmd rot --ei value 1   # 0/1/2/3
adb shell am broadcast -a com.xgj.rotatefix.SET --es cmd default_auto --ei value 0
```

### 观察状态

```bash
adb shell dumpsys window displays | grep -E "mRotation=|mUserRotation|mSupportAutoRotation|mProposedRotation|mFlat"
adb logcat -s XgjRotateFix | grep 旋转
```

---

## 五、实测结果（Z11_SN 实机）

```
ctrl cmd=rot value=0 → mRotation=0  (PORTRAIT)
ctrl cmd=rot value=1 → 旋转: 0° -> 90°   mRotation=1
ctrl cmd=rot value=2 → 旋转: 90° -> 180° mRotation=2
ctrl cmd=rot value=0 → 旋转: 180° -> 0°  mRotation=0
auto_on             → mUserRotationMode=USER_ROTATION_FREE, mEnabled=true
```

### App Settings Reborn（`ru.bluecat.android.xposed.mods.appsettings`）屏幕方向覆盖 A/B 实测

配置在 `/data/misc/<uuid>/prefs/ru.bluecat.../ModSettings.xml`，
`orientation` 是 `Constants.orientationCodes` 的下标（`{MIN_VALUE,-1,1,0,4,7,6,9,8,10,12,11,13}`），
目标 App `bin.mt.plus`：

| 配置值 | 含义 | `DisplayRotation.mCurrentAppOrientation` | `mRotation` |
|---|---|---|---|
| `orientation=3` | `codes[3]=0` = LANDSCAPE | `SCREEN_ORIENTATION_LANDSCAPE` | **1（横屏）** |
| `orientation=2` | `codes[2]=1` = PORTRAIT | `SCREEN_ORIENTATION_PORTRAIT` | **0（竖屏）** |

修复前无论选什么都恒为 `SCREEN_ORIENTATION_USER` + `mRotation=0`。

- SystemUI 崩溃次数：**0**（修复前为崩溃循环）
- ANR：**0**
- `system_server` / `com.android.systemui` 进程稳定

---

## 六、已知限制 / 注意

1. **自动旋转需要把表从水平姿态拿起来**。手表平放桌上时 `mProposedRotation=-1`、
   `mFlat=true`，本来就不该转 —— 这是 AOSP 的正确行为，不是故障。
   想验证自动旋转：打开「自动旋转」开关后把表盘竖起来 / 转 90°。
2. 本 ROM 的 `screencap` 被屏蔽（root 下也返回 rc=1），无法截图取证，只能肉眼看屏。
3. `needSensorRunning()` 放行后亮屏时加速度计常开，手表上会有额外耗电。
   只想要手动旋转的话把「开机默认自动旋转」关掉即可。
4. 横屏后表盘/Launcher 布局可能错乱 —— ROM 自身没为横屏适配，与模块无关。
5. 自动旋转与手动锁定互斥（Android 原生语义）：`accelerometer_rotation=1` 走传感器，
   `=0` 时 `user_rotation` 才生效。
6. 若 ROM 升级导致 `DisplayRotation` 的字段或方法改名，
   模块里对应 hook 会打日志报错并自动降级（`doUpdateRotation 失败` / `hook ... failed`），
   按日志改名字段即可。

---

## 七、源码与构建

```
lspmod/
├── AndroidManifest.xml            # xposedmodule / xposedscope 元数据
├── assets/xposed_init             # com.xgj.rotatefix.RotateFixHook
├── res/values/arrays.xml          # xposed_scope = android
├── res/layout/activity_main.xml
├── res/mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher.png   # 应用图标（5 档密度）
├── make_icon.ps1                  # 从源图生成上面 5 档图标（System.Drawing）
├── java/com/xgj/rotatefix/
│   ├── RotateFixHook.java         # 核心 hook（只注入 system_server）
│   └── MainActivity.java          # 控制面板
├── api-82.jar                     # Xposed API，仅编译期
└── build.sh                       # aapt2 → javac → d8 → aapt add → zipalign → apksigner
```

```bash
bash build.sh          # 产物 ./XTCRotateFix.apk
```

依赖：`C:\Android\Sdk\build-tools\35.0.0`、`platforms\android-34\android.jar`、
签名 `C:\xgj_236\AllToolBox\ca\certs\codesign.pfx`（密码见同目录 `password.txt`）。
LSPosed 模块不要求平台签名，换自己的证书也行。

### 改 ROM 版本时对照检查的符号表

| 类 | 成员 |
|---|---|
| `com.android.server.wm.DisplayRotation` | 字段 `mSupportAutoRotation:Z`(final)、`mSupportWristRotation:Z`(final)、`mAllowAllRotations:I`、`mUserRotationMode:I`、`mUserRotation:I`、`mRotation:I`、`mLastOrientation:I`、`mDeferredRotationPauseCount:I`、`mOrientationListener`、`mDisplayContent`、`mService` |
| 同上 | 方法 `needSensorRunning()Z`、`rotationForOrientation(II)I`、`updateRotationUnchecked(Z)Z`、`updateOrientationListener()V`、`isFixedToUserRotation()Z`、`shouldRotateSeamlessly(IIZ)Z`、`prepareSeamlessRotation()V`、`prepareNormalRotationAnimation()V`、`startRemoteRotation(II)V` |
| `com.android.server.wm.DisplayContent` | `mWaitingForConfig:Z`、`mFixedRotationTransitionListener`、`getRotationAnimation()`、`setLayoutNeeded()`、静态 `deltaRotation(II)I` |
| `com.android.server.wm.WindowManagerService` | `mDisplayFrozen:Z`、`mDisplayEnabled:Z`、`mWindowsFreezingScreen:I`、`mH`、静态 `WINDOW_FREEZE_TIMEOUT_DURATION` |
| `WindowManagerService$H` | 静态 `WINDOW_FREEZE_TIMEOUT` |
| `com.android.server.pm.PackageManagerService` | `mAvailableFeatures` |
| `com.android.server.wm.ActivityRecord` | `mAtmService`、`getDisplayId()`、`setRequestedOrientation(I)V` |
| `com.android.server.wm.ActivityTaskManagerService` | `mRootWindowContainer` |
| `com.android.server.wm.RootWindowContainer` | `ensureVisibilityAndConfig(ActivityRecord,int,boolean,boolean)Z` |
| `com.android.server.wm.DisplayContent` | `mIgnoreRotationForApps:Z`、`configureDisplayPolicy()V`、`getOrientation()I`、`updateOrientation(Configuration,IBinder,boolean)` |
| ~~`com.android.systemui.statusbar.phone.RotationButtonController`~~ | 已迁至 ReXTCsettings「修复 BV」 |
