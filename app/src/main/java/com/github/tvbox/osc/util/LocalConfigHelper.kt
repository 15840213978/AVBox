package com.github.tvbox.osc.util

import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * 本地配置文件(SAF 选择结果)→ 接口地址 转换:调用页 Activity 注册 `OpenDocument` 契约,
 * 选中后经 [handleLocalConfigResult] 交给 [localConfigToApi] 转成 `clan://` 地址
 * (唯一入口 = 配置管理页「添加订阅」dialog 的「从本地选择」;2026-09-11 起取代自绘 LocalFileActivity)。
 */
object LocalConfigHost {
    /** 等待 SAF 选择结果的 pending 回调(同一时刻只会有一个选择流程) */
    var pending: ((api: String) -> Unit)? = null
}

/** 启动系统文件选择器;挂载见 `ConfigManageActivity.localConfigLauncher` */
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
 * 配置 Uri → clan:// 接口地址:**优先直引原文件**,否则复制到外置缓存 `config/` 兜底。
 *
 * 直引条件缺一即复制:已授予存储权限(Android 11+ = 系统「所有文件访问」)、能解析出真实路径、
 * 路径存在且可读、且落在本地服务根目录下(`RemoteServer` 的 `/file/` 只服务
 * `Environment.getExternalStorageDirectory()`,SD 卡等非主卷取不到)。
 *
 * 为什么优先直引:配置里 `./x.jar`、`../lib/x.js` 这类同目录引用会被重写成"配置文件所在目录"的
 * http 前缀 —— 复制分支只带 json 过去、兄弟文件没跟过来,这些引用会 404。
 */
fun localConfigToApi(context: Context, uri: Uri): String? {
    val storageRoot = Environment.getExternalStorageDirectory().absolutePath
    if (PermissionHelper.isStorageGranted(context)) {
        toClanApi(readablePath(getPathFromUri(context, uri)), storageRoot)?.let { return it }
    }
    return toClanApi(copyUriToLocalConfig(context, uri), storageRoot)
}

/** 直引前校验存在且可读:MediaStore 的 DATA 列可能指向已删除/已移动的文件,直引会让整个源拉取失败 */
private fun readablePath(path: String?): String? {
    if (path.isNullOrEmpty()) return null
    val file = File(path)
    return if (file.isFile && file.canRead()) path else null
}

/** 真实路径 → clan:// 地址;为空或不在本地服务根目录下时返回 null(= 应走复制) */
internal fun toClanApi(path: String?, storageRoot: String): String? {
    if (path.isNullOrEmpty() || !path.startsWith(storageRoot)) return null
    return "clan://localhost/" + path.substring(storageRoot.length).replaceFirst("^/+".toRegex(), "")
}

/** SAF Uri → 真实文件路径;解析不出返回 null(= 应复制) */
private fun getPathFromUri(context: Context, uri: Uri): String? {
    return try {
        if ("file".equals(uri.scheme, ignoreCase = true)) return uri.path
        if (DocumentsContract.isDocumentUri(context, uri)) return getDocumentPath(context, uri)
        if ("content".equals(uri.scheme, ignoreCase = true)) getDataColumn(context, uri) else null
    } catch (ignored: Throwable) {
        null
    }
}

/**
 * 文档 Uri → 路径(2026-09-16 扩写,对齐 FongMi `FileChooser.getDocumentPath`)。
 *
 * 旧实现只认 `primary:` 与 `raw:`,于是从选择器「下载」分类(`msf:<id>`)或「最近」(`document:<id>`)
 * 选中的文件都解析失败 → 落到复制分支 → 同目录引用 404。按 provider 逐一解析,解析不出的仍复制;
 * 下面几个纯字符串函数由 `LocalConfigPathTest` 覆盖。
 */
private fun getDocumentPath(context: Context, uri: Uri): String? {
    val docId = DocumentsContract.getDocumentId(uri)
    return when (uri.authority) {
        "com.android.externalstorage.documents" ->
            externalStoragePath(docId, Environment.getExternalStorageDirectory().absolutePath)

        "com.android.providers.downloads.documents" -> downloadPath(context, uri, docId)
        "com.android.providers.media.documents" -> mediaPath(context, docId)
        else -> null
    }
}

/** externalstorage:`primary:` 挂外置存储根,`XXXX-XXXX:`(SD 卡)挂 `/storage/<卷名>` */
internal fun externalStoragePath(docId: String, primaryRoot: String): String? {
    val split = docId.split(":", limit = 2)
    if (split.size < 2 || split[1].isEmpty()) return null
    if ("primary".equals(split[0], ignoreCase = true)) return "$primaryRoot/${split[1]}"
    return "/storage/${docId.replace(':', '/')}"
}

/** downloads 的 docId 是否 MediaStore 形态(`msf:<id>`;老条目是 `raw:<绝对路径>` 或纯数字 id) */
internal fun isMediaStoreDownloadId(docId: String): Boolean = docId.startsWith("msf:")

/** downloads 的数值 id(已去 `msf:` 前缀);非数字(含 `raw:` 形态)返回 null */
internal fun downloadNumericId(docId: String): Long? =
    (if (isMediaStoreDownloadId(docId)) docId.substring(4) else docId).toLongOrNull()

/** media 的 docId(`image:123`/`document:456`)→ 类型与数值 id;解析不了返回 null */
internal fun mediaDocId(docId: String): Pair<String, Long>? {
    val split = docId.split(":", limit = 2)
    if (split.size < 2) return null
    val id = split[1].toLongOrNull() ?: return null
    return split[0] to id
}

/**
 * downloads → 真实路径;`msf:` 需 API 29+。三段尝试:`MediaStore.Downloads` → `MediaStore.Files`
 * (前两段常因该行 `is_download=0` —— 文件是拷进 Download 目录而非下载器下载的 —— 查不到)→
 * 按显示名拼 `<外置存储根>/Download/<名>`。
 *
 * 后两段都设闸:第二段**有结果但名字不符即放弃**(id 指向别的文件时不能再叠加猜测,猜同名路径
 * 可能引到另一个同名文件);第三段只取 basename 且须过 [readablePath]。宁可 null 走复制。
 */
private fun downloadPath(context: Context, uri: Uri, docId: String): String? {
    if (docId.startsWith("raw:")) return docId.substring(4)
    val id = downloadNumericId(docId) ?: return null
    if (!isMediaStoreDownloadId(docId)) {
        return getDataColumn(context, ContentUris.withAppendedId(Uri.parse("content://downloads/public_downloads"), id))
    }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
    val displayName = getDisplayName(context, uri)
    val downloads = ContentUris.withAppendedId(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL), id)
    getDataColumn(context, downloads)?.let { return it }
    val files = ContentUris.withAppendedId(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), id)
    val path = getDataColumn(context, files)
    if (path != null) return if (sameFileName(path, displayName)) path else null
    val guess = downloadGuessPath(Environment.getExternalStorageDirectory().absolutePath, displayName)
    return readablePath(guess)
}

/** 「下载」兜底猜测路径 = `<外置存储根>/Download/<显示名>`;名字为空 / `.` / `..` 时 null(纯函数,单测覆盖) */
internal fun downloadGuessPath(root: String, displayName: String): String? {
    val name = displayName.substringAfterLast('/').trim()
    if (name.isEmpty() || name == "." || name == "..") return null
    return "$root/Download/$name"
}

/** 两个路径的文件名是否一致(id 是否指向选中文件的校验);任一为空即 false */
internal fun sameFileName(path: String?, displayName: String?): Boolean {
    if (path.isNullOrEmpty() || displayName.isNullOrEmpty()) return false
    return path.substringAfterLast('/') == displayName.substringAfterLast('/')
}

/** media → 真实路径;`document:`(「最近」里的 json/txt/m3u)走 MediaStore.Files */
private fun mediaPath(context: Context, docId: String): String? {
    val (type, id) = mediaDocId(docId) ?: return null
    val volume = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.VOLUME_EXTERNAL else "external"
    val target = when (type) {
        "image" -> MediaStore.Images.Media.getContentUri(volume)
        "video" -> MediaStore.Video.Media.getContentUri(volume)
        "audio" -> MediaStore.Audio.Media.getContentUri(volume)
        else -> MediaStore.Files.getContentUri(volume)
    }
    return getDataColumn(context, ContentUris.withAppendedId(target, id))
}

/** 查 MediaStore 的 DATA 列;查询失败一律按"解析不出"处理,由调用方复制兜底 */
private fun getDataColumn(context: Context, uri: Uri): String? {
    var cursor: Cursor? = null
    return try {
        cursor = context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)
        if (cursor != null && cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
            if (index >= 0) cursor.getString(index) else null
        } else {
            null
        }
    } catch (ignored: Throwable) {
        null
    } finally {
        try {
            cursor?.close()
        } catch (ignored: Throwable) {
        }
    }
}

/** 副本文件名 = `md5(uri)_原名`:同一文件重复导入覆盖自己,不同来源的同名文件互不影响 */
private fun copyFileName(context: Context, uri: Uri): String {
    val name = getDisplayName(context, uri).replace('/', '_').replace('\\', '_')
    return MD5.encode(uri.toString()) + "_" + name
}

/**
 * 复制 Uri 内容到外置私有 `files/config/`(SAF 授权足够读取,不依赖存储权限)。落点选 files 而非 cache:
 * 系统「清除缓存」会把 cache 副本删掉,之后只能静默回落到 `filesDir` 旧快照(用户改本地 json 不生效)。
 */
private fun copyUriToLocalConfig(context: Context, uri: Uri): String? {
    var input: InputStream? = null
    var output: FileOutputStream? = null
    return try {
        input = context.contentResolver.openInputStream(uri) ?: return null
        val dir = File(FileUtils.getExternalFilesPath(), "config")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, copyFileName(context, uri))
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

/** 取 DISPLAY_NAME,取不到用 local_config.json 兜底 */
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
