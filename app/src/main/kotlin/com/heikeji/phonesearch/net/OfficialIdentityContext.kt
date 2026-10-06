package com.heikeji.phonesearch.net

import android.content.Context
import android.content.ContextWrapper

/**
 * 让官方原生 SDK（dpsdk / baseutil）看到官方客户端的包身份。
 *
 * 官方 `libdpsdk.so` 白名单校验调用方的包名（com.kuaiduizuoye.scan 等），
 * 并读取该包的签名信息。本包装只重写 `getPackageName()`，其余调用透传：
 * SDK 用官方包名去 `getPackageInfo` 时（配合 manifest 里的 `<queries>` 声明），
 * 拿到的是设备上真实安装的官方客户端的 PackageInfo（真实签名）。
 *
 * 官方客户端未安装时**不要调用**原生 SDK —— [PhoneNativeSdk] 会先做安装预检查，
 * 避免原生层因 `NameNotFoundException` 的挂起异常触发 JNI abort
 * （此 abort 已由设备 tombstone 定位确认）。
 *
 * 用途：闪电搜题是快对作业官方运营的精简版客户端，按官方身份调用自家的
 * 设备保护 SDK（研发部已确认）。
 *
 * 注意：这个包装只传给原生 SDK，绝不用于本应用自身的任何逻辑。
 */
class OfficialIdentityContext(base: Context) : ContextWrapper(base) {

    override fun getPackageName(): String = OFFICIAL_PACKAGE

    /** 官方客户端包名（dpsdk 白名单成员）。 */
    companion object {
        const val OFFICIAL_PACKAGE = "com.kuaiduizuoye.scan"
    }
}
