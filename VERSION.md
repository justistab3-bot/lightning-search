# 版本记录

> **原项目：腕上搜题 · 原作者：yijia**
> 本仓库是其手机端衍生实现，协议设计与业务逻辑的著作权归原作者所有。详见 [README](README.md) 顶部声明。

包名固定为 `com.heikeji.phonesearch`，`versionCode` 每轮 +1，归档产物在 `dist/`。

归档命令：

```powershell
pwsh -File tools/package-apk.ps1
```

应用内首页底部会显示当前版本号，装到机器上后可以直接确认是哪一轮。

| 版本 | versionCode | 内容 |
|---|---|---|
| 1.0.0 | 1 | 协议全链路打通（登录 / 搜题 / 验证码挑战），应用内相机 + 裁剪，结果页竖屏「上图下答案、左右滑动切答案」 |
| 1.3.0 | 3 | 视觉全面重做：Claude 设计语言（暖羊皮纸底、陶土色、衬线标题）、首页时段问候 + 入场动效、学科徽标、答案页圆角卡片、横屏三套布局、相机方向修复 |
| 1.4.0 | 4 | LaTeX 公式渲染（纯 Kotlin 子集渲染器，答案页仍禁用 JS）、学科实体图标（物理=锥形瓶等）、首个结果标「最佳匹配」、答案页竖向滚动不再被翻页抢走、相机预览翻转开关 |
| 1.5.0 | 5 | 相机预览 4 方向可调并同步到成片、相机横屏界面重做、答案序号圆圈、竖屏滚动自动收起原图、横屏答案区全屏 |
| 1.6.0 | 6 | 应用图标（从 1024 原图生成五档 + 自适应图标）、序号改成正圆（自绘强制正方形）、自动收起改走 `onScrollChanged`、相机默认旋转 180°、去掉前后置切换 |
| 1.7.0 | 7 | 答案图片点击看大图（锚点方案，无 JS）、抖动修复（滚动抑制窗口）、横屏序号行随阅读模式收起、横屏快门移到右侧、各处 emoji |
| 1.8.0 | 8 | 竖屏滚动时原图整块隐藏、横屏全屏时顶栏一并收起（浮动退出按钮）、版本号显示与归档 |
| 1.9.0 | 9 | 同步「腕上搜题」1.1.1：新增**整页搜题**（`/pagesearch`）、题块与候选答案两级切换、原图缩放平移框选、EXIF 逆映射区域解码裁剪、`pageExtraInfo{sid,index,loc}` 框选精搜、完整 `SearchTask` 与 generation 校验、会话 compare-and-clear、首次使用说明 |
| 1.10.0 | 10 | 答案页开启 JavaScript 并内置本地 **KaTeX**（`WebViewAssetLoader` + CSP 锁死脚本来源），Kotlin 渲染器降级为兜底；新增**断网全屏提示页**（自动弹出、网络恢复自动关闭） |
| 1.11.0 | 11 | 长按「拍照搜题」直接调用系统相机（全分辨率，FileProvider 授权）；修复应用内相机选整页时误走单题裁剪流程的 bug |
| 1.12.0 | 12 | 应用内检查更新：从 Gitee release 接口查最新版本、直链下载 APK、调系统安装器（全程不跳浏览器）；新增 7 个版本比较单测 |
| 1.13.0 | 13 | **AI 作文**（新功能）：接入 `api.kuaiduizuoye.com/aiwriting` 系列接口，支持**快速写作**与**英语作文**；SSE 流式解析 + 打字机效果；新增 `SseParser` / 事件解析 / 请求构造（18 个测试，用真实抓包做样本）。提纲与导图流程的端点已探明，但 `createThought` 仍返回 `5324`，待抓包补齐参数后开放 |
| 1.14.0 | 14 | AI 作文修复：正文首段的 markdown 标题（`# xxx`）抽出来单独渲染，不再露出 `#`；切换模式/语言/字数时清空上一轮结果；状态栏显示「实际 X 字 / 目标 Y」；字数不足目标 85% 时自动重写一次并保留更长的那篇；把年级折进 `describe`（实测服务端不读 `gradeId`） |
| 1.15.0 | 15 | AI 作文：新增**手动选择文体**（自动/记叙文/议论文/说明文/书信/散文/小说/诗歌），走 `describe` 生效（实测改 `queryType` 只回显不生效）；正文**字号可调**（A−/A+，12–28sp，记忆上次选择）；中英切换时清空标题输入；新增 10 个请求构造测试 |
| ~~1.16.0~~ | ~~16~~ | **已回滚**。曾加「查看整本答案」：整页结果底部入口 + 教辅答案扫描图翻页浏览 |
| ~~1.17.0~~ | ~~17~~ | **已回滚**。曾试图修入口不出现：把教材信息提取挪到解码后的单题答案层 |
| ~~1.18.0~~ | ~~18~~ | **已回滚**。曾试图修入口误报与错误不可见 |
| 1.19.0 | 19 | **回滚「查看整本答案」**，代码回到 1.15.0 状态。原因：入口判定依赖的教材信息在服务端字段位置不稳定 —— 先是在整页响应里找不到（原生模型 `PicPageSearch` 无此字段），改到单题答案层后又变成每道题都误报；即便拿到 id，`/search/submit/booksearch` 仍取不到答案页（`ticket`/`randStr` 两个验证码字段是否必需无法离线确认）。三个 commit 保留在 git 历史里，将来要捡回可 `git revert` |
| 1.20.0 | 20 | **快问 AI**（新功能）：接入 `/kdchat` 系列接口，文字多轮对话 + **深度思考**（思考过程可折叠、显示耗时）+ **联网搜索** + 推荐问题 + 停止生成 + Markdown 渲染。SSE 协议用新加的**签名探针**实测取得（ProxyPin 不缓冲 SSE）。**修存储泄漏**：更新用的安装包从来不删，每版留一个 12 MB 的 APK 在 `files/updates/`，装过七八版就上百兆（用户实测 98 MB）——现在启动时自动回收，只留最新一个 |
| 1.21.0 | 21 | 更新链路加固：`/releases/latest` 若拿不到 apk 附件（上传失败、或只是说明用的 release），旧逻辑直接返回 null，用户会**永远卡在「已是最新」**——现在退到 release 列表挑「版本号最高且带 apk」的那个。同时从 Gitee 删掉已回滚的 v1.16.0 / v1.17.0 / v1.18.0 三个坏版本，避免有人装到带撤销功能的包。新增 10 个更新解析测试 |
| 1.22.0 | 22 | **修横屏闪退**：`layout-land/activity_essay.xml` 的答案区把两个 TextView 直接塞进 `ScrollView`，而它只能有一个直接子 View —— inflate 时抛 `IllegalStateException`，**一横屏打开 AI 作文就崩**。新增 `LayoutSanityTest` 静态检查布局（含 `NestedScrollView`、且不遗漏根元素），并校验竖屏/横屏 id 集合一致。**快问 AI 补横屏布局**：开关与输入压成一行。更新解析测试补上真实的 `org.json`（`testImplementation`，不进 APK） |
| 1.23.0 | 23 | **快问 AI 支持图片**：输入框左侧加选图按钮，图片压到最长边 1280 / 质量 80 后走 `/kdchat/photo/ask`（multipart + `imageInfo{picMD5}` + `toolType=image`）。`pagesearchInfo` 在 H5 里是**可选**的（`a.pagesearchInfo && ...`），所以不发也能用。**主页横屏不再滚动**（根节点由 `NestedScrollView` 换成普通 `LinearLayout`，两栏重新分配高度）。**最近搜题默认收起**，点标题展开，展开状态记忆。**体积优化**：历史记录增加 40MB 总量上限（只限条数挡不住大题的整份 HTML），WebView 答案图缓存超 32MB 自动清；长按版本号可看存储占用并手动清理 |
| 1.24.0 | 24 | **修「竖屏转横屏闪退」**：`activity_home.xml` 的 `root` 竖屏是 `NestedScrollView`、横屏是 `LinearLayout`，`historyBody` 竖屏是 `LinearLayout`、横屏是 `ScrollView` —— ViewBinding 只按一份生成字段类型，转到另一方向就 `ClassCastException`。`LayoutSanityTest` 新增「同 id 必须是同一种 View」规则，**这条规则正是靠它抓出上面两处的**。**横屏首页按横屏重新设计**（目标 16:9 宽屏，横向宽、高度只有 ~390dp）：三块功能并排居中（拍照搜题权重 4、AI 作文 2.2、快问 AI 2.2），两侧留白把内容挤到中间，不再左重右空；头部压成一行、底部一行放统计与退出；横屏历史预览从 6 条减到 3 条 |
| 1.25.0 | 25 | **修横屏最近搜题滚不动**：`historyBody` 在横屏是 `wrap_content`，列表一长就撑到屏幕外，而根节点不可滚动 → 看着就是「滚不动」。现在展开时把 `historySection` 设为 `0dp + weight 1` 吃掉剩余高度，列表在**内部**滚动；收起时回到 `wrap_content` 不留空档。**修字体不平衡**：`dateLabel`（时间）原来用 `Label`（12sp）比 `tagline`（提示语，14sp）小一档，两边都统一到 `Body`；历史条目的时间也从 12sp 提到 13sp。**作文横屏加全屏按钮**：点一下收起配置栏，正文吃满整个版面（竖屏同样可用，收起配置卡片） |
| 1.26.0 | 26 | **最低版本降到 Android 5.0（minSdk 21）**，为词典笔这类老设备。代价是整套 AndroidX 退回 2024 年版本（2025 年的版本集体把最低要求提到 23），依赖清单里注明了原因。开了 **core library desugaring**（`:protocol` 用 `java.util.Base64`，Android 5.0 上没这个类）。逐条修了 lint 报出的 17 处 API 越界：`Process.is64Bit`/`getActiveNetwork`/`getSystemService(Class)`/`registerDefaultNetworkCallback`/`getContentLengthLong`/`InputStream.nullInputStream`（全部按版本分支或换等价写法）；`KeyGenParameterSpec` 在 5.0/5.1 不可用，会话加密降级为**设备标识派生密钥的软件 AES**（弱于硬件密钥库，但比明文好，代码里有说明）；主题里的 `windowLightStatusBar` 等标记 `tools:targetApi`。另外在启动时**显式打开 TLS 1.2**（5.x 的 `HttpsURLConnection` 默认协议列表不一定包含它，否则所有 https 请求都会失败且报错难懂）。`lintDebug` 纳入常规构建 |
| 1.27.0 | 27 | **接入友盟+ 统计与崩溃分析**（U-App + U-APM），并配齐合规链路：①**隐私政策全文**（内容与代码真实行为逐条对齐，含第三方 SDK 披露）②**首次启动同意弹窗**，不同意则退出 ③**同意之前绝不初始化统计 SDK** —— `preInit()` 不采集可以在 `Application.onCreate` 无条件调，`init()`/`UMCrash.init()` 只在同意且开关打开时执行 ④**设置里可关闭匿名统计**（长按版本号），关闭时调 `submitPolicyGrantResult(false)`。AppKey 从 `local.properties` 注入而非写死（开源项目里写死会把 fork 的数据打进同一账号）。**移除了友盟自动合并进来的 `AD_ID` 广告标识符权限**（搜题工具没有广告场景，且与隐私政策声明冲突）。接入前验证过：AAR 未声明 minSdk（不打回 23）、原生库含 `armeabi-v7a`（32 位设备可跑）、构件在阿里云镜像上 |
| 1.28.0 | 28 | **适配词典笔的超窄横屏**（实测 P600PRO：`sw254dp-w960dp-h254dp-normal-long-land`，即 960×254dp、Android 5.1）。用 ADB dump 视图树定位到：首页主操作行只剩 112dp，拍照按钮被压到 20dp、两条提示文字直接 0dp；整页结果页**根本没有横屏版**，用竖屏那份导致 56dp 标题栏 + 180dp 原图 = 236dp，答案区只剩十几 dp。**关键坑**：Android 的 `w`/`h`/`sw` 限定符**全是「最小值」，没有「最大高度」**，所以没法把矮屏单独圈出来（`small` 也匹配不上，设备是 `normal`）。改成官方推荐的「先保证最小尺寸可用，再向上增强」：`layout-land` 换成紧凑版（横向铺开，任何高度都不溢出），原宽敞版移到 `layout-h340dp-land`（可用高度 ≥340dp 的手机/平板横屏）；新增 `layout-land/activity_page_result.xml`（题图移到左栏）。`LayoutSanityTest` 的变体目录改为**动态枚举**并新增覆盖检查，以后加尺寸变体不会漏检 |
| 1.29.0 | 29 | 待定 |

## 每轮迭代的固定动作

1. 改 `app/build.gradle.kts` 的 `versionCode` / `versionName`
2. 在本文件补一行
3. `.\gradlew.bat :protocol:test :app:assembleDebug`
4. `pwsh -File tools/package-apk.ps1`
5. 交付 `dist/闪电搜题-v<版本>.apk`
