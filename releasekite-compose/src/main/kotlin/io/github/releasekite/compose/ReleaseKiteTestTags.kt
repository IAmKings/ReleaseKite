package io.github.releasekite.compose

/**
 * UI 测试定位标记。
 *
 * 契约（docs/API_CONTRACT.md §3）要求以下 testTag 稳定存在，宿主自动化/截图走查依赖它们；
 * 末尾几条是契约之外为「设置区」补充的定位标记（不影响契约，纯增量）。
 */
internal object ReleaseKiteTestTags {
    const val DIALOG = "releasekite_update_dialog"
    const val UPDATE_NOW = "releasekite_update_now"
    const val LATER = "releasekite_update_later"
    const val RETRY = "releasekite_update_retry"
    const val SETTINGS = "releasekite_update_settings"
    const val ROW = "releasekite_update_row"

    /** 补充标记：设置区开关与重置按钮（契约未要求，便于测试与宿主自动化）。 */
    const val SETTINGS_AUTO_SWITCH = "releasekite_settings_auto_switch"
    const val SETTINGS_BETA_SWITCH = "releasekite_settings_beta_switch"
    const val SETTINGS_RESET_ROUTES = "releasekite_settings_reset_routes"

    /** 补充标记：更新说明滚动区（断言占位文案/滚动容器）。 */
    const val NOTES = "releasekite_update_notes"

    /** 补充标记：「查看完整说明」入口（验证 LocalUriHandler 跳转接线）。 */
    const val VIEW_FULL = "releasekite_update_view_full"
}
