# 闪电搜题（手机版）

以 `watchsearch_api_full_chain_handoff.md` 为协议依据，从零实现的 Kotlin Android 手机客户端。
原手表版 `com.heikeji.watchsearch` 的反编译结果只作为协议参考，代码全部重写。

- 应用名：**闪电搜题**
- 包名：`com.heikeji.phonesearch`（与原手表版隔离，可共存）
- minSdk 24 / targetSdk 37 / compileSdk 37
- 当前版本：**v1.8.0**（见 [VERSION.md](VERSION.md)）

## 这是什么

一个把「腕上搜题」手表应用的能力搬到手机上的客户端：拍题目 → 上传识别 → 展示答案与解析。
结果页竖屏是「上方固定原图、下方左右滑动切换答案」，横屏是左右分栏。

工程上有意思的部分（与具体业务无关，可以单独看）：

| 模块 | 说明 |
|---|---|
| `protocol/render/LatexRenderer` | 纯 Kotlin 的 LaTeX 子集渲染器，把公式转成 HTML+CSS 排版。**不需要 MathJax/KaTeX**，因此答案页可以保持禁用 JavaScript |
| `protocol/crypto/DesCodec` | 自定义位序的 DES 实现（PC-2 入口非标准），由独立 Java oracle + 326 条固定向量锁定 |
| `ui/result/AnswerWebView` | 解决 WebView 竖向滚动与 ViewPager2 翻页的手势冲突：按主方向在早期锁定手势归属 |
| `ui/common/PageNumberView` | 强制正方形的自绘序号圆（`shape drawable` 会被 TabView 拉成椭圆） |
| 设计语言 | Claude 设计系统的落地：暖羊皮纸底 + 陶土色 + 衬线标题，见下文 |
| 横屏 | 不锁方向，`layout-land/` 四套布局 + 系统栏左右内边距 |

## 构建

需要 **JDK 17+**（开发时用的是 `D:\ansidio\jbr`，即 JDK 25）与 Android SDK（build-tools 36.0.0）。

```bash
# local.properties 需要指向本机 SDK
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

./gradlew :protocol:test :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

按版本归档（会读 `app/build.gradle.kts` 里的版本号）：

```powershell
pwsh -File tools/package-apk.ps1   # -> dist/闪电搜题-v<版本>.apk
```

> **注意**：AGP 9.3.1 内置 Kotlin 支持，**不要**再 apply `org.jetbrains.kotlin.android`，
> 否则会报 `Cannot add extension with name 'kotlin'`。
> 另外 Maven Central 直连在此网络下返回 403，仓库只配了阿里云镜像。

## 免责声明

本项目是**逆向工程的学习产物**，用于个人研究与技术交流：

- 协议实现（签名、加密、设备标识、验证码挑战）来自对原 APK 的分析，**相关算法与密钥的权利属于原服务方**
- 请勿用于商业用途、批量请求或任何干扰服务正常运行的行为
- 使用者需自行承担因使用本代码产生的一切后果，包括但不限于账号风险与法律风险
- 如果权利方认为本仓库侵犯了其权益，请提 Issue，会立即处理

代码中不含任何真实账号、密码、会话或设备标识；协议常量与测试向量均来自公开的反编译分析。

## 设计语言

参考 Claude 设计系统，刻意避开"AI 产品"那套冷色 + 渐变 + 霓虹：

| 维度 | 做法 |
|---|---|
| 底色 | 暖羊皮纸 `#F5F4ED`，卡片象牙白 `#FAF9F5` |
| 品牌色 | 陶土色 `#C96442`，**整套里唯一的高饱和色**，只用于主操作 |
| 中性色 | 全部带黄褐底调（`#4D4C48` / `#5E5D59` / `#87867F`），不用冷灰 |
| 标题 | 衬线体、单一字重 500（不做粗体） |
| 正文 | 无衬线、行高 1.7 |
| 层次 | 1px 暖描边 + 极轻投影，不用重投影、不用渐变 |
| 圆角 | 卡片 16dp、主容器 24dp、按钮 14dp |
| 学科标识 | 低饱和土质色圆角徽标（工具栏 / 历史列表 / 答案页各一处） |

深色模式有独立的暖炭色板（`values-night/colors.xml`），不是简单反色。

## 横屏适配

- 不锁定 Activity 方向；相机方向交给 CameraX 的 `targetRotation`，并在屏幕旋转时同步。
- `layout-land/` 提供三套横屏布局：
  - 结果页：左栏原图 + 右栏答案，左右分栏
  - 首页：左栏问候/主操作/统计 + 右栏最近搜题
  - 登录页：内容居中并限制宽度，避免大屏被拉散

## 公式排版

答案里的 LaTeX **不用 MathJax/KaTeX**（答案页 WebView 禁用 JavaScript，且从网络加载脚本会破坏 CSP），
而是 `protocol/render/LatexRenderer` 这个纯 Kotlin 子集渲染器，转成 HTML + CSS：

- 分式 `\frac` `\dfrac` `\tfrac`、根式 `\sqrt`（含 `\sqrt[n]{}`）
- 上下标、希腊字母、常用运算符/关系符/箭头/几何符号
- 函数名 `\sin` `\log` `\lim`…、`\text{}` 原样文本
- 定界符 `$...$` `$$...$$` `\(...\)` `\[...\]`；全角 `＼` 自动纠正
- 无法识别的命令**原样保留命令名**，不会把内容吞掉

渲染顺序是「先清洗 HTML → 再对文本节点做公式排版」，生成的标签全部来自本模块，
因此不会引入不可信标记。

## 学科图标

`protocol/render/SubjectIcons` 是唯一的图标数据源（24x24 SVG path）：

| 学科 | 图标 | 学科 | 图标 |
|---|---|---|---|
| 数学 | 根号 | 化学 | 试管 |
| 语文 | 摊开的书 | 生物 | 叶子 |
| 英语 | 地球 | 历史 | 沙漏 |
| 物理 | 锥形瓶（实验器材） | 地理 | 折叠地图 |
| 政治 | 天平 | 其他 | 文档 |

原生界面用 `PathParser` 绘制（`SubjectIconView`），答案页 HTML 用内联 SVG，两者共用同一份 path。

## 手势

答案页每页一个 WebView。竖向滑动与 ViewPager2 翻页的冲突由 `AnswerWebView` 处理：
在手势早期判断主方向，竖向为主时对父容器 `requestDisallowInterceptTouchEvent(true)`
把整个手势锁给 WebView 滚动，横向为主时交给 ViewPager2 翻页。

## 拍照与裁剪

不调用系统相机应用，也不提供相册入口，全流程在应用内完成：

```
首页「拍照搜题」
  -> CameraActivity   内置 CameraX 相机（预览 / 快门 / 闪光灯三态 / 前后置切换）
  -> CropActivity     裁剪框（拖框内平移、拖四角缩放）+ 左右旋转 90°
  -> ResultActivity   编码成上传用 JPEG 后直接搜题
```

- 相机页与裁剪页锁定竖屏，避免旋转带来的坐标换算问题。
- 裁剪视图 `CropImageView` 自己绘制位图并管理裁剪框，避免「ImageView + 覆盖层」两套坐标系同步的经典问题。
- 图片处理分两步：`decodeForEditing`（采样到最长边 2048、按 EXIF 摆正）→ 用户编辑 →
  `encodeForUpload`（最长边 1600、JPEG 88、校验 SOI）。

## 结果页

用户确认的布局：**上方固定原图，下方答案区左右滑动切换**。

```
┌──────────────────────────────┐
│ ←  数学 · 第 2/3 条           │  AppBar
├──────────────────────────────┤
│        你拍的原图             │  固定，不随滑动变化
│    （点击全屏捏合缩放）        │  展开=屏高 36%，可收起到 48dp
├──────────────────────────────┤
│    [ 1 ]  [ 2 ]  [ 3 ]       │  TabLayout，可点切换
├──────────────────────────────┤
│  答案                         │  ViewPager2
│  <WebView 渲染>               │  ← 左右滑动切换答案
│  解析                         │  ↑↓ 页内垂直滚动
│  匹配到的题目                  │
└──────────────────────────────┘
```

一页 = `answers.mainPageInfo` 的一个元素。每页**只放一个 WebView**：垂直滚动交给 WebView，
横向滑动交给 ViewPager2，避免嵌套滚动的手势冲突。

## 工程结构

```
protocol/   纯 Kotlin/JVM，无 Android 依赖（编译期强制边界）
  ProtocolProfile        协议常量集中处（UI 不得硬编码）
  crypto/                DesCodec(自定义位序 DES) / Rc4 / Digests / PairSwap / ResponseKey
  sign/                  SignA(signA 生成与 signB 校验) / RequestSigner
  codec/                 UrlForm / Base64NoWrap
  envelope/              Envelope(antispam 响应逐层解包)
  decode/                AnswerDecoder(三条解码路径 + gzip)
  parse/                 AnswerParser / ImagePidResolver
  render/                AnswerHtmlSanitizer(jsoup 白名单) / AnswerPageRenderer
  model/                 AnswerItem / SearchResult / AccountSession / SearchChallenge

app/        Android
  net/       DeviceIdentity / HttpTransport / ProtocolContext / BootstrapClient / ApiClient
  account/   SecureSessionStore(Keystore AES-GCM) / SessionRepository
  image/     QuestionImageProcessor
  search/    SearchRepository / SearchChallengeStore
  ui/        login / home / camera / crop / result / verification
tools/des-oracle/   DES 独立 oracle（见该目录 README）
```

## 构建

构建必须用 **JDK 25**（Android Studio 自带 JBR）。PATH 上的 Java 26 不受 Gradle 9.5 支持。

```powershell
$env:JAVA_HOME="D:\ansidio\jbr"
.\gradlew.bat :protocol:test        # 协议层单元测试
.\gradlew.bat :app:assembleDebug    # 产出 app\build\outputs\apk\debug\app-debug.apk
```

环境要点（已实测）：

| 项 | 值 |
|---|---|
| AGP | 9.3.1（要求 Gradle **恰好 9.5.0**） |
| Kotlin | 由 AGP 内置（AGP 自身依赖 kotlin-gradle-plugin 2.2.10），**不要**再 apply `org.jetbrains.kotlin.android` |
| build-tools | 36.0.0（AGP 9.3.1 的默认值，显式固定） |
| 仓库 | 只配阿里云镜像（本机直连 Maven Central 返回 403） |

## 安全与隐私

- 协议常量只在 `ProtocolProfile`。
- **不实现任何绕过风控的逻辑**；收到 `validatedInfo` 必须走官方验证页。
- 验证页 WebView 与答案 WebView 完全分离；答案 WebView **禁用 JavaScript**、禁止一切导航。
- 验证页 Bridge 校验 bridgeSecret + origin/当前 URL/预期 URL 三者相等 + HTTPS + 精确 host/path
  + 无 userInfo/端口/fragment + query 仅 `validatedInfo` + 回调 ≤32768 字符 + KDUSS 未变；
  action 走白名单，其余回 404。
- KDUSS 用 Android Keystore + AES-GCM 保存，禁止明文落盘。
- 实名信息（姓名、身份证号）不缓存、不落盘、不进日志。
- 日志禁止出现：手机号/密码/验证码、KDUSS/CUID、signA/signB/deviceSecret/responseKey/sign、
  姓名/身份证号、validatedInfo、完整题目图片、Cookie。

## 已知待验证项

1. **真实网络链路尚未跑通验证**：需要可用账号完成短信/密码登录。
   协议层（签名、RC4、DES、解码）有 34 个单元测试覆盖，但端到端需实账号确认。
2. **密码登录端点** `loginv2`：反编译的 `P0.c` 只实现了短信登录，密码登录按
   `{phone, password, idfa:"", yongsterStatus:"0"}` 的内层顺序复用通用加密 POST，需实测确认。
3. **`app_zyb_identityCheck` 的参数名**：假设页面通过 `param.name` / `param.id` 传值，
   缺失时回 404。若服务端用别的字段名需要调整。
4. **原实现的 `m(html, cuid)` 预处理**：该方法在 JADX 合并类里无法按签名定位，
   其图片地址规范化语义与渲染期的 `AnswerHtmlSanitizer` 一致，因此统一放到渲染期处理。
5. 协议常量（`vc=1170` / `vcname=6.49.0` / `token` / `certificateDigest`）是版本绑定值，
   服务端升级后需要整体更新 `ProtocolProfile`。

## 与原交接文档的差异

1. 文档 §10.1 说 `sid` 未保存到挑战对象 —— 实际 `P0.k(validatedInfo, sid)` 已传入，本实现同样保存 `sid`。
2. 文档 §3.2 的 `identityIdV2` / `occupationType` 不在会话对象 `P0.l` 里，而是 `P0.c` 的实例状态。
   本实现把它们随会话持久化，并在恢复会话时重新拉 `userinfov3` 回填（原版重启后会退化为 0）。
3. 密码登录 `loginv2` 见上「已知待验证项 2」。
