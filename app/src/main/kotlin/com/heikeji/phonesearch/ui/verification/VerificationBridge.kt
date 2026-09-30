package com.heikeji.phonesearch.ui.verification

import org.json.JSONObject
import java.util.Locale

/**
 * 验证页的 JS Bridge 注入脚本（原 Q.f.e 的逐字移植）。
 *
 * 官方页面通过 `window.ZYBJSBridge.postMessage(...)` 或 `window.HybridSdkCommon.iframeHybrid(...)`
 * 与客户端通信，实际落到 `window.prompt(bridgeSecret, json)`；原生侧用
 * `window.__watchVerificationReply({code, data, callbackKey})` 回复。
 *
 * 同时把 viewport 改成固定 360 宽度并按 WebView 宽度做初始缩放，保证手机竖屏下布局正确。
 */
object VerificationBridge {

    fun inject(html: String, widthDp: Int, bridgeSecret: String): String {
        require(html.lowercase(Locale.ROOT).contains("<head")) { "验证页面格式无法识别" }

        val scale = minOf(1.0f, maxOf(120, widthDp) / 360.0f)
        val withViewport = html.replace(
            Regex("(?i)<meta\\s+name=[\"']viewport[\"'][^>]*>"),
            "<meta name=\"viewport\" content=\"width=360,initial-scale=$scale," +
                "minimum-scale=$scale,maximum-scale=3,user-scalable=yes\">",
        )

        val script = buildString {
            append("<script>(function(){var key=")
            append(JSONObject.quote(bridgeSecret))
            append(
                ",legacy={},sequence=0,sdk;" +
                    "window.ZYBJSBridge={postMessage:function(message){window.prompt(key,String(message));}};" +
                    "function iframeHybrid(action,param,callback){" +
                    "if(typeof param==='function'){callback=param;param={};}" +
                    "var id='watch_legacy_'+(++sequence);" +
                    "if(typeof callback==='function')legacy[id]=callback;" +
                    "window.ZYBJSBridge.postMessage(JSON.stringify({action:action,param:param||{},callbackKey:id}));}" +
                    "Object.defineProperty(window,'HybridSdkCommon',{configurable:true," +
                    "get:function(){return sdk;}," +
                    "set:function(value){sdk=value;" +
                    "if(value&&typeof value.iframeHybrid==='function')value.iframeHybrid=iframeHybrid;}});" +
                    "window.__watchVerificationReply=function(reply){" +
                    "var callback=legacy[reply.callbackKey];" +
                    "if(callback){delete legacy[reply.callbackKey];" +
                    "callback(reply.code===200?reply.data:{errNo:reply.code,errStr:'验证操作未完成'},reply.callbackKey);" +
                    "if(typeof callback.remove==='function')callback.remove(reply.callbackKey);}" +
                    "else if(window.__jsBridge)window.__jsBridge.callback(reply);};})();</script>",
            )
        }

        val headEnd = withViewport.lowercase(Locale.ROOT).indexOf("<head").let { headStart ->
            withViewport.indexOf('>', headStart) + 1
        }
        return withViewport.substring(0, headEnd) + script + withViewport.substring(headEnd)
    }

    /** 生成回复脚本（由原生侧 evaluateJavascript 执行）。 */
    fun replyScript(callbackKey: String, data: JSONObject, code: Int): String {
        val payload = JSONObject()
            .put("code", code)
            .put("data", data)
            .put("callbackKey", callbackKey)
        return "if(typeof window.__watchVerificationReply==='function')" +
            "{window.__watchVerificationReply($payload);}"
    }
}
