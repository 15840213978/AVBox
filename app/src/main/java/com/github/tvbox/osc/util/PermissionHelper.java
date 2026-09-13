package com.github.tvbox.osc.util;

import android.app.Activity;
import android.content.Context;
import android.os.Build;

import com.hjq.permissions.OnPermissionCallback;
import com.hjq.permissions.XXPermissions;
import com.hjq.permissions.permission.PermissionLists;
import com.hjq.permissions.permission.base.IPermission;

/**
 * 权限统一入口(XXPermissions 28.x 对象模型 API)。
 *
 * <p>存储权限:本应用需要浏览/读写 /sdcard 任意路径(本地 jar、备份、文件浏览器、局域网共享):
 * Android 11+ 该能力依赖 MANAGE_EXTERNAL_STORAGE(系统"所有文件访问"设置页,无弹窗);
 * Android 10- 走 READ/WRITE 弹窗。Android 13+ 的旧存储权限会被系统静默拒绝,不可再依赖。
 *
 * <p>通知权限(2026-09-13 补):`POST_NOTIFICATIONS` 自 Android 13(API 33)起是**运行时权限**,
 * 清单里声明了也必须显式申请,否则音乐后台播放的**前台服务通知不显示**(服务本身能起,
 * 但用户看不到播放控制,且部分 ROM 会限制无可见通知的前台服务)。
 */
public final class PermissionHelper {

    private PermissionHelper() {
    }

    public static boolean isStorageGranted(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return XXPermissions.isGrantedPermission(context, PermissionLists.getManageExternalStoragePermission());
        }
        return XXPermissions.isGrantedPermissions(context, new IPermission[]{
                PermissionLists.getReadExternalStoragePermission(),
                PermissionLists.getWriteExternalStoragePermission()});
    }

    public static void requestStorage(Activity activity, OnPermissionCallback callback) {
        IPermission[] permissions = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                ? new IPermission[]{PermissionLists.getManageExternalStoragePermission()}
                : new IPermission[]{
                        PermissionLists.getReadExternalStoragePermission(),
                        PermissionLists.getWriteExternalStoragePermission()};
        XXPermissions.with(activity).permissions(permissions).request(callback);
    }

    /**
     * 申请通知权限(仅 Android 13+ 需要;低版本该权限由系统默认授予,申请也无意义)。
     *
     * <p>**不阻断主流程**:拒绝授权只是没有通知,音乐照常播放,调用方无需处理结果。
     */
    public static void requestNotificationIfNeeded(Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        if (XXPermissions.isGrantedPermission(activity, PermissionLists.getPostNotificationsPermission())) return;
        XXPermissions.with(activity)
                .permission(PermissionLists.getPostNotificationsPermission())
                .request((permissions, allGranted) -> {
                    // 拒绝不影响任何功能:只是前台服务通知不展示,音乐照常播放
                });
    }
}
