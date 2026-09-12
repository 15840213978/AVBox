package com.github.tvbox.osc.util

import android.app.Activity
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * 本地配置文件(SAF 选择结果)→ 接口地址 转换。
 *
 * 2026-09-11 起改用系统 Storage Access Framework(SAF):不再使用自绘 LocalFileActivity,
 * 由调用页 Activity 注册 `OpenDocument` 契约,选中后经 [handleLocalConfigResult] 转成
 * `clan://` 接口地址并分发给 pending 回调(当前唯一入口 = 配置管理页「添加订阅」dialog 的「从本地选择」)。
 * SAF 自身带读取授权,**无需存储权限**即可选中任意位置的文件。
 */
object LocalConfigHost {
    /** 等待 SAF 选择结果的 pending 回调(同一时刻只会有一个选择流程) */
    var pending: ((api: String) -> Unit)? = null
}

/**
 * 启动系统文件选择器。挂载于调用页 Activity(当前 = `ConfigManageActivity`):
 * ```
 * private val localConfigLauncher = registerForActivityResult(
 *     ActivityResultContracts.OpenDocument()
 * ) { uri -> if (uri != null) handleLocalConfigResult(this, uri) }
 *
 * fun launchLocalConfig(onResult: (String) -> Unit) =
 *     startLocalConfig(localConfigLauncher, onResult)
 * ```
 */
fun startLocalConfig(
    launcher: ActivityResultLauncher<Array<String>>,
    onResult: (api: String) -> Unit,
) {
    LocalConfigHost.pending = onResult
    launcher.launch(arrayOf("*/*"))
}

/** SAF 结果入口:把选中的 Uri 转成 clan:// 接口地址,分发给 pending 回调 */
fun handleLocalConfigResult(activity: Activity, uri: Uri) {
    val callback = LocalConfigHost.pending
    LocalConfigHost.pending = null
    if (callback == null) return
    val api = localConfigToApi(activity, uri)
    if (api.isNullOrEmpty()) {
        Toast.makeText(activity, "读取本地配置失败", Toast.LENGTH_SHORT).show()
        return
    }
    callback(api)
}

/**
 * 配置 Uri → clan:// 接口地址:
 * 已授予存储权限且能取到真实路径时直接引用原文件;否则复制到 App 外置缓存 config/ 目录
 * (SAF 授权足够读取,不依赖存储权限),再转成 `clan://localhost/<相对路径>`。
 */
fun localConfigToApi(context: Context, uri: Uri): String? {
    var path = if (PermissionHelper.isStorageGranted(context)) getPathFromUri(context, uri) else null
    if (path.isNullOrEmpty()) {
        path = copyUriToLocalConfig(context, uri)
    }
    if (path.isNullOrEmpty()) return null
    val storageRoot = Environment.getExternalStorageDirectory().absolutePath
    if (!path.startsWith(storageRoot)) return null
    return "clan://localhost/" + path.substring(storageRoot.length).replaceFirst("^/+".toRegex(), "")
}

/** 旧 getPathFromUri 1:1:file scheme 直取；文档 Uri 解析 externalstorage/downloads 的 raw 路径 */
private fun getPathFromUri(context: Context, uri: Uri): String? {
    return try {
        if ("file".equals(uri.scheme, ignoreCase = true)) {
            return uri.path
        }
        if (DocumentsContract.isDocumentUri(context, uri)) {
            val docId = DocumentsContract.getDocumentId(uri)
            if ("com.android.externalstorage.documents" == uri.authority) {
                val split = docId.split(":")
                if (split.size > 1 && "primary".equals(split[0], ignoreCase = true)) {
                    return Environment.getExternalStorageDirectory().absolutePath + "/" + split[1]
                }
            }
            if ("com.android.providers.downloads.documents" == uri.authority && docId.startsWith("raw:")) {
                return docId.substring(4)
            }
        }
        null
    } catch (ignored: Throwable) {
        null
    }
}

/** 旧 copyUriToLocalConfig 1:1:复制 Uri 内容到外置缓存 config/<displayName> */
private fun copyUriToLocalConfig(context: Context, uri: Uri): String? {
    var input: InputStream? = null
    var output: FileOutputStream? = null
    return try {
        input = context.contentResolver.openInputStream(uri) ?: return null
        val dir = File(FileUtils.getExternalCachePath(), "config")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, getDisplayName(context, uri))
        output = FileOutputStream(file)
        val buffer = ByteArray(8192)
        var length: Int
        while (input.read(buffer).also { length = it } != -1) {
            output.write(buffer, 0, length)
        }
        file.absolutePath
    } catch (th: Throwable) {
        th.printStackTrace()
        null
    } finally {
        try {
            output?.close()
        } catch (ignored: Throwable) {
        }
        try {
            input?.close()
        } catch (ignored: Throwable) {
        }
    }
}

/** 旧 getDisplayName 1:1:取 DISPLAY_NAME,取不到用 local_config.json 兜底 */
private fun getDisplayName(context: Context, uri: Uri): String {
    var name = "local_config.json"
    var cursor: Cursor? = null
    try {
        cursor = context.contentResolver.query(uri, null, null, null, null)
        if (cursor != null && cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) {
                val displayName = cursor.getString(index)
                if (!displayName.isNullOrEmpty()) {
                    name = displayName
                }
            }
        }
    } catch (ignored: Throwable) {
    } finally {
        try {
            cursor?.close()
        } catch (ignored: Throwable) {
        }
    }
    return name
}
