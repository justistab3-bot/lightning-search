# 「查看整本答案」接口逆向记录

> 来源：快对作业 7.7.0（`com.kuaiduizuoye.scan`）原生代码 + 真机抓包
> 抓包时间：2026-10-05，整页搜题 → 点「查看整本答案」

## 链路

```
整页搜题响应里带 relatedBook.bookId / pageId
        ↓
（可选）POST /kdgrowth/search/pagebookinfo    H5 点击按钮时调，拿答案书元信息
        ↓
POST /search/submit/booksearch               取整本答案
        ↓
POST /search/submit/viewbook                 埋点（返回空对象）
        ↓
GET  https://kd-book.cdnjtzy.com/scanimg/scan_<hash>.jpg   答案页扫描图
```

## booksearch

原生定义在 `com.kuaiduizuoye.scan.common.net.model.v1.SearchBookSearch.Input`。

```
POST /search/submit/booksearch
Content-Type: application/x-www-form-urlencoded

data=<RC4+Base64 加密的下面这些参数>&<公共参数>&sign=...&_t_=...&kakorrhaphiophobia=...
```

| 参数 | 说明 |
|---|---|
| `bookId` | 教材 id |
| `ticket` | 验证码票据；正常流程为空 |
| `randStr` | 验证码随机串；正常流程为空 |
| `isXposed` | 原生**硬编码 0** |
| `isEmulator` | 原生**硬编码 0** |
| `isHitDayup` | 配置开关，默认 0 |
| `grade` | 用户年级（原生取设置里的） |
| `resolution` | `屏宽*屏高`，如 `1080*2340` |

原生调用点（`com.kuaiduizuoye.scan.activity.scan.util.b2`）：

```java
SearchBookSearch.Input.buildInput(
    model.bookId, model.ticket, model.randStr,
    0, 0, model.isHitDayup, model.grade,
    z.e() + "*" + z.d()      // 屏宽 * 屏高
);
```

### 加解密

和登录接口**完全一致**（本项目的 `ApiClient.postEncrypted`）：

- 请求：`data = Base64(RC4("&" + urlencode(innerParams), responseKey))`
- 响应：`data` 是 base64 → RC4 解密 → 明文

响应外壳：

```json
{"errNo":0,"errstr":"success","data":{"data":"<base64 密文>"}}
```

注意是**两层 data**。解出来的明文可能还套了一层 gzip（魔数 `1f 8b`），所以实现里先看魔数再决定要不要 `GZIPInputStream`。

## 响应模型 `SearchBookSearch`

只列本应用用到的：

| 字段 | 说明 |
|---|---|
| `bookId` / `name` / `subject` / `grade` / `term` / `version` / `cover` | 书元信息 |
| `hasAnswer` | 服务端标记是否有答案 |
| **`answerList[]`** | `{origin(原图), thumbnail(缩略图), w, h, isHD}` ← **答案页** |
| `answers[]` / `oriAnswers[]` | 原生把 `answerList` 摊平成这两个字符串数组 |
| `pageList[]` | `{url, thumbnail, pageNum, width, height, tids, locs}` |
| `pageId` / `pageInfo` / `multiVersionInfo` | 页码与多版本 |
| `saleInfo` | 付费信息（`hasBuy` / `unbuyInfo` / `buyInfo`） |
| `needVerify` / `isExist` / `isOnline` / `bookType` / `isCollected` | 状态标记 |

原生摊平逻辑（`b2.i`）：

```java
for (SearchBookSearch.AnswerList a : searchBookSearch.answerList) {
    searchBookSearch.answers.add(a.thumbnail);
    searchBookSearch.oriAnswers.add(a.origin);
}
```

**所以 `answerList` 是首选来源**，`answers`/`oriAnswers` 只是退路。

## 待确认

1. **`ticket` / `randStr` 是否必需** —— 腾讯验证码字段，正常流程应为空。抓包里这两个在密文内，看不到值。需要真机实测。
2. **`name` / `cover` 是否单独加密** —— `pagebookinfo` 的响应里这两个是密文（整包是明文 JSON），`booksearch` 是整包加密所以理论上解出来就是明文。实现里做了防御：解不出来就原样用。
3. **`Dp-Ticket` 请求头** —— 抓包里有，但 AI 作文那边实测不带也能通，可能同样可选。

## 实现位置

```
protocol/.../book/
  BookSearchRequest.kt    参数构造（含 resolution 拼接）
  BookSearchParser.kt     响应解析 + 从整页响应里挖 relatedBook
  model/BookAnswer.kt     数据模型
app/net/ApiClient.kt      searchBook()：复用 postEncrypted 那套加解密
app/ui/book/
  BookAnswerViewModel.kt  加载状态
  BookAnswerActivity.kt   ViewPager2 翻页 + 缩放 + 位图 LRU 缓存
```

测试：`protocol/src/test/kotlin/.../book/BookSearchParserTest.kt`（15 个用例）
