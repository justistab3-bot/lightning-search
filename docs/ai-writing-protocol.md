# AI 作文接口逆向记录

> 抓包时间：2026-10-05，客户端：快对作业 7.7.0（vc=1810 / channel=xiaomi）
> 验证环境：本机直连（curl / Invoke-WebRequest），协议层单测覆盖

## 与搜题的差异

AI 作文走的是**独立的 H5 接口**，和搜题那套签名体系完全无关：

| | 搜题 | AI 作文 |
|---|---|---|
| 域名 | `www.kuaiduizuoye.com` | `api.kuaiduizuoye.com` |
| 签名 | `signA`/`signB`/`sign`/`_t_`/`kakorrhaphiophobia` | **完全没有** |
| 登录态 | 需要 `KDUSS` | **不需要**，Cookie 里只有 `cuid` |
| 风控票 | — | `Dp-Ticket` 出现在 `preInstantWriting` 上，但**实测不带也能通** |
| 版本号 | 强绑定 | **实测无关**（1170/6.49.0 与 1810/7.7.0 结果一致） |
| 响应 | RC4/Base64/GZIP 编码 | 明文 JSON + **SSE 流** |

请求头只需要：

```
appid: scancode
cuid: <cuid>
vc: 1170            vcname: 6.49.0
channel: vivo       os: android
Origin / Referer: https://www.kuaiduizuoye.com
X-Requested-With: com.kuaiduizuoye.scan
Cookie: cuid=<cuid>
User-Agent: <任意非空值>
```

## 写作模式（`pageType`）

页面 JS 里有一张模式表，决定走哪套端点：

| pageType | 生成前 | 生成 |
|---|---|---|
| `aiwrite_quick` | `preInstantWriting` | `instantWriting` |
| `aiwrite_thinking` | `writingThought/preInstantWriting` | `writingThought/instantWriting` |
| `aiwrite_thinking_map` | 同 `aiwrite_thinking` | 同 `aiwrite_thinking` |
| `aiwrite_para` | `writingParagraph/preInstantWriting` | `writingParagraph/instantWriting` |
| `english` | `preEngInstantWriting` | `engInstantWriting` |

## 中文快速写作（已实现）

### 1. 文体识别

```
POST /aiwriting/ai/composition/writingintent
{"hybrid":1,"pageFrom":"标题输入页","adid":"<cuid>","cuid":"<cuid>",
 "needAnti":[],"sid":"","title":"<标题>","gradeId":6}
```

返回 `data.sid` / `data.sessionId` / `data.queryType`（文体）与
`data.queryTypeList`：记叙文 / 议论文 / 说明文 / 书信 / 散文 / 小说 / 诗歌 / 其他。

失败不致命，可以退化成默认文体。

### 2. 准备

```
POST /aiwriting/ai/composition/preInstantWriting?uid=&gradeId=6&sid=&queryType=5
     &searchFrom=&move=&title=<标题>&wordCount=800%2B&describe=&voiceDescribe=
     &entityStr=&language=Chinese&isDefaultTitle=2&photoTextId=&channel=

{"hybrid":1,"writeDate":<秒级时间戳>,"searchFrom":"shouye","entityList":[],
 "isDefaultTitle":2,"preSid":"","sessionId":"","session":{},"title":"<标题>",
 "describe":"","sid":"","language":1,"queryType":"记叙文","wordCount":"800+",
 "gradeId":6,"historySids":[]}
```

> 注意 `queryType` 在 **query 里是枚举 `5`**，在 **body 里是中文字体名**。两者不一致是原实现的行为。

返回 `data.sid` / `data.sessionId` / `data.writeDate`。

### 3. 流式生成

```
GET /aiwriting/ai/composition/instantWriting?sid=&cuid=&appid=scancode&eventId=
    &sessionId=&channel=vivo&gradeId=6&queryType=5&searchFrom=&move=&title=<标题>
    &wordCount=800%2B&describe=&entityStr=&entityContent=&language=Chinese

Accept: text/event-stream
```

## SSE 事件格式

标准 SSE，`Content-Type: text/event-stream`，chunked：

```
id:1
event:msg
data:{"more":1,"articleResult":[{"content":"当","paraIndex":0,...}],...}

id:511
event:msg
data:{"more":0,"session":{"selected":[{"paragraph_id":"...","content":"..."},...]},...}

id:512
event:close
data:<nil>
```

关键点：

- **`articleResult[].content` 是增量片段**（中文 1-2 字，英语是单词），不是全量快照。
  实测 511 个片段按序拼接恰好等于最终 `wordCount`。打字机效果用它。
- **最后一条 `more:0` 的事件带 `session.selected[]`**：分好段的完整文章，**这是权威结果**，
  不要用拼接结果当最终值。
- 结束事件的 `articleResult` 是**空的**。
- `event:close` 中文出现 1 次、英语出现 2 次，解析器要能容忍多次。

### 实测数据

| | 中文（800+） | 英语（100+） |
|---|---|---|
| 事件数 | 513 | 135 |
| 耗时 | ~7s | ~2.2s |
| 流量 | ~430 KB | ~115 KB |
| 段落数 | 6-7 | 3 |
| `articleType` | `记叙文-叙事` | 空 |

## 英语作文

端点与中文不同，参数只有两处差别：

- body 里 `language: 2`（中文是 `1`）
- query 里 `language=English`（中文是 `Chinese`）
- 字数档位不同（英语 `60+/80+/100+/120+`）
- **没有文体识别**，也不需要调 `writingintent`

## 提纲流程（端点已探明，参数未完全对齐）

```
POST /aiwriting/ai/composition/writingThought/preCreateThought
     （注意：**不带 query 字符串**）
{"hybrid":1,"cuid":"<cuid>","title":"<标题>","gradeId":6,
 "queryType":"记叙文","wordCount":"800+","language":1}
-> {"sid":"...","sessionId":"..."}
```

```
GET /aiwriting/ai/composition/writingThought/createThought?sid=&cuid=&appid=scancode&sessionId=...
Accept: text/event-stream
```

`sessionId` 是必需参数（缺失报 `errNo 4000 ... SessionId required`），
但带上后服务端返回：

```
id:1
event:error
data:{"errMsg":"生成思路失败","errNo":5324}
```

说明还缺参数（可能是框架/提纲类型的 id）。**待真实抓包确认**。

`writingThought/result?sid=&cuid=` 可用，返回 `{sid, sessionId, thoughtList}`。

## 完整端点清单

从页面 JS（`index-b_yggVbD.js` / `http-CeBGenox.js`）提取，共 60+ 条，常用的：

```
writingintent                       文体识别
writeCheck                          查重
preInstantWriting / instantWriting  中文快速写作
preEngInstantWriting / engInstantWriting  英语作文
writingThought/{preCreateThought,createThought,preInstantWriting,instantWriting,result}
writingParagraph/{preCreateParagraph,preInstantWriting,instantWriting}
preBatchInstantWriting              批量生成
preInstantChangePara / instantChangeParaV2 / changeParaList / changeParaSave  段落改写
rewrite / rewrite/preInstantWrite / rewriteChangeParagraph   润色
continuation                        续写
titlepage                           标题推荐
save                                保存
/aiwriting/composition/article/detail        文章详情
/aiwriting/composition/search/searchword     作文搜索
/aiwriting/common/app/avaliable              功能开关
```

## 测试样本

`protocol/src/test/resources/` 下有两份**真实抓包**样本（已去掉无关字段，保留完整结构）：

- `aiwriting-sse-sample.txt` —— 中文：3 个增量 + 结束事件 + close
- `aiwriting-sse-english-sample.txt` —— 英语：2 个增量 + 结束事件 + close

对应测试：`protocol/src/test/kotlin/.../aiwriting/AiWritingSseTest.kt`（18 个用例）。

## 备注

- 这套接口是 H5 页面用的（带 CORS 头），设计上就允许跨源调用，因此没有签名。
- 接口属于快对作业，会随版本变化；本文档记录的是 7.7.0 的行为。
- 实现见 `protocol/.../aiwriting/`（纯逻辑 + 解析）与 `app/.../net/AiWritingClient.kt`（HTTP + 流式读取）。
