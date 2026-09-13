package com.github.tvbox.osc.util;

import android.util.Log;

import com.github.tvbox.osc.base.App;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * @author pj567
 * @date :2020/12/18
 * @description:
 */
public class LOG {
    private static String TAG = "TVBox-runtime";
    private static final int MAX_LOG_LENGTH = 3000;

    /**
     * 临时排查通道(2026-09-12):本机 ROM(vivo/BBK)会吞掉第三方应用的 android.util.Log 输出 ——
     * 应用刚启动时 logcat 里 TAG=TVBox-runtime 为 0 行(而 System.err 正常),导致 echo-* 埋点抓不到。
     * 开启后把匹配前缀的日志异步追加到 files/preload_debug.log,用
     * `adb shell run-as <pkg> cat files/preload_debug.log` 取出。排查完把 FILE_LOG 置 false 即可。
     * 只落盘少数事件级前缀,不在热路径上,异步写不阻塞调用线程。
     */
    private static final boolean FILE_LOG = true;
    private static final String[] FILE_LOG_PREFIXES = {"echo-preload", "echo-setDataSource", "echo-play-cache", "echo-kv", "echo-exo-cache", "echo-music"};
    private static final String FILE_LOG_NAME = "preload_debug.log";
    private static ExecutorService fileLogExecutor;

    private static void fileLog(String level, String msg) {
        if (!FILE_LOG || msg == null) return;
        boolean match = false;
        for (String prefix : FILE_LOG_PREFIXES) {
            if (msg.startsWith(prefix)) {
                match = true;
                break;
            }
        }
        if (!match) return;
        final String line = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date()) + " " + level + " " + msg;
        synchronized (LOG.class) {
            if (fileLogExecutor == null) fileLogExecutor = Executors.newSingleThreadExecutor();
        }
        try {
            fileLogExecutor.execute(() -> {
                // 每行独立开关文件:保证进程被杀时已写入的内容不丢(排查场景量小,开销可接受)
                try (FileWriter writer = new FileWriter(new File(App.getInstance().getFilesDir(), FILE_LOG_NAME), true)) {
                    writer.write(line + "\n");
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable ignored) {
        }
    }

    public static void e(String msg) {
        Log.e(TAG, "" + msg);
        fileLog("E", String.valueOf(msg));
    }

    public static void i(String msg) {
        Log.i(TAG, "" + msg);
        fileLog("I", String.valueOf(msg));
    }

    public static void longI(String prefix, String msg) {
        longLog(Log.INFO, prefix, msg);
    }

    public static void longE(String prefix, String msg) {
        longLog(Log.ERROR, prefix, msg);
    }

    private static void longLog(int priority, String prefix, String msg) {
        String text = msg == null ? "null" : msg;
        String title = prefix == null ? "" : prefix;
        int length = text.length();
        if (length <= MAX_LOG_LENGTH) {
            Log.println(priority, TAG, title + text);
            return;
        }
        int count = (length + MAX_LOG_LENGTH - 1) / MAX_LOG_LENGTH;
        for (int i = 0; i < count; i++) {
            int start = i * MAX_LOG_LENGTH;
            int end = Math.min(start + MAX_LOG_LENGTH, length);
            Log.println(priority, TAG, title + "[" + (i + 1) + "/" + count + "] " + text.substring(start, end));
        }
    }
}
