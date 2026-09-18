package com.tdiqsy.app;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Debug;
import android.provider.Settings;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/**
 * 运行环境检测（安全加固）
 *
 * 检测项：
 *  1) 模拟器 / 虚拟机         —— 常见云真机、模拟器特征
 *  2) 设备已 Root             —— su 程序 / test-keys
 *  3) VPN / 虚拟网络          —— tun/tap/ppp/wg 等虚拟网卡
 *  4) 系统代理 / 抓包代理     —— http(s) 系统代理、全局 http_proxy
 *  5) Hook / 抓包框架         —— frida / xposed / substrate 注入特征
 *  6) 调试器 / 可调试状态     —— isDebuggerConnected / debuggable
 *
 * 任意一项命中即认为环境不安全，由 SecurityGuard 统一阻断并退出。
 */
public final class EnvDetector {

    private EnvDetector() {
    }

    /** 返回命中的不安全原因列表；为空表示环境安全。 */
    public static List<String> detect(Context context) {
        List<String> reasons = new ArrayList<>();
        if (isEmulator()) reasons.add("检测到模拟器 / 虚拟机环境");
        if (isRooted()) reasons.add("检测到设备已 ROOT");
        if (isVpnActive()) reasons.add("检测到 VPN / 虚拟网络代理");
        if (hasProxy(context)) reasons.add("检测到系统代理 / 抓包代理");
        if (isHooked()) reasons.add("检测到 Hook / 抓包注入框架");
        if (isDebuggerAttached(context)) reasons.add("检测到调试器 / 可调试状态");
        return reasons;
    }

    /** 模拟器检测 */
    public static boolean isEmulator() {
        String finger = Build.FINGERPRINT == null ? "" : Build.FINGERPRINT;
        String model = Build.MODEL == null ? "" : Build.MODEL;
        String hw = Build.HARDWARE == null ? "" : Build.HARDWARE;
        String product = Build.PRODUCT == null ? "" : Build.PRODUCT;
        String manufacturer = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER;
        String brand = Build.BRAND == null ? "" : Build.BRAND;

        String low = (finger + " " + model + " " + hw + " " + product + " " + manufacturer + " " + brand).toLowerCase();
        String[] marks = {
                "sdk", "sdk_gphone", "google_sdk", "emulator", "genymotion", "goldfish",
                "ranchu", "vbox", "nox", "mumu", "tian_tian", "micrsoift", "droid4x",
                "andy", "ttvm", "tvm", "virtual" /* 国际化：多语言模拟器/云机 */ , "windsx",
                "pantech", "simulator", "kvm", "qemu", "lynx", "vodabye"
        };
        for (String m : marks) {
            if (low.contains(m)) return true;
        }
        if (low.contains("test-keys")) return true;

        // Redmi / 通用：硬件指标项
        if (Build.TAGS != null && low.contains("test-keys")) return true;

        return checkBuildIsQemu()
                || fileExists("/system/lib/libc_malloc_debug_qemu.so")
                || fileExists("/system/bin/qemu-props")
                || fileExists("/dev/socket/qemud")
                || fileExists("/dev/qemu_pipe")
                || fileExists("/system/bin/qemu-android")
                || fileExists("/system/lib/libgoldfish.so");
    }

    private static boolean checkBuildIsQemu() {
        try {
            try {
                Class<?> sp = Class.forName("android.os.SystemProperties");
                java.lang.reflect.Method m = sp.getMethod("get", String.class);
                String qemu = (String) m.invoke(null, "ro.kernel.qemu");
                if (qemu != null && qemu.equals("1")) return true;
                String kvm = (String) m.invoke(null, "ro.boot.qemu");
                if (kvm != null && kvm.equals("1")) return true;
                String avd = (String) m.invoke(null, "ro.product.device");
                if (avd != null && avd.toLowerCase().contains("sdk")) return true;
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** Root 检测：常见 su 路径 + test-keys */
    public static boolean isRooted() {
        String[] suPaths = {
                "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
                "/system/app/Superuser.apk", "/system/app/SuperSU.apk",
                "/data/local/bin/su", "/data/local/xbin/su", "/data/adb/su",
                "/system/etc/init.d/99SuperSUDaemon", "/system/xbin/busybox",
                "/system/bin/magisk", "/sbin/magisk", "/data/adb/magisk"
        };
        for (String p : suPaths) {
            if (fileExists(p)) return true;
        }
        String tags = Build.TAGS == null ? "" : Build.TAGS;
        if (tags.contains("test-keys")) return true;
        return fileExists("/data/local/tmp/frida-server");
    }

    /** VPN / 虚拟网卡检测 */
    public static boolean isVpnActive() {
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            if (ifs == null) return false;
            while (ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                try {
                    if (!ni.isUp()) continue;
                } catch (Throwable ignored) {
                    continue;
                }
                String name = ni.getName() == null ? "" : ni.getName().toLowerCase();
                if (name.startsWith("tun") || name.startsWith("tap")
                        || name.startsWith("ppp") || name.startsWith("wg")
                        || name.startsWith("utun") || name.startsWith("ipsec")
                        || name.startsWith("softether") || name.startsWith("openvpn")
                        || name.startsWith("tinc") || name.startsWith("ssl")
                        || name.startsWith("hex") || name.startsWith("gtun")
                        || name.startsWith("tunl") || name.startsWith("sit")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 系统代理 / 抓包代理检测 */
    public static boolean hasProxy(Context context) {
        try {
            String httpHost = System.getProperty("http.proxyHost");
            String httpsHost = System.getProperty("https.proxyHost");
            if ((httpHost != null && !httpHost.isEmpty()) || (httpsHost != null && !httpsHost.isEmpty())) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        // 全局代理（部分机型/抓包工具写入）
        try {
            String global = Settings.Global.getString(context.getContentResolver(),
                    Settings.Global.HTTP_PROXY);
            if (global != null && !global.isEmpty() && !"0.0.0.0:0".equals(global) && !":0".equals(global)) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** Hook / 抓包框架检测：遍历 /proc/self/maps，查找 frida / xposed / substrate 等注入特征 */
    public static boolean isHooked() {
        try {
            File maps = new File("/proc/self/maps");
            if (!maps.exists()) return false;
            BufferedReader br = new BufferedReader(new FileReader(maps), 8192);
            String line;
            int scanned = 0;
            try {
                while ((line = br.readLine()) != null && scanned++ < 40000) {
                    String l = line.toLowerCase();
                    if (l.contains("frida") || l.contains("gum-js")
                            || l.contains("libxposed") || l.contains("xposed")
                            || l.contains("substrate") || l.contains("cydia")
                            || l.contains("libbzhook") || l.contains("libriru")
                            || l.contains("whale") || l.contains("dobby")) {
                        return true;
                    }
                }
            } finally {
                br.close();
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 调试器 / 可调试状态检测 */
    public static boolean isDebuggerAttached(Context context) {
        try {
            if (Debug.isDebuggerConnected()) return true;
            if (Debug.waitingForDebugger()) return true;
            ApplicationInfo ai = context.getApplicationInfo();
            if (ai != null && (ai.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) return true;
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean fileExists(String path) {
        try {
            return new File(path).exists();
        } catch (Throwable ignored) {
            return false;
        }
    }
}