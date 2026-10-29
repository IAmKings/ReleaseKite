package io.github.releasekite.internal

import androidx.core.content.FileProvider

/**
 * ReleaseKite 专用 FileProvider（唯一 provider 类）。
 *
 * 为什么必须自己建子类：AndroidManifest 合并以 `<provider android:name>` 为键。
 * 若库清单直接声明 `androidx.core.content.FileProvider`，宿主一旦也声明自己的
 * FileProvider（相机/分享/图片选择等场景非常常见），manifest merger 会报
 * `Attribute provider#androidx.core.content.FileProvider@authorities ... is also present`
 * 直接导致宿主构建失败（T6 审查 P1-1）。
 *
 * 改用本类后：
 * - provider 的 name 与宿主互不相干，merger 不再冲突；
 * - authority 仍是 `"${packageName}.releasekite.updates"`，与
 *   [AndroidUpdateInstaller] 的 `FileProvider.getUriForFile(context, authority, apk)`
 *   静态调用完全兼容（按 authority 解析，不依赖具体类名）；
 * - 暴露路径仍只限 `cacheDir/releasekite/updates`（见 `res/xml/releasekite_file_paths.xml`）。
 */
class ReleaseKiteFileProvider : FileProvider()
