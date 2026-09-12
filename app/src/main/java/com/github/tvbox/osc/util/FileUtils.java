package com.github.tvbox.osc.util;

import android.os.Environment;
import android.text.TextUtils;
import android.util.Base64;

import com.github.catvod.net.OkHttp;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.server.ControlManager;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FileUtils {

    public static boolean writeSimple(byte[] data, File dst) {
        try {
            if (dst.exists())
                dst.delete();
            BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(dst));
            bos.write(data);
            bos.close();
            return true;
        } catch (IOException e) {
            e.printStackTrace();
        }
        return false;
    }

    public static byte[] readSimple(File src) {
        try {
            BufferedInputStream bis = new BufferedInputStream(new FileInputStream(src));
            int len = bis.available();
            byte[] data = new byte[len];
            bis.read(data);
            bis.close();
            return data;
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    public static void copyFile(File source, File dest) throws IOException {
        InputStream is = null;
        OutputStream os = null;
        try {
            is = new FileInputStream(source);
            os = new FileOutputStream(dest);
            byte[] buffer = new byte[1024];
            int length;
            while ((length = is.read(buffer)) > 0) {
                os.write(buffer, 0, length);
            }
        } finally {
            is.close();
            os.close();
        }
    }

    public static void recursiveDelete(File file) {
        if (!file.exists())
            return;
        if (file.isDirectory()) {
            for (File f : file.listFiles()) {
                recursiveDelete(f);
            }
        }
        file.delete();
    }

    public static String readFileToString(String path, String charsetName) {
        // 定义返回结果
        String jsonString = "";

        BufferedReader in = null;
        try {
            in = new BufferedReader(new InputStreamReader(new FileInputStream(new File(path)), charsetName));// 读取文件
            String thisLine = null;
            while ((thisLine = in.readLine()) != null) {
                jsonString += thisLine;
            }
            in.close();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException el) {
                }
            }
        }
        // 返回拼接好的JSON String
        return jsonString;
    }

    public static String getRootPath() {
        return Environment.getExternalStorageDirectory().getAbsolutePath();
    }

    public static File getLocal(String path) {
        return new File(path.replace("file:/", getRootPath()));
    }

    public static File getCacheDir() {
        return App.getInstance().getCacheDir();
    }

    public static String getCachePath() {
        return getCacheDir().getAbsolutePath();
    }
    public static String getFilePath() {
        return App.getInstance().getFilesDir().getAbsolutePath();
    }

    public static void cleanDirectory(File dir) {
        if (!dir.exists()) return;
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) return;
        for(File one : files) {
            try {
                deleteFile(one);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public static boolean isWeekAgo(File file)
    {
        long oneWeekMillis = 3L * 24 * 60 * 60 * 1000;
        long timeDiff = System.currentTimeMillis() - file.lastModified();
        return timeDiff > oneWeekMillis;
    }

    /**
     * 递归删除:文件直接删,目录先删空其内容。
     * ⚠️ 不能把 canWrite() 当删除前提(2026-09-12 修复 Bug):爬虫 jar 释放的加固库是 400 只读
     * (实测 cache/danMugo_v8.so 13,441,840B + cache/.ftyfnw3nr9fd9ykv,两者合计正好是设置页显示的 12.9MB),
     * canWrite() 为 false 时会被直接跳过,于是「清除缓存」怎么点都清不掉、大小恒定不变。
     * 删除只要求**父目录**可写,与文件自身只读无关,故这里先尝试解除只读再删。
     */
    public static void deleteFile(File file) {
        if (!file.exists()) return;
        if (file.isFile()) {
            deleteSingle(file);
            return;
        }
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files == null || files.length == 0) {
                deleteSingle(file);
                return;
            }
            for(File one : files) {
                deleteFile(one);
            }
        }
        return;
    }

    /** 删除单个文件/空目录;只读项先解除只读再删,仍失败则留日志便于排查 */
    private static void deleteSingle(File file) {
        if (!file.canWrite()) file.setWritable(true);
        if (!file.delete()) {
            LOG.i("clearCache: cannot delete " + file.getAbsolutePath());
        }
    }

    public static void cleanPlayerCache() {
        String ijkCachePath = getCachePath() + "/ijkcaches/";
        String thunderCachePath = getCachePath() + "/thunder/";
        File ijkCacheDir = new File(ijkCachePath);
        File thunderCacheDir = new File(thunderCachePath);
        try {
            if (ijkCacheDir.exists()) cleanDirectory(ijkCacheDir);
        } catch (Exception e) {
            e.printStackTrace();
        }
        try {
            if (thunderCacheDir.exists()) cleanDirectory(thunderCacheDir);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 外置缓存中属于**用户数据**、清理时必须保留的一级目录:config/ =
     * 配置管理页「从本地选择」经 SAF 导入的接口配置(clan:// 地址直接指向该文件),
     * 删掉等于把用户的订阅源弄丢。⚠️ 系统设置里的「清除缓存」会连同它一起删,属既有设计隐患
     * (用户数据本不该放在 cacheDir),此处只保证应用内入口不误删。
     */
    private static final String EXTERNAL_CACHE_KEEP_DIR = "config";

    /**
     * 应用缓存占用(内部缓存 + 外部缓存,不含 {@link #EXTERNAL_CACHE_KEEP_DIR} 用户数据)。
     * 口径说明:Exo 视频缓存目录 exo-video-cache 位于外部缓存,只统计内部缓存会漏掉绝大部分占用;
     * 外部缓存不可用时 getExternalCacheDir() 返回 null,此时不叠加,避免与内部缓存重复计数。
     * 目录递归统计是耗时 IO,须在后台线程调用。
     */
    public static long getCacheSize() {
        long size = directorySize(getCacheDir(), null);
        File externalCacheDir = App.getInstance().getExternalCacheDir();
        if (externalCacheDir != null && !externalCacheDir.getAbsolutePath().equals(getCachePath())) {
            size += directorySize(externalCacheDir, EXTERNAL_CACHE_KEEP_DIR);
        }
        return size;
    }

    /** [skipChildName] 只在顶层生效:跳过该名字的一级子项(用于排除外置缓存里的用户数据) */
    private static long directorySize(File dir, String skipChildName) {
        if (dir == null || !dir.exists()) return 0;
        if (dir.isFile()) return dir.length();
        File[] files = dir.listFiles();
        if (files == null) return 0;
        long size = 0;
        for (File one : files) {
            if (skipChildName != null && skipChildName.equals(one.getName())) continue;
            size += one.isDirectory() ? directorySize(one, null) : one.length();
        }
        return size;
    }

    /** 字节数 → 展示文本(空缓存显示 0KB);与统计一致,须在后台线程调用 */
    public static String formatCacheSize(long bytes) {
        if (bytes <= 0) return "0KB";
        if (bytes < 1024L * 1024L) return Math.max(1, bytes / 1024) + "KB";
        if (bytes < 1024L * 1024L * 1024L) return String.format(Locale.US, "%.1fMB", bytes / 1024.0 / 1024);
        return String.format(Locale.US, "%.2fGB", bytes / 1024.0 / 1024 / 1024);
    }

    /**
     * 清理应用缓存(内部 + 外部,含 Exo 视频缓存目录 exo-video-cache)。
     * 与系统设置里的「清除缓存」同语义,因此也会清掉已下载的爬虫 jar 缓存(cache/jar/),
     * 下次启动按需重新下载;{@link #EXTERNAL_CACHE_KEEP_DIR} 用户数据保留不动。
     * 不 release 正在使用的 SimpleCache:media3 对缓存文件缺失按 miss 处理
     * (CacheDataSource 带 FLAG_IGNORE_CACHE_ON_ERROR),后续播放重新回源写盘;
     * 只清空目录内容、保留目录本身。耗时 IO,须在后台线程调用。
     */
    public static void clearCache() {
        cleanDirectory(getCacheDir());
        File externalCacheDir = App.getInstance().getExternalCacheDir();
        if (externalCacheDir == null) return;
        File[] files = externalCacheDir.listFiles();
        if (files == null) return;
        for (File one : files) {
            if (EXTERNAL_CACHE_KEEP_DIR.equals(one.getName())) continue;
            try {
                deleteFile(one);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public static void clearSpiderCacheFiles() {
        cleanDirectory(new File(getFilePath() + "/csp/"));
        cleanDirectory(new File(getCachePath() + "/jar/"));
        cleanDirectory(new File(getCachePath() + "/py/"));
        cleanDirectory(new File(getCachePath() + "/catvod_jsapi/"));
        clearJsModuleCache();
    }

    private static void clearJsModuleCache() {
        File externalCacheDir = new File(getExternalCachePath());
        File[] files = externalCacheDir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file != null && file.getName().startsWith("qjscache_")) {
                deleteFile(file);
            }
        }
    }

    public static String read(String path) {
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(getLocal(path))));
            StringBuilder sb = new StringBuilder();
            String text;
            while ((text = br.readLine()) != null) sb.append(text).append("\n");
            br.close();
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    public static String getFileName(String filePath){
        if(TextUtils.isEmpty(filePath)) return "";
        String fileName = filePath;
        int p = fileName.lastIndexOf(File.separatorChar);
        if(p != -1){
            fileName = fileName.substring(p + 1);
        }
        return fileName;
    }

    public static String getFileNameWithoutExt(String filePath){
        if(TextUtils.isEmpty(filePath)) return "";
        String fileName = filePath;
        int p = fileName.lastIndexOf(File.separatorChar);
        if(p != -1){
            fileName = fileName.substring(p + 1);
        }
        p = fileName.indexOf('.');
        if(p != -1){
            fileName = fileName.substring(0, p);
        }
        return fileName;
    }


    public static boolean hasExtension(String path) {
        int lastDotIndex = path.lastIndexOf(".");
        int lastSlashIndex = Math.max(path.lastIndexOf("/"), path.lastIndexOf("\\"));
        // 如果路径中有点号，并且点号在最后一个斜杠之后，认为有后缀
        return lastDotIndex > lastSlashIndex && lastDotIndex < path.length() - 1;
    }

    public static void saveCache(File cache,String json){
        try {
            File cacheDir = cache.getParentFile();
            if (!cacheDir.exists())
                cacheDir.mkdirs();
            if (cache.exists())
                cache.delete();
            FileOutputStream fos = new FileOutputStream(cache);
            fos.write(json.getBytes("UTF-8"));
            fos.flush();
            fos.close();
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }





    //JS  工具方法
    private static final Pattern URL_JOIN = Pattern.compile("^http.*\\.(js|txt|json)", Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);
    public static String loadModule(String name) {
        String rel = null;
        try {
            if (name.contains("gbk.js")) {
                name = "gbk.js";
            } else if (name.contains("模板.js")) {
                name = "模板.js";
            } else if (name.contains("cat.js")) {
                name = "cat.js";
            }
            LOG.i("echo-loadModule "+name);
            Matcher m = URL_JOIN.matcher(name);
            if (m.find()) {
                String cache = getCache(MD5.encode(name));
                rel= cache;
                if (StringUtils.isEmpty(cache)) {
                    String netStr = get(name);
                    if (!TextUtils.isEmpty(netStr)) {
                        setCache(604800, MD5.encode(name), netStr);
                    }
                    rel= netStr;
                }
            } else if (name.startsWith("assets://")) {
                rel= getAsOpen(name.substring(9));
            } else if (isAsFile(name, "js/lib")) {
                rel=getAsOpen("js/lib/" + name);
            } else if (name.startsWith("file://")) {
                rel=get(ControlManager.get()
                        .getAddress(true) + "file/" + name.replace("file:///", "")
                        .replace("file://", ""));
            } else if (name.startsWith("clan://localhost/")) {
                rel=get(ControlManager.get()
                        .getAddress(true) + "file/" + name.replace("clan://localhost/", ""));
            } else if (name.startsWith("clan://")) {
                String substring = name.substring(7);
                int indexOf = substring.indexOf(47);
                rel=get("http://" + substring.substring(0, indexOf) + "/file/" + substring.substring(indexOf + 1));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return rel;
    }


    // BugReview #26:被 NanoHTTPD 线程、主线程、QuickJS 线程并发读写,改并发容器
    private static final Map<String, Set<String>> cachedDirFiles = new ConcurrentHashMap<>();
    public static boolean isAsFile(String name,String dir) {
        // 1. 先从缓存里取目录列表
        Set<String> files = cachedDirFiles.get(dir);
        if (files == null) {
            LOG.i("echo-读取AssetsList");
            try {
                String[] list = App.getInstance().getAssets().list(dir);
                files = new HashSet<>(Arrays.asList(list));
            } catch (IOException e) {
                files = Collections.emptySet();
            }
            cachedDirFiles.put(dir, files);
        }
        // 2. 内存查找
        return files.contains(name.trim());
    }

    public static String getAsOpen(String name) {
        try {
            InputStream is = App.getInstance().getAssets().open(name);
            byte[] data = new byte[is.available()];
            is.read(data);
            return new String(data, "UTF-8");
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "";
    }

    public static String getCache(String name) {
        try {
            String code = "";
            File file = open(name);
            if (file.exists()) {
                code = new String(readSimple(file));
            }
            if (TextUtils.isEmpty(code)) {
                return "";
            }
            JsonObject asJsonObject = (new Gson().fromJson(code, JsonObject.class)).getAsJsonObject();
            if (((long) asJsonObject.get("expires").getAsInt()) <= System.currentTimeMillis() / 1000) {
                recursiveDelete(open(name));
            }
            return asJsonObject.get("data").getAsString();
        } catch (Exception e4) {
            return "";
        }
    }

    public static void setCache(int time, String name, String data) {
        try {
            JSONObject jSONObject = new JSONObject();
            jSONObject.put("expires", (int) (time + (System.currentTimeMillis() / 1000)));
            jSONObject.put("data", data);
            writeSimple(jSONObject.toString().getBytes(), open(name));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void setCacheByte(String name, byte[] data) {
        try {
            writeSimple(byteMerger("//DRPY".getBytes(), Base64.encode(data, Base64.URL_SAFE)), open("B_" + name));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static byte[] byteMerger(byte[] bt1, byte[] bt2){
        byte[] bt3 = new byte[bt1.length+bt2.length];
        System.arraycopy(bt1, 0, bt3, 0, bt1.length);
        System.arraycopy(bt2, 0, bt3, bt1.length, bt2.length);
        return bt3;
    }

    public static String get(String str) {
        return get(str, null);
    }

    public static String get(String str, Map<String, String> headerMap) {
        if (headerMap == null) {
            headerMap=new HashMap<>();
            headerMap.put("User-Agent",str.startsWith("https://gitcode.net/") ? UA.random() : "okhttp/3.15");
        }
        return OkHttp.string(str, headerMap);
    }

    public static File open(String str) {
        return new File(getExternalCachePath() + "/qjscache_" + str + ".js");
    }
    public static String getExternalCachePath() {
        File externalCacheDir = App.getInstance().getExternalCacheDir();
        if (externalCacheDir == null){
            return getCachePath();
        }
        return externalCacheDir.getAbsolutePath();
    }
}
