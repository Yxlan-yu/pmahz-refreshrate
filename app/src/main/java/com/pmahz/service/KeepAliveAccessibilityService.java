package com.pmahz.service;
import android.accessibilityservice.AccessibilityService;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import androidx.core.app.NotificationCompat;
import com.pmahz.R;
import com.pmahz.model.DisplayMode;
import com.pmahz.util.AutoOverclockManager;
import com.pmahz.util.RootUtils;
import com.pmahz.util.ShizukuUtils;
import java.util.List;
public class KeepAliveAccessibilityService extends AccessibilityService {
    private static final String TAG = "KeepAliveA11y";
    private static final String CUSTOM_CHANNEL_ID = "custom_app_channel";
    private static final int CUSTOM_NOTIF_ID = 1002;
    private String currentFgPackage = "";
    private volatile String pendingFgPackage = "";
    private volatile String lastAppliedConfig = "";  // "effectivePkg@res@hz"
    private volatile String lastRealPkg = "";     
    private android.os.Handler fgHandler;
    private Runnable pendingApplyRunnable;
    private SharedPreferences servicePrefs;
    private SharedPreferences.OnSharedPreferenceChangeListener prefListener;
    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        fgHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        Log.d(TAG, "无障碍服务已连接");
        createCustomChannel();
        servicePrefs = getSharedPreferences("s", MODE_PRIVATE);
        prefListener = (sp, key) -> {
            if (!"custom_app_refresh".equals(key)) return;
            if (sp.getBoolean("custom_app_refresh", false)) {
                if (lastRealPkg != null && !lastRealPkg.isEmpty()) updatePersistentNotification(lastRealPkg);
                else postWaitingNotification();
            } else {
                AutoOverclockManager.clearCustomOverride();
                lastAppliedConfig = "";
                cancelCustomNotification();
            }
        };
        servicePrefs.registerOnSharedPreferenceChangeListener(prefListener);
        if (servicePrefs.getBoolean("custom_app_refresh", false)) postWaitingNotification();
        checkAndRestartService();
    }
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                type != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return;
        String pkg = getTopForegroundPackage(event);
        if (pkg == null || pkg.isEmpty()) return;
        if (pkg.equals(getPackageName()) || pkg.equals("android")) return;
        scheduleForegroundApply(pkg);
    }
    private String getTopForegroundPackage(AccessibilityEvent event) {
        try {
            java.util.List<android.view.accessibility.AccessibilityWindowInfo> windows = getWindows();
            if (windows != null) {
                for (android.view.accessibility.AccessibilityWindowInfo w : windows) {
                    if (w != null && w.isFocused() &&
                            w.getType() == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) {
                        android.view.accessibility.AccessibilityNodeInfo root = w.getRoot();
                        if (root != null) {
                            CharSequence p = root.getPackageName();
                            root.recycle();
                            if (p != null && p.length() > 0) return p.toString();
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        CharSequence p = event.getPackageName();
        return p != null ? p.toString() : null;
    }
    private void scheduleForegroundApply(String pkg) {
        pendingFgPackage = pkg;
        final String finalPkg = pkg;
        new Thread(() -> {
            if (!finalPkg.equals(pendingFgPackage)) return;
            applyForPackage(finalPkg);
            updatePersistentNotification(finalPkg);
        }).start();
    }
    @Override
    public void onInterrupt() {
        Log.d(TAG, "无障碍服务中断");
        checkAndRestartService();
    }
    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "无障碍服务销毁");
        try { if (servicePrefs != null && prefListener != null) servicePrefs.unregisterOnSharedPreferenceChangeListener(prefListener); } catch (Exception ignored) {}
        AutoOverclockManager.clearCustomOverride();
        lastAppliedConfig = "";
        checkAndRestartService();
    }
    private synchronized void applyForPackage(String basePkg) {
        if (basePkg == null || basePkg.isEmpty()) return;
        if (basePkg.equals("android") || basePkg.equals(getPackageName())) return;
        SharedPreferences prefs = getSharedPreferences("s", MODE_PRIVATE);
        if (!prefs.getBoolean("custom_app_refresh", false)) { AutoOverclockManager.clearCustomOverride(); return; }
        String authMode = prefs.getString("auth_mode", "");
        if (authMode == null || authMode.isEmpty()) return;
        String effectivePkg = resolveEffectivePkg(prefs, basePkg);
        boolean enabled = prefs.getBoolean("app_refresh_enabled_" + effectivePkg, false);
        if (!enabled) {
            AutoOverclockManager.clearCustomOverride();
            lastAppliedConfig = "";
            return;
        }
        String res = prefs.getString("app_refresh_res_" + effectivePkg, "");
        int hz = prefs.getInt("app_refresh_hz_" + effectivePkg, -1);
        if (res == null || res.isEmpty() || hz <= 0) return;
        String configKey = effectivePkg + "@" + res + "@" + hz;
        if (configKey.equals(lastAppliedConfig)) return;
        currentFgPackage = basePkg;
        lastAppliedConfig = configKey;
        Log.d(TAG, "自定义刷新率切换: " + effectivePkg + " → " + res + " @ " + hz + "Hz");
        AutoOverclockManager.setCustomOverride(res, hz);
        applyDisplayTarget(authMode, res, hz);
    }
    private void updatePersistentNotification(String basePkg) {
        try {
            SharedPreferences prefs = getSharedPreferences("s", MODE_PRIVATE);
            if (!prefs.getBoolean("custom_app_refresh", false)) { cancelCustomNotification(); return; }
            if (basePkg == null || basePkg.isEmpty()) return;
            if (isIgnoredForNotification(prefs, basePkg)) return;
            lastRealPkg = basePkg;
            String effectivePkg = resolveEffectivePkg(prefs, basePkg);
            boolean enabled = prefs.getBoolean("app_refresh_enabled_" + effectivePkg, false);
            String setRes = prefs.getString("app_refresh_res_" + effectivePkg, "");
            int setHz = prefs.getInt("app_refresh_hz_" + effectivePkg, -1);
            int[] cur = getCurrentWHRate();
            String curRes = cur[0] > 0 ? (cur[0] + "×" + cur[1]) : "?";
            int curHz = cur[2];
            postCustomNotification(basePkg, enabled, setRes, setHz, curRes, curHz);
        } catch (Exception e) {
            Log.e(TAG, "updatePersistentNotification: " + e.getMessage());
        }
    }
    private boolean isIgnoredForNotification(SharedPreferences prefs, String pkg) {
        if (pkg.equals("android") || pkg.equals(getPackageName())) return true;
        if (pkg.equals("com.android.systemui")) return true;
        if (prefs.getBoolean("app_refresh_enabled_" + pkg, false)) return false;
        try {
            if (pkg.equals(getLauncherPackage())) return true;
            if (getPackageManager().getLaunchIntentForPackage(pkg) == null) return true;
        } catch (Exception ignored) {}
        return false;
    }
    private String getLauncherPackage() {
        try {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            android.content.pm.ResolveInfo ri = getPackageManager()
                    .resolveActivity(home, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
            if (ri != null && ri.activityInfo != null) return ri.activityInfo.packageName;
        } catch (Exception ignored) {}
        return "";
    }
    private int[] getCurrentWHRate() {
        try {
            android.hardware.display.DisplayManager dm =
                    (android.hardware.display.DisplayManager) getSystemService(DISPLAY_SERVICE);
            android.view.Display d = dm.getDisplay(android.view.Display.DEFAULT_DISPLAY);
            android.view.Display.Mode m = d.getMode();
            return new int[]{ m.getPhysicalWidth(), m.getPhysicalHeight(), Math.round(d.getRefreshRate()) };
        } catch (Exception e) { return new int[]{0, 0, 0}; }
    }
    private void createCustomChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                Context lc = getLocalizedCtx();
                NotificationChannel ch = new NotificationChannel(
                        CUSTOM_CHANNEL_ID,
                        lc.getString(R.string.custom_notif_channel_name),
                        NotificationManager.IMPORTANCE_LOW);
                NotificationManager nm = getSystemService(NotificationManager.class);
                if (nm != null) nm.createNotificationChannel(ch);
            } catch (Exception ignored) {}
        }
    }
    private Context getLocalizedCtx() {
        try {
            SharedPreferences prefs = getSharedPreferences("s", MODE_PRIVATE);
            String lang = prefs.getString("language", "zh");
            java.util.Locale locale = "zh-rTW".equals(lang) ? new java.util.Locale("zh", "TW") : new java.util.Locale(lang);
            android.content.res.Configuration config = new android.content.res.Configuration(getResources().getConfiguration());
            config.setLocale(locale);
            return createConfigurationContext(config);
        } catch (Exception e) { return this; }
    }
    private void postCustomNotification(String pkg, boolean enabled, String setRes, int setHz,
                                        String curRes, int curHz) {
        try {
            Context lc = getLocalizedCtx();
            String appLabel = pkg;
            try {
                android.content.pm.PackageManager pm = getPackageManager();
                appLabel = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
            } catch (Exception ignored) {}
            Intent openIntent = new Intent(this, com.pmahz.MainActivity.class);
            openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            int of = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) of |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent openPi = PendingIntent.getActivity(this, 3, openIntent, of);
            Intent stopIntent = new Intent(this, CustomRefreshActionReceiver.class);
            stopIntent.setAction("STOP_CUSTOM_REFRESH");
            int sf = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) sf |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent stopPi = PendingIntent.getBroadcast(this, 4, stopIntent, sf);
            String statusLine = enabled
                    ? lc.getString(R.string.custom_notif_status_on)
                    : lc.getString(R.string.custom_notif_status_off);
            String curLine = lc.getString(R.string.custom_notif_current_format, curRes, curHz);
            String title = appLabel + "  " + statusLine;
            StringBuilder big = new StringBuilder();
            if (enabled && setRes != null && !setRes.isEmpty() && setHz > 0) {
                big.append(lc.getString(R.string.custom_notif_set_format, setRes, setHz)).append("\n");
            }
            big.append(curLine);
            Notification n = new NotificationCompat.Builder(this, CUSTOM_CHANNEL_ID)
                    .setContentTitle(title)
                    .setContentText(curLine)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(big.toString()).setBigContentTitle(title))
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setContentIntent(openPi)
                    .addAction(android.R.drawable.ic_media_pause, lc.getString(R.string.custom_notif_stop), stopPi)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .build();
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(CUSTOM_NOTIF_ID, n);
        } catch (Exception e) {
            Log.e(TAG, "postCustomNotification: " + e.getMessage());
        }
    }
    private void cancelCustomNotification() {
        try {
            lastAppliedConfig = "";
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(CUSTOM_NOTIF_ID);
        } catch (Exception ignored) {}
    }
    private void postWaitingNotification() {
        try {
            Context lc = getLocalizedCtx();
            Intent openIntent = new Intent(this, com.pmahz.MainActivity.class);
            openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            int of = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) of |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent openPi = PendingIntent.getActivity(this, 3, openIntent, of);
            Intent stopIntent = new Intent(this, CustomRefreshActionReceiver.class);
            stopIntent.setAction("STOP_CUSTOM_REFRESH");
            int sf = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) sf |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent stopPi = PendingIntent.getBroadcast(this, 4, stopIntent, sf);
            String title = lc.getString(R.string.custom_notif_waiting_title);
            String text = lc.getString(R.string.custom_notif_waiting_text);
            Notification n = new NotificationCompat.Builder(this, CUSTOM_CHANNEL_ID)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setContentIntent(openPi)
                    .addAction(android.R.drawable.ic_media_pause, lc.getString(R.string.custom_notif_stop), stopPi)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .build();
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(CUSTOM_NOTIF_ID, n);
        } catch (Exception ignored) {}
    }
    private String resolveEffectivePkg(SharedPreferences prefs, String basePkg) {
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
            List<android.app.ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
            if (procs != null) {
                for (android.app.ActivityManager.RunningAppProcessInfo proc : procs) {
                    if (proc.importance != android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) continue;
                    if (proc.pkgList == null) continue;
                    boolean matches = false;
                    for (String p : proc.pkgList) {
                        if (p.equals(basePkg)) { matches = true; break; }
                    }
                    if (!matches) continue;
                    int userId = proc.uid / 100000;
                    if (userId > 0) {
                        String cloneKey = basePkg + ":u" + userId;
                        if (prefs.getBoolean("app_refresh_enabled_" + cloneKey, false)) {
                            return cloneKey;
                        }
                    }
                    return basePkg;
                }
            }
        } catch (Exception ignored) {}
        return basePkg;
    }
    private void applyDisplayTarget(String authMode, String res, int hz) {
        try {
            String[] wh = res.split("x");
            if (wh.length != 2) return;
            int targetW = Integer.parseInt(wh[0]);
            int targetH = Integer.parseInt(wh[1]);
            List<DisplayMode> modes = AutoOverclockManager.getSupportedModes(this);
            DisplayMode target = null;
            for (DisplayMode m : modes) {
                if (m.getWidth() == targetW && m.getHeight() == targetH && m.getRateInt() == hz) {
                    target = m;
                    break;
                }
            }
            if (target == null) {
                for (DisplayMode m : modes) {
                    if (m.getWidth() == targetW && m.getHeight() == targetH) {
                        if (target == null || Math.abs(m.getRateInt() - hz) < Math.abs(target.getRateInt() - hz)) {
                            target = m;
                        }
                    }
                }
            }
            if (target == null) return;
            if ("root".equals(authMode)) {
                RootUtils.setDisplayMode(target.getWidth(), target.getHeight(), target.getRateInt(), target.getSfIndex());
            } else if ("shizuku".equals(authMode)) {
                ShizukuUtils.setDisplayMode(target.getWidth(), target.getHeight(), target.getRateInt(), target.getSfIndex());
            }
            Log.d(TAG, "应用刷新率已切换: " + res + " @ " + hz + "Hz");
        } catch (Exception e) {
            Log.e(TAG, "应用刷新率切换失败: " + e.getMessage());
        }
    }
    private void checkAndRestartService() {
        SharedPreferences prefs = getSharedPreferences("s", MODE_PRIVATE);
        if (!prefs.getBoolean("auto_overclock", false)) return;
        String authMode = prefs.getString("auth_mode", "");
        String ocRes    = prefs.getString("oc_target_res", "");
        int    ocHz     = prefs.getInt("oc_target_hz", -1);
        if (authMode.isEmpty() || ocRes.isEmpty() || ocHz < 0) return;
        String[] wh = ocRes.split("x");
        if (wh.length < 2) return;
        try {
            int tw = Integer.parseInt(wh[0]);
            int th = Integer.parseInt(wh[1]);
            Intent si = new Intent(this, OverclockService.class);
            si.putExtra("auth_mode", authMode);
            si.putExtra("targetW", tw);
            si.putExtra("targetH", th);
            si.putExtra("targetHz", ocHz);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                startForegroundService(si);
            else
                startService(si);
        } catch (Exception e) {
            Log.e(TAG, "重启服务失败: " + e.getMessage());
        }
    }
}
