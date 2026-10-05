package com.heikeji.phonesearch.update

/**
 * 更新源的配置。
 *
 * 走 Gitee 而不是 GitHub：Gitee 的 release 附件对公开仓库是**匿名直链**，
 * 应用不需要内置任何令牌就能检查与下载（内置令牌等于把令牌发到每个 APK 里）。
 */
object UpdateConfig {

    const val OWNER = "tab3"
    const val REPO = "lightning-search"

    private const val API_BASE = "https://gitee.com/api/v5/repos/$OWNER/$REPO"

    /** 最新 release；公开仓库无需令牌。 */
    const val LATEST_RELEASE_URL = "$API_BASE/releases/latest"

    /** release 列表页，仅在自动检查失败时作为兜底提示用。 */
    const val RELEASES_PAGE = "https://gitee.com/$OWNER/$REPO/releases"

    const val CONNECT_TIMEOUT_MS = 12_000
    const val READ_TIMEOUT_MS = 20_000

    /** release JSON 的响应上限。 */
    const val MAX_METADATA_BYTES = 512 * 1024

    /** APK 下载上限，防止被塞一个超大文件。 */
    const val MAX_APK_BYTES = 200L * 1024 * 1024

    /** 下载缓冲。 */
    const val BUFFER_BYTES = 32 * 1024
}
