# DES 独立 oracle

## 为什么需要它

原 APK 的 `P0.e` 是**自定义位序 DES**：低位在前取位、每字节密文编码成四个字符、
补齐方式是"中间补 0 + 最后一字节写补齐长度"、PC-2 表里还有一个非标准项（`...32,46`）。

这些细节任何一处抄错，签名都会被服务端拒绝，而且**本地无法察觉**——因为服务端才持有真值。
所以这里保留一份从 jadx 反编译结果**逐字复制**的 Java 实现，与 `:protocol` 里改写过的
Kotlin 版本（`DesCodec`）相互独立，用来生成固定向量做交叉验证。

## 文件

| 文件 | 说明 |
|---|---|
| `OriginalDes.java` | 原 `P0.e` 的逐字副本（仅重命名了入口方法） |
| `protocol/src/test/resources/des-vectors.tsv` | `明文 <TAB> 密钥 <TAB> 密文`，326 条 |
| `protocol/src/test/resources/signb-vectors.tsv` | `cuid <TAB> signA <TAB> signB <TAB> deviceSecret`，4 条 |

## 对原文的两处机械改动

1. 把 `P0.e.d` 中"生成 random10 与拼装明文"之后的部分抽成 `encodeRaw(plain, key)`。
   原方法内部用 `SecureRandom`，无法产生可复现的固定向量；被抽出的那些行与原文逐字一致。
2. 方法重命名：`a`→`md5Hex`、`e`→`desCore`、`f`→`decodeCipher`，并去掉 `P0.d` 包装
   （`c` 直接返回 deviceSecret 字符串）。

**没有改动任何 DES 逻辑、任何表、任何补齐/编码规则。**

## 如何重新生成

需要 JDK（本机 PATH 上的 `java`/`javac` 即可）。

```powershell
$dir = "tools\des-oracle"
$out = "protocol\src\test\resources"
javac -d "$dir\out" "$dir\OriginalDes.java"
java -cp "$dir\out" OriginalDes $out
```

`main` 会自校验（每条向量都做 encode→decode 往返，signB 向量还会验证解析出的
deviceSecret 与预期一致），自校验失败会直接抛异常而不是写出错误向量。

## 覆盖范围

- 密钥：`@fG2SuLA`（真实 signA 密钥）、`ABCDE#G4`、`zzzzz#G4`、`01234#G4`、`QwErT#G4`
  （后四个是真实 signB 密钥形状 `random10[0..4] + "#G4"`）
- 明文长度 1..64（覆盖补齐边界：8 的整数倍时补满 8 字节）
- 真实 signA 明文形状：`8&%d*##<random10>##<certificateDigest>##<cuid>`
- signB 路径：`<random10><2 字符><deviceSecret>` → 解析出 deviceSecret

## 与测试的关系

`DesCodecTest` 读取这两个 TSV 并逐条断言。向量文件已入库，因此**测试离线可跑**，
重新生成只在协议实现变化时才需要。

## 注意

`tools/des-oracle/out/` 是编译产物，不要入库。
