package io.github.releasekite.sample

import android.app.Application
import io.github.releasekite.ReleaseKite

/**
 * 示例应用入口：在 `Application.onCreate` 中完成 ReleaseKite 的一次性初始化。
 *
 * 初始化顺序要求：`ReleaseKite.initialize(...)` 必须先于任何 `ReleaseKite.manager` /
 * `ReleaseKiteHost` 的使用（见 docs/INTEGRATION.md「常见问题」）。
 */
class SampleApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        ReleaseKite.initialize(
            context = this,
            config = DemoSettings.config(),
        )
    }
}
