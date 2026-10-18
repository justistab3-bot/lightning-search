<div align="center">

<h1>⚠️ 原项目与原作者声明</h1>

<h2>原项目：<strong>腕上搜题</strong></h2>

<h2>原作者：<strong>yijia</strong></h2>

<h3>本仓库是「腕上搜题」的<strong>手机端衍生实现</strong>。</h3>

<p>
<strong>协议设计、接口定义、加密与签名方案，以及全部业务逻辑，均出自原作者 yijia 的「腕上搜题」。</strong><br>
<strong>本项目仅在其工作成果之上，做了 Android 手机端的重新实现与界面适配。</strong><br>
<strong>相关著作权与署名权归yijia 所有。</strong>
</p>

<p>
<strong>使用、分发或二次开发本项目时，请一并注明原作者 yijia 与原项目「腕上搜题」。</strong><br>
<strong>若原作者认为本仓库有任何不妥，请提 Issue，会立即处理（包括删除仓库）。</strong>
</p>

</div>

---

# 闪电搜题（手机版）

「腕上搜题」（原作者 **yijia**）的 Android 手机端衍生实现，使用 Kotlin 与原生 XML 布局。协议设计、加密与签名方案、业务逻辑的功劳归原作者所有。

[主仓库：Gitee](https://gitee.com/tab3/lightning-search) · [源码镜像：GitHub](https://github.com/justistab3-bot/lightning-search) · [版本记录](VERSION.md)

## 下载与兼容性

**当前版本：v1.36.2（versionCode 42）**

[下载 v1.36.2 APK](https://gitee.com/tab3/lightning-search/releases/download/v1.36.2/lightning-search-v1.36.2.apk) · [发行说明](https://gitee.com/tab3/lightning-search/releases/tag/v1.36.2) · [全部发行版](https://gitee.com/tab3/lightning-search/releases)

| 项目 | 说明 |
|---|---|
| 应用名 | 闪电搜题 |
| 包名 | `com.heikeji.phonesearch`，与原手表版隔离，可共存 |
| 系统 | Android 5.0 起，minSdk 21 / targetSdk 37 / compileSdk 37 |
| 架构 | 保留 `armeabi-v7a` 32 位支持，同时包含 `arm64-v8a`、`x86`、`x86_64` |
| 升级 | 本仓库发行包沿用既有签名链，可覆盖安装；账号存储格式与密钥沿用旧版 |

v1.36.2 APK 的 SHA-256：

```text
C39240057ED74DB69CAE4BEA4694D7AC7AAD1183294B3CDE2A0654CB24167F1D
```

## 主要功能

- **拍照搜题**：首页单击调用系统相机，支持单题和整页模式；长按保留 CameraX 应用内相机备用入口。
- **题目与解析**：左右滑动切换候选答案，支持数学公式、学科标识、原图展开收起和图片全屏缩放。
- **AI 作文 ✍️**：按年级、语言、文体和字数生成，支持提纲、正文、停止生成、复制与全屏阅读。
- **💬 快问 AI**：文字或图片提问、多轮对话、深度思考、联网搜索、推荐问题和停止生成。
- **最近搜题**：从首页打开历史面板，查看题目和统计；历史记录按需加载。
- **界面适配**：问候与鼓励语、深浅色主题、横竖屏及窄屏布局、断网提示、应用内更新。

## v1.36.2 的界面与动效

首页拍照按钮改用横排小相机图标和 52–72dp 紧凑高度，不再按剩余空间拉伸。问候语放大到 24–32sp，鼓励语放大到 14–17sp，较长文案可以完整换行。

主要按钮、图标按钮、模式/年级选项、题块和历史入口加入按下反馈与松手回弹。页面前进/返回、弹窗、原图展开收起、作文全屏与提纲显示也有短动效，答案翻页随手势轻微缩放淡入。

共享实现位于 `ui/common/UiMotion`：弹簧可以从当前值和速度继续运动，快速反复操作会取消过期淡出，控件离开窗口时清理动画。动效遵循系统关闭动画设置，不接管原有点击、长按或图片缩放手势。

快问 AI 只对新插入消息播放一次入场动效。流式文字保持稳定消息 ID 和增量更新；布局完成后按实际底部跟随，用户向上翻阅时保留阅读位置。

## 账号与升级

应用直接进入首页，AI 作文与快问 AI 不设置登录门槛。搜题按服务端实际返回处理：解密后若收到“请前往个人中心登录”的提醒，结果页显示“前往登录”按钮；用户登录成功后继续搜索原题，取消登录则保留原图与结果页。

当前实现已移除搜题设备保护 SDK、包名/签名身份适配、票据等待与相关网络探针。服务端登录提醒本身不会删除本地账号。

覆盖升级保留原有账号偏好文件、加密格式和 Keystore alias。临时读取失败保留原密文；新登录凭据保存成功后才替换会话；旧请求不能删除或覆盖新登录的账号。Android 5.0/5.1 的软件加密分支也保持原有派生方式。详见 [账号与升级衔接](docs/account-upgrade.md)。

自行构建的 APK 只有使用与已安装版本相同的签名，才能直接覆盖升级。

## 拍照、整页搜题与结果页

两种相机入口都遵循首页选择的模式，后续路由由 `ui/common/SearchEntry` 统一决定：

```text
单题：系统相机 / 应用内相机 → 裁剪与旋转 → 搜题 → 答案与解析
整页：系统相机 / 应用内相机 → 整页搜题 → 选择题块 / 框选 → 单题精搜
```

- 单题编辑预览采样到最长边 2048，上传最长边 1600、JPEG 质量 88。
- 整页上传最长边 2400、JPEG 质量 92；题块精搜从原始文件区域解码，最长边 1600、质量 92。
- 系统相机照片按 EXIF 处理方向。裁剪、定位与框选共用图像坐标变换，保留整页 `serviceIndex` 和 `pageExtraInfo` 关联。
- 整页里单个题块失败只影响该题块；定位信息不可用时仍展示答案。

竖屏结果页将原图与答案上下排列，答案可左右翻页、页内垂直滚动。原图可以整块收起，把空间让给答案；宽屏布局采用原图与答案左右分栏，并提供全屏阅读入口。

每页答案只使用一个 `AnswerWebView`。它在手势早期判断主方向：竖向交给 WebView，横向交给 ViewPager2，减少翻页与滚动冲突。图片查看器支持捏合缩放、双击放大与拖动。

## 公式排版

答案页使用随 APK 打包的 **本地 KaTeX**（`app/src/main/assets/math/`），同时保留纯 Kotlin 的 `protocol/render/LatexRenderer` 兜底排版。

| 情况 | 表现 |
|---|---|
| 本地 KaTeX 正常加载 | 从 `data-tex` 读取 LaTeX，渲染矩阵、分段公式与复杂上下标 |
| KaTeX 加载失败 | 保留 Kotlin 生成的 HTML/CSS 排版，不退化为整段原始 LaTeX |

Kotlin 渲染器支持分式、根式、上下标、希腊字母、常用运算符、文本及 `$...$`、`$$...$$`、`\(...\)`、`\[...\]` 定界符。无法识别的命令保留命令名，避免吞掉内容。

答案 WebView **开启 JavaScript 以运行本地 KaTeX**，并保留以下边界：

- HTML 在渲染前经过 jsoup 白名单清洗，剔除脚本、事件处理器和不可信标记。
- CSP 将脚本来源限定为 `https://appassets.androidplatform.net`，禁止网络连接、对象、框架与表单提交。
- 本地资源由 `WebViewAssetLoader` 以 HTTPS 提供；关闭 file/content access、DOM storage、多窗口和第三方 Cookie，不注册业务 JS Bridge。
- 图片链接交给原生查看器，其余导航由答案页拦截。验证码验证页使用独立 WebView。

## 设计与适配

当前使用“墨蓝学院”设计语言，配色与文字样式集中在资源文件中：

| 维度 | 当前实现 |
|---|---|
| 浅色页面 / 卡片 | 冷雾蓝灰 `#EEF1F8` / 白色 `#FFFFFF` |
| 浅色品牌色 | 靛蓝 `#3B4DB8`，用于主操作与选中态 |
| 深色页面 / 卡片 | 墨蓝 `#0F1220` / `#1A1F33`，品牌色提亮为 `#9DB0FF` |
| 文字 | 无衬线字体，标题加粗、正文常规；首页优先突出问候与鼓励语 |
| 层次 | 冷描边与浅投影，圆角资源为 12 / 16 / 22dp |
| 学科标识 | 根号、书本、Aa、原子轨道、锥形瓶、叶子、建筑、地球、天平等线稿 |

学科图标唯一数据源是 `protocol/render/SubjectIcons` 的 24×24 SVG path。原生 `SubjectIconView` 与答案页内联 SVG 共用定义，深色模式使用对应的颜色资源。

应用不锁定屏幕方向。首页提供六套尺寸/方向布局，其他页面也按窄屏、横屏和宽屏资源适配；系统栏内边距包含左右区域。相机方向随屏幕旋转同步到 CameraX。

## 应用内更新、断网与存储

首页底部版本号是更新入口：点击检查版本，发现更新后点击进入下载和安装；进入首页时也会静默检查。

更新源为 **Gitee 公开发行版**，检查和下载无需在应用内置令牌：

```text
Gitee release API → 比较版本号 → 应用内下载 APK → 系统安装器
```

先查询 `/releases/latest`；若没有可用 APK，再从发行列表选择版本号最高且带 APK 的记录。下载先写入 `.part`，完成后改名；安装通过 FileProvider 授权。Android 8+ 若需“安装未知应用”授权，会打开系统设置并在授权后继续。

`NetworkMonitor` 监听网络变化，断网时显示全屏提示页，恢复网络后返回原页面；旧系统使用兼容的网络回调。页面提供重新检测和打开网络设置入口。

启动时回收陈旧安装包与相机临时图片，历史记录和 WebView 缓存设有容量限制。首页设置入口可查看和管理相关存储。

## 构建与验证

当前版本已验证的构建环境：

| 项目 | 版本 / 配置 |
|---|---|
| JDK | 25（Android Studio JBR）；构建前将 `JAVA_HOME` 指向对应目录 |
| Gradle Wrapper | 9.5.0 |
| Android Gradle Plugin | 9.3.1，内置 Kotlin 支持 |
| Kotlin | 2.2.10；Java/Kotlin 目标字节码为 17 |
| Android SDK | compileSdk 37，build-tools 36.0.0 |
| 依赖仓库 | `settings.gradle.kts` 配置的阿里云 Google / Maven Central 镜像 |

在未纳入版本控制的 `local.properties` 中设置本机 SDK 路径，例如：

```properties
sdk.dir=C:/Users/your-name/AppData/Local/Android/Sdk
```

Windows PowerShell：

```powershell
# 先设置 JAVA_HOME，再执行完整的本地检查和构建
.\gradlew.bat :protocol:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug

# 按 app/build.gradle.kts 的版本号归档 APK
pwsh -File tools/package-apk.ps1
```

其他系统可使用 `./gradlew` 执行相同任务。构建产物为 `app/build/outputs/apk/debug/app-debug.apk`，版本归档为 `dist/闪电搜题-v<版本>.apk`。

AGP 已内置 Android Kotlin 支持，**不要再添加 `org.jetbrains.kotlin.android` 插件**，否则会出现扩展重复注册。可选的友盟 AppKey 从本机 `local.properties` 读取；未配置时跳过正式初始化，不影响主要功能。

v1.36.2 的交付检查：203 项单元测试通过，2 项网络探针默认跳过，Android Lint 0 错误，APK 编译与签名核对通过。该轮未进行模拟器、真机或接口可用性测试。

## 工程结构

```text
protocol/src/main/kotlin/com/heikeji/phonesearch/protocol/
  core/                    公共协议、签名、信封、编码与加解密
  search/                  单题 / 整页请求、解码、解析与模型
  identity/                通用设备信息与学段上报
  account/                 账号请求模型
  chat/                    快问 AI 请求与解析
  aiwriting/               AI 作文协议
  render/                  HTML 清洗、答案排版、公式与学科图标
app/src/main/kotlin/com/heikeji/phonesearch/
  net/                     Net / SearchApi / AccountApi / ChatClient / AiWritingClient
  account/                 SecureSessionStore / SessionRepository
  image/                   图像解码、方向变换与上传编码
  search/                  搜题仓库与验证码状态
  ui/                      首页、相机、裁剪、答案、登录、AI 等原生界面
    common/UiMotion        共享回弹、显隐和交互动效
  update/                  Gitee 更新检查、下载与安装
app/src/main/res/           深浅色主题与尺寸 / 方向布局
app/src/main/assets/math/   本地 KaTeX 资源
app/src/test/               账号、布局、更新、消息等单元回归
protocol/src/test/          协议、解析、渲染等单元回归
tools/des-oracle/          DES 独立 oracle（见该目录 README）
```

`app/build.gradle.kts` 在构建期校验 `minSdk = 21` 与 `armeabi-v7a` 兼容底线。布局回归检查覆盖全部 `layout-*` 变体，校验滚动容器子节点数量、同名布局 ID 集合与 View 类型，防止旋转或窄屏下的布局崩溃。

`protocol` 是不依赖 Android 的纯 Kotlin/JVM 模块，可单独运行协议与渲染回归。

## 安全与隐私

- 通用协议常量集中在 `protocol.core.NetConfig`；请求由统一执行器处理。
- 收到验证码挑战时打开官方验证页；验证页与答案页相互隔离。
- 账号使用 Keystore AES-GCM 保存；Android 5.0/5.1 使用保留兼容格式的软件加密分支。
- 实名信息不缓存、不落盘；日志禁止记录密码、验证码、完整账号凭据、密钥、Cookie、实名信息与题目原图等敏感数据。
- 首次启动展示隐私政策，友盟正式初始化在用户同意后进行。

## 免责声明

本项目是**逆向工程的学习产物**，用于个人研究与技术交流：

- **原项目「腕上搜题」及其全部协议设计、接口定义、加密与签名方案，出自原作者 yijia，著作权与署名权归其所有。** 本项目只是手机端的衍生实现。
- 协议实现来自对原 APK 的分析，相关算法与密钥的权利属于原作者 yijia 与原服务方。
- 请勿用于商业用途、批量请求或任何干扰服务正常运行的行为。
- 使用者需自行承担因使用本代码产生的一切后果，包括账号风险与法律风险。
- 如果原作者或权利方认为本仓库侵犯了其权益，请提 Issue，会立即处理，包括删除仓库。

代码中不含真实账号、密码或会话；协议常量与测试向量来自公开的反编译分析。使用、分发或二次开发时，请一并注明原作者 yijia 与原项目「腕上搜题」。
