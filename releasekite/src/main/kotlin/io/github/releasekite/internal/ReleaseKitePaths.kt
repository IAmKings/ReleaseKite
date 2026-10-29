package io.github.releasekite.internal

import android.content.Context
import java.io.File

/**
 * ReleaseKite 运行期路径约定（契约 §2）。
 *
 * 下载目录固定在 `cacheDir/releasekite/updates`：
 * - 与 `res/xml/releasekite_file_paths.xml` 的 FileProvider 暴露目录一一对应；
 * - 放在 cacheDir 下，系统空间紧张时可整体回收，不污染宿主 filesDir。
 */
object ReleaseKitePaths {

    /** 相对 `cacheDir` 的下载目录；`.part` 半包与最终 APK 都在这里。 */
    const val UPDATES_DIR = "releasekite/updates"

    /** 下载目录（不自动创建，下载器/Worker 落盘前会 mkdirs）。 */
    fun updatesDir(context: Context): File =
        File(context.applicationContext.cacheDir, UPDATES_DIR)
}
