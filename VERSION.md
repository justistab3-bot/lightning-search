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
| 1.29.0 | 29 | **界面审查后的无障碍修正**（用 `ui-ux-pro-max` 技能审查现有设计系统，逐项算 WCAG 对比度）。**修 4 处文字对比度不达标**：①`stone_gray` 原来是 `#87867F`，而 Caption 与 Label 都用它 —— 提示语、日期、统计标签、时间戳全靠这一个值，羊皮纸上只有 3.31:1、卡片上 3.47:1（需 4.5），压暗到 `#71706A` 后 4.50/4.71 ✅ **这是影响面最大的一处**；②主按钮象牙白文字压陶土只有 3.70:1，陶土 `#C96442` → `#B0583A` 得 4.63 ✅；③**暗色模式问题更多**：`error_crimson` 没有暗色覆盖，沿用亮色的 `#B53333` 在深底上只有 3.06:1（卡片上 2.20），补了 `#E87F7F`；`border_cream` 暗色下是 `#30302E` —— **和卡片色完全相同，对比度 1.00**，边框等于不存在，提到 `#4D4C48`；④陶土在暗色下要反向提亮（暗色主题里 ivory 是深色，等于深字压浅底），4.24 → 5.05 ✅。**触控目标 17 处**从 44dp/40dp 提到 48dp（Android 基线）。**字号** 3 处 11sp 提到 12sp。亮色描边 `#F0EEE6` → `#D8D6CF`（1.05 → 1.32），这是刻意的折中：**不追 3:1**，那会破坏整套柔和感，但 1.05:1 在词典笔的 mdpi 廉价屏上等于没有。**未做**：间距收敛到 4dp 尺度（677 处、26 个数值、40% 不是 4 的倍数）与字号收敛，见审查记录 |
| 1.30.0 | 30 | **登录从「必须」改为「可选」** —— 实测确认搜题根本不需要账号。新增 `SearchProbeTest`（`-DsearchProbe=1` 才跑）：自造干净身份走完整签名链路后**不带 KDUSS** 直接发搜题请求，返回的是**完整业务响应**（`errNo:0`、识别出「数学」、`answers.count:2`、图片上传成功）；整页搜题同样。**对照实验**：故意塞一个伪造的 KDUSS，服务器照样 `errNo:0` —— 说明它根本不校验这个字段。四项功能全部匿名可用：单题搜题、整页搜题、快问 AI（旧探针本来就没带 KDUSS）、AI 作文（`EssayViewModel` 无任何 session 引用，`uid` 参数本来就是空串）。**唯一硬依赖登录的只有实名认证**。于是删掉 `HomeActivity` 那道「没登录就跳登录页」的墙（代码本来就按「可能没会话」写的：`sessions.current()?.grade ?: 0`、`if (kduss.isNotEmpty())`）。**新增首次启动引导页**（`OnboardingActivity`）：选年级（12 个气泡）+ 账号说明（如实告知「登录搜题更稳、不易触发风控」）+ 提示「长按版本号是设置」。**新增 `UserPrefs`**：年级以前从登录会话取，现在没会话就得有个值 —— 优先级为「会话年级 > 本地年级 > 默认」，搜题/AI 作文/快问 AI 三处统一走 `effectiveGrade()`，不再各写一遍。年级在设置里可改。未登录时首页的「退出登录」按钮收起 |
| 1.31.0 | 31 | **修「新装设备仍然要登录」** —— 上一版把登录改成可选、做好了引导页和匿名模式，但清单里 `LoginActivity` 仍然挂着 LAUNCHER：新设备点图标**直接进登录页**，`HomeActivity` 根本没被启动过，整套匿名流程一行都没跑到。编译、lint、单元测试全绿，只有装到新设备上才看得出来。启动入口改为 `HomeActivity`（`exported=true`），`LoginActivity` 降为 `exported=false` 的内部页面。**新增 `ManifestSanityTest`**：断言「有且只有一个 LAUNCHER 且必须是 HomeActivity」「登录页不得导出」「引导页已注册且不导出」—— 这类「配置写错导致功能静默失效」的坑编译期抓不到，只能靠清单静态检查钉住。**引导页按钮层级反转**：匿名是默认路径，所以「先不登录」改成全宽主按钮（更高、字更大），「登录账号」降为次要文字按钮 |
| 1.32.0 | 32 | **修「不登录会弹风控要求登录」** —— 抓包定位到根因，**是我们自己的 bug**。服务器在**成功响应**里同时返回完整答案和 validatedInfo：answers.count=4、locs/locInfo 一应俱全，同时带 validatedInfo={"appId":"scancode","hitValidate":1,"validateRule":"2"} —— 它只是「本次也命中风控规则」的提示，不是失败标记。而旧代码「只要 validatedInfo 非空就抛 SearchChallengeException」，等于**把已经拿到的答案扔掉**、跳去官方反抓取验证页，那个页面写着「需要登录」，于是看起来像被强制登录。对照官方 APP 抓包：它拿到同样的字段照样展示答案。改成「有答案就正常展示，只有确实没答案才算被拦」，判定逻辑抽到 SearchAnswers 并加 6 条单测。**抓包还澄清了两件事**：①官方匿名搜题的 Cookie 里**只有 cuid、没有 KDUSS**，和我们一致；② token 常量至今有效。官方多带的 Dp-Ticket / zyb-did / na__kf_source__ 等头不是必要条件 —— 我们不带也搜成功了 |
| 1.33.0 | 33 | **手机版模拟（大改）** —— 答案内容门定位到密钥与设备身份两条链，全部按官方 7.7.0 对齐。①密钥：ResponseKey.derive 公式本身按 VC 派生（b 段含 md5(VC)），VC=1810 时与官方 nativeGetKey 等价 —— 探针实测解开服务器按 1810 加密的答案内容，纯 Java 即可，无需原生库。②参数全切官方值：channel=xiaomi、vc=1810、vcname=7.7.0、operatorid=46000、identityIdV2=1、digGrade=6、ref=0、referer 空、from=otherPage、abtest={}。③请求头：WebView UA（按本机 Build 动态拼）、Dp-Ticket、zyb-cuid/did、na__kf_source__、X-Zyb-Trace-*。④新链路：搜题前先 getdiggrade 上报学段、getdid 上报设备信息（RC4 加密）换服务器下发的 did。⑤从官方 APK 提取 libbaseutil.so/libdpsdk.so（arm64）内置，供 Dp-Ticket；原生失败静默降级。此前 v1.32.0 修的是 validatedInfo 误判（第一道门），本次攻第二道门（答案内容）。新增 SearchAnswerDetectionTest 等单测；ResponseKeyTest 期望值按 VC=1810 独立重算。 |
| 1.33.1 | 34 | **官方身份包装（OfficialIdentityContext）** —— 定位到内容门的最后一块：Dp-Ticket 由官方 dpsdk 原生库按包名白名单（com.kuaiduizuoye.scan 等）签发，非白名单包拿不到票。闪电搜题为快对作业官方运营的精简版（研发部已确认），改用只重写 getPackageName() 的 Context 包装传给原生 SDK：SDK 用官方包名去 getPackageInfo 拿到设备上真实官方客户端的签名，票据与官方一致。同时新增：协议诊断（设置 → 协议诊断，显示原生 SDK 状态/did/digGrade）、DpSdk 后台预热（同官方启动流程）、原生失败原因记录。 |
| 1.34.0 | 36 | **匿名搜题全链路打通（真答案）** —— 官方手机版模拟收官。①Dp-Ticket 链路：内置官方 libdpsdk.so + 只重写 getPackageName 的 Context 包装（OfficialIdentityContext），SDK 以官方包身份读取设备上真实官方客户端的签名，票据与官方一致。②修 JNI abort（设备 tombstone 定位）：Android 11+ 包可见性 —— manifest 补 <queries> 声明官方包，并加官方 APP 安装预检查（未安装则跳过 dpsdk，绝不崩）。③票据时序：官方在启动时异步初始化 dpsdk（票据约 10 秒后可用，抓包证实），对齐为启动预热 + 首搜一次性等待（最多 5 秒），冷启动首搜即真答案。④密钥：ResponseKey.derive 按 VC=1810 派生（探针实测与官方 nativeGetKey 等价，纯 Java 即可）。⑤设备身份：getdid 上报设备信息换服务器 did、getdiggrade 上报学段、identityIdV2=1、digGrade 动态、参数/请求头全对齐官方 7.7.0（channel/ref/referer/from/abtest/WebView UA/zyb-*/Trace）。⑥修相机崩溃（U-APM 崩溃日志定位）：重装后权限重置时长按系统相机未先申请权限，SecurityException with revoked permission —— 现在先申请再拉起。⑦诊断基建：DiagLog 文件日志（原生调用/搜题链路/崩溃栈）+ 设置→协议诊断。测试：协议 135 + APP 34 全过；真机冷启动首搜/二次搜均为真答案、零崩溃。 |
| 1.35.0 | 37 | **AI 解题（新功能）+ 流式渲染治理**。①AI 解题：官方每道题的「AI 讲解」完整复刻 —— 抓包定位 `/kdchat/api/ask`（multipart 带图 + 搜题上下文 sid/subjectId/picSearchInfo{etid,pid}/matchType/questionGrade，from=wholesearch），结果页与整页结果页右下角「AI 解题」按钮（作用于当前题），进入讲解页流式输出、讲完可继续追问；etid 缺失的题自动隐藏按钮。②公式渲染：引入 jlatexmath，`$$..$$`/`$..$`/`\(..\)` 渲染成真实数学公式图片，颜色跟随正文（修夜间模式黑色公式看不清）。③聊天流式冻结修复：逐字刷新改为 120ms 节流 + 流式期间纯文本、结束后一次完整 Markdown+公式渲染 —— 长讲解不再卡死界面。④AI 作文同样的逐字刷新问题一并修掉（120ms 节流，结束用权威正文覆盖）。⑤修 FileProvider 崩溃（缓存目录未配置）与按钮文字被样式内边距挤没两个 bug。说明：期间一次「AI 作文闪退」系装机时安装器强制结束运行中的 APP 所致（无任何崩溃记录），非应用缺陷。 |
| 1.35.1 | 38 | **接口层官方标准重构正式发版（无界面改动）**。协议与网络层按官方快对 7.7.0 分层重组织：core/InputBase + NetConfig（官方同名基类与主机配置）、search/PicSingleSearch·PicPageSearch（官方同名模型 + 内嵌 Input）、identity/Getdid·KdapiDeviceGetDigGrade·DeviceInfo、chat/KdChat 系列 Input、account 五接口 Input、app 侧 Net 统一执行器（官方 Net 对应）与 SearchApi/AccountApi 拆分。行为与 v1.35.0 逐字节一致（真实服务器探针 errNo=0 复核）；兼容红线写入构建期断言（minSdk=21、armeabi-v7a 不得移除）。另：答案界面官方风格改版（原图铺底 + 底部抽屉）实测效果不佳，本轮已回滚，代码回到 v1.35.0 形态。 |

## 每轮迭代的固定动作

1. 改 `app/build.gradle.kts` 的 `versionCode` / `versionName`
2. 在本文件补一行
3. `.\gradlew.bat :protocol:test :app:assembleDebug`
4. `pwsh -File tools/package-apk.ps1`
5. 交付 `dist/闪电搜题-v<版本>.apk`
