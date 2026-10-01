package com.xgj.rotatefix;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.ContentObserver;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 控制面板。
 *
 * 本 ROM 的 XTCSetting / XTCSystemUI 里没有任何旋转开关，所以这里自带一个。
 * 真正改 Settings 的动作有两条件路径（互为备份）：
 *   1) 本 App 有 WRITE_SETTINGS 时直接写；
 *   2) 发广播给 system_server 里的 hook 去写（不需要任何权限）。
 *
 * 顶部只显示模块是否已激活。判定方式：hook 每次开机在 system_server 里写入
 * 当前时间戳，App 拿它和「本次开机时刻」比较 —— 时间戳早于开机时刻说明本次
 * 开机 hook 没起来（模块被停用/没勾作用域），不会像布尔标记那样残留假阳性。
 */
public class MainActivity extends Activity {

    // 与 RotateFixHook 中的常量保持一致（此处写字面量，避免在 App 进程加载到 Xposed 类）
    private static final String ACTION_SET = "com.xgj.rotatefix.SET";
    private static final String KEY_DEFAULT_AUTO = "xgj_rotate_default_auto";
    private static final String KEY_HOOK_ALIVE = "xgj_rotate_hook_alive";

    private static final int COLOR_ON = 0xFF2E7D32;   // 绿
    private static final int COLOR_OFF = 0xFFC62828;  // 红

    private TextView tvHook;
    private Switch swAuto;
    private Switch swDefault;
    private boolean mBinding = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvHook = (TextView) findViewById(R.id.tvHook);
        swAuto = (Switch) findViewById(R.id.swAuto);
        swDefault = (Switch) findViewById(R.id.swDefault);

        swAuto.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                if (mBinding) {
                    return;
                }
                send(checked ? "auto_on" : "auto_off", 0);
            }
        });

        swDefault.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                if (mBinding) {
                    return;
                }
                send("default_auto", checked ? 1 : 0);
            }
        });

        int[] ids = {R.id.btn0, R.id.btn1, R.id.btn2, R.id.btn3};
        final String[] names = {"竖屏 0°", "横屏 90°", "反向竖屏 180°", "反向横屏 270°"};
        for (int i = 0; i < ids.length; i++) {
            final int rot = i;
            Button btn = (Button) findViewById(ids[i]);
            btn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    send("rot", rot);
                    toast("已切到" + names[rot] + "（已关闭自动旋转）");
                }
            });
        }

        ContentResolver cr = getContentResolver();
        ContentObserver obs = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange) {
                refresh();
            }
        };
        cr.registerContentObserver(
                Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION), false, obs);
        cr.registerContentObserver(
                Settings.System.getUriFor(KEY_DEFAULT_AUTO), false, obs);
        cr.registerContentObserver(
                Settings.System.getUriFor(KEY_HOOK_ALIVE), false, obs);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        final ContentResolver cr = getContentResolver();

        boolean active;
        try {
            final long bootMs = System.currentTimeMillis() - SystemClock.elapsedRealtime();
            final long aliveMs = Settings.System.getLong(cr, KEY_HOOK_ALIVE, 0L);
            active = aliveMs > bootMs;
        } catch (Throwable t) {
            active = false;
        }
        if (active) {
            tvHook.setText("模块已激活");
            tvHook.setTextColor(COLOR_ON);
        } else {
            tvHook.setText("模块未激活\n请在 LSPosed 里启用并重启");
            tvHook.setTextColor(COLOR_OFF);
        }

        final boolean auto =
                Settings.System.getInt(cr, Settings.System.ACCELEROMETER_ROTATION, 1) == 1;
        final boolean defAuto = Settings.System.getInt(cr, KEY_DEFAULT_AUTO, 1) == 1;

        mBinding = true;
        swAuto.setChecked(auto);
        swDefault.setChecked(defAuto);
        mBinding = false;
    }

    /** 双路径下发：能直写就直写，同时发广播给 system_server 兜底 */
    private void send(String cmd, int value) {
        ContentResolver cr = getContentResolver();
        if (Settings.System.canWrite(this)) {
            try {
                if ("auto_on".equals(cmd)) {
                    Settings.System.putInt(cr, Settings.System.ACCELEROMETER_ROTATION, 1);
                } else if ("auto_off".equals(cmd)) {
                    Settings.System.putInt(cr, Settings.System.ACCELEROMETER_ROTATION, 0);
                } else if ("rot".equals(cmd)) {
                    Settings.System.putInt(cr, Settings.System.ACCELEROMETER_ROTATION, 0);
                    Settings.System.putInt(cr, Settings.System.USER_ROTATION, value);
                } else if ("default_auto".equals(cmd)) {
                    Settings.System.putInt(cr, KEY_DEFAULT_AUTO, value);
                }
            } catch (Throwable t) {
                // 落到广播路径
            }
        }
        try {
            Intent i = new Intent(ACTION_SET);
            i.putExtra("cmd", cmd);
            i.putExtra("value", value);
            sendBroadcast(i);
        } catch (Throwable t) {
            toast("发送失败: " + t);
        }
    }

    private void toast(final String s) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, s, Toast.LENGTH_SHORT).show();
            }
        });
    }
}
