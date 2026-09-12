package com.github.tvbox.osc.util;

import android.app.Activity;
import android.content.Context;
import android.os.Build;

import com.hjq.permissions.OnPermissionCallback;
import com.hjq.permissions.XXPermissions;
import com.hjq.permissions.permission.PermissionLists;
import com.hjq.permissions.permission.base.IPermission;

/**
 * 存储权限统一入口(XXPermissions 28.x 对象模型 API)。
 * 本应用需要浏览/读写 /sdcard 任意路径(本地 jar、备份、文件浏览器、局域网共享):
 * Android 11+ 该能力依赖 MANAGE_EXTERNAL_STORAGE(系统"所有文件访问"设置页,无弹窗);
 * Android 10- 走 READ/WRITE 弹窗。Android 13+ 的旧存储权限会被系统静默拒绝,不可再依赖。
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
}
