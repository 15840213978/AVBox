package com.github.tvbox.osc.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本地源导入的路径解析纯函数单测(2026-09-16)。
 *
 * 为什么值得测:判定错的后果是"配置能导入、但同目录 jar/js 404"(该直引时落到了复制分支),
 * 或者反过来"直引到本地服务读不到的位置"(SD 卡 —— `RemoteServer` 的 `/file/` 只服务外置存储根)
 * 导致整个源拉取失败。两者都只在真机选文件时才暴露,而这几个函数本身不碰 `android.*`,
 * 可以纯 JVM 覆盖(`localConfigToApi` 那层系统调用不进单测)。
 */
class LocalConfigPathTest {

    private val root = "/storage/emulated/0"

    // ---- 直引 / 复制的分水岭 ----

    @Test
    fun pathUnderStorageRootBecomesClanUrl() {
        assertEquals(
            "clan://localhost/Download/tvbox.json",
            toClanApi("$root/Download/tvbox.json", root),
        )
        // 复制分支的落点(外置缓存)同属外置存储根,必须也能转成地址
        assertEquals(
            "clan://localhost/Android/data/com.github.avbox.osc/cache/config/tvbox.json",
            toClanApi("$root/Android/data/com.github.avbox.osc/cache/config/tvbox.json", root),
        )
    }

    @Test
    fun pathOutsideStorageRootMustFallBackToCopy() {
        assertNull(toClanApi("/storage/ABCD-1234/tvbox.json", root))
        assertNull(toClanApi("/data/user/0/com.github.avbox.osc/cache/config/tvbox.json", root))
        assertNull(toClanApi(null, root))
        assertNull(toClanApi("", root))
    }

    // ---- externalstorage provider(选择器的「内部存储」目录树 / SD 卡) ----

    @Test
    fun externalStoragePrimaryDocId() {
        assertEquals("$root/Download/tvbox.json", externalStoragePath("primary:Download/tvbox.json", root))
    }

    @Test
    fun externalStorageSecondaryVolumeDocId() {
        assertEquals("/storage/ABCD-1234/TVBox/x.json", externalStoragePath("ABCD-1234:TVBox/x.json", root))
    }

    @Test
    fun externalStorageBadDocId() {
        assertNull(externalStoragePath("primary:", root))
        assertNull(externalStoragePath("nodocid", root))
    }

    // ---- downloads provider(选择器的「下载」分类;Android 10+ 主流形态是 msf:) ----

    @Test
    fun downloadDocIdForms() {
        assertTrue(isMediaStoreDownloadId("msf:1000000123"))
        assertFalse(isMediaStoreDownloadId("1000000123"))
        assertFalse(isMediaStoreDownloadId("raw:/storage/emulated/0/Download/x.json"))
        assertFalse(isMediaStoreDownloadId("document:456"))
    }

    @Test
    fun downloadNumericIdForms() {
        assertEquals(1000000123L, downloadNumericId("msf:1000000123") ?: -1L)
        assertEquals(1000000123L, downloadNumericId("1000000123") ?: -1L)
        assertNull(downloadNumericId("raw:/storage/emulated/0/Download/x.json"))
        assertNull(downloadNumericId("msf:abc"))
    }

    // ---- media provider(选择器的「最近」;json/txt 走 document: 形态,查 MediaStore.Files) ----

    @Test
    fun mediaDocIdParsed() {
        assertEquals("document" to 456L, mediaDocId("document:456"))
        assertEquals("image" to 1L, mediaDocId("image:1"))
        assertNull(mediaDocId("document:abc"))
        assertNull(mediaDocId("nodocid"))
    }

    // ---- `msf:` 第二段(MediaStore.Files)采纳前的同名校验:不过就复制,绝不能直引到别的文件 ----

    @Test
    fun sameFileNameGuardsWrongId() {
        assertTrue(sameFileName("$root/Download/tvbox.json", "tvbox.json"))
        assertTrue(sameFileName("$root/Download/tvbox.json", "Download/tvbox.json"))
        assertFalse(sameFileName("$root/Download/other.json", "tvbox.json"))
        assertFalse(sameFileName(null, "tvbox.json"))
        assertFalse(sameFileName("$root/Download/tvbox.json", null))
    }

    // ---- `msf:` 第三段(兜底):按显示名猜 Download 目录下的路径,非法名一律拒绝 ----

    @Test
    fun downloadGuessPathOnlyAcceptsPlainNames() {
        assertEquals("$root/Download/tvbox.json", downloadGuessPath(root, "tvbox.json"))
        // 带目录的显示名只取 basename,不会指到 Download 之外
        assertEquals("$root/Download/tvbox.json", downloadGuessPath(root, "Download/tvbox.json"))
        assertEquals("$root/Download/tvbox.json", downloadGuessPath(root, "  tvbox.json  "))
        assertNull(downloadGuessPath(root, ""))
        assertNull(downloadGuessPath(root, "."))
        assertNull(downloadGuessPath(root, ".."))
    }
}
