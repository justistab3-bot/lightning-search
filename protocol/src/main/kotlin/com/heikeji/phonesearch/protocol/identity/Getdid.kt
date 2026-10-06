package com.heikeji.phonesearch.protocol.identity

import com.heikeji.phonesearch.protocol.core.InputBase
import com.heikeji.phonesearch.protocol.core.NetConfig

/**
 * 设备 ID 上报接口，对应官方 `com.baidu.homework.common.net.model.v1.Getdid`。
 *
 * 上报负载由 [DeviceInfo] 构造（RC4 加密的设备信息），服务器下发 did，
 * 之后 did 进入公共参数与 `zyb-did` 请求头。
 */
object Getdid {

    /** 官方 `Getdid.Input`。 */
    class Input private constructor(private val param: String) : InputBase() {

        override val modelClass: Class<*> = Getdid::class.java
        override val url: String = URL

        /** 官方 `__pid = "resource"`：走资源主机。 */
        override val pid: String = NetConfig.PID_RESOURCE
        override val method: Int = METHOD_POST

        override fun params(): Map<String, Any?> = linkedMapOf("param" to param)

        companion object {
            /** 官方 `Input.URL`。 */
            const val URL = "/userident/user/getdid"

            /** 官方 `Input.buildInput`。 */
            fun buildInput(param: String): Input = Input(param)
        }
    }
}
