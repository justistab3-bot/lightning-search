/*
 * DES 独立 oracle —— 直接从 jadx 反编译结果逐字复制而来。
 *
 * 目的：为 :protocol 的 Kotlin 移植版（DesCodec）生成**固定向量**。
 * 这是与 Kotlin 实现相互独立的第二份实现（原始 Java vs 改写后的 Kotlin），
 * 用来抓出移植过程中的笔误。
 *
 * 唯一的两处机械改动（不改动任何 DES 逻辑）：
 *   1. 把原 P0.e.d 中「生成 random10」与「拼装明文」之后的部分抽成 encodeRaw(plain, key)，
 *      使其可以对任意明文确定性地求值（原方法内部用 SecureRandom，无法产生固定向量）。
 *      被抽出的行与原文逐字一致。
 *   2. 方法重命名：a->md5Hex、e->desCore、f->decodeCipher，并去掉 P0.d 包装。
 *
 * 使用方法见同目录 README.md。
 */
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;

public final class OriginalDes {

    public static final SecureRandom f708a = new SecureRandom();
    public static final char[] b = "0123456789abcdef".toCharArray();
    public static final String DIGEST = "2fb53de6d38eff7109f19d68e047123b";

    public static final int[] f709c = {57, 49, 41, 33, 25, 17, 9, 1, 59, 51, 43, 35, 27, 19, 11, 3, 61, 53, 45, 37, 29, 21, 13, 5, 63, 55, 47, 39, 31, 23, 15, 7, 56, 48, 40, 32, 24, 16, 8, 0, 58, 50, 42, 34, 26, 18, 10, 2, 60, 52, 44, 36, 28, 20, 12, 4, 62, 54, 46, 38, 30, 22, 14, 6};
    public static final int[] f710d = {39, 7, 47, 15, 55, 23, 63, 31, 38, 6, 46, 14, 54, 22, 62, 30, 37, 5, 45, 13, 53, 21, 61, 29, 36, 4, 44, 12, 52, 20, 60, 28, 35, 3, 43, 11, 51, 19, 59, 27, 34, 2, 42, 10, 50, 18, 58, 26, 33, 1, 41, 9, 49, 17, 57, 25, 32, 0, 40, 8, 48, 16, 56, 24};
    public static final int[] f711e = {56, 48, 40, 32, 24, 16, 8, 0, 57, 49, 41, 33, 25, 17, 9, 1, 58, 50, 42, 34, 26, 18, 10, 2, 59, 51, 43, 35, 62, 54, 46, 38, 30, 22, 14, 6, 61, 53, 45, 37, 29, 21, 13, 5, 60, 52, 44, 36, 28, 20, 12, 4, 27, 19, 11, 3};
    public static final int[] f = {13, 16, 10, 23, 0, 4, 2, 27, 14, 5, 20, 9, 22, 18, 11, 3, 25, 7, 15, 6, 26, 19, 12, 1, 40, 51, 30, 36, 46, 54, 29, 39, 50, 44, 32, 46, 43, 48, 38, 55, 33, 52, 45, 41, 49, 35, 28, 31};
    public static final int[] f712g = {1, 1, 2, 2, 2, 2, 2, 2, 1, 2, 2, 2, 2, 2, 2, 1};
    public static final int[] f713h = {31, 0, 1, 2, 3, 4, 3, 4, 5, 6, 7, 8, 7, 8, 9, 10, 11, 12, 11, 12, 13, 14, 15, 16, 15, 16, 17, 18, 19, 20, 19, 20, 21, 22, 23, 24, 23, 24, 25, 26, 27, 28, 27, 28, 29, 30, 31, 0};
    public static final int[] f714i = {15, 6, 19, 20, 28, 11, 27, 16, 0, 14, 22, 25, 4, 17, 30, 9, 1, 7, 23, 13, 31, 26, 2, 8, 18, 12, 29, 5, 21, 10, 3, 24};
    public static final int[][] f715j = {new int[]{14, 4, 13, 1, 2, 15, 11, 8, 3, 10, 6, 12, 5, 9, 0, 7, 0, 15, 7, 4, 14, 2, 13, 1, 10, 6, 12, 11, 9, 5, 3, 8, 4, 1, 14, 8, 13, 6, 2, 11, 15, 12, 9, 7, 3, 10, 5, 0, 15, 12, 8, 2, 4, 9, 1, 7, 5, 11, 3, 14, 10, 0, 6, 13}, new int[]{15, 1, 8, 14, 6, 11, 3, 4, 9, 7, 2, 13, 12, 0, 5, 10, 3, 13, 4, 7, 15, 2, 8, 14, 12, 0, 1, 10, 6, 9, 11, 5, 0, 14, 7, 11, 10, 4, 13, 1, 5, 8, 12, 6, 9, 3, 2, 15, 13, 8, 10, 1, 3, 15, 4, 2, 11, 6, 7, 12, 0, 5, 14, 9}, new int[]{10, 0, 9, 14, 6, 3, 15, 5, 1, 13, 12, 7, 11, 4, 2, 8, 13, 7, 0, 9, 3, 4, 6, 10, 2, 8, 5, 14, 12, 11, 15, 1, 13, 6, 4, 9, 8, 15, 3, 0, 11, 1, 2, 12, 5, 10, 14, 7, 1, 10, 13, 0, 6, 9, 8, 7, 4, 15, 14, 3, 11, 5, 2, 12}, new int[]{7, 13, 14, 3, 0, 6, 9, 10, 1, 2, 8, 5, 11, 12, 4, 15, 13, 8, 11, 5, 6, 15, 0, 3, 4, 7, 2, 12, 1, 10, 14, 9, 10, 6, 9, 0, 12, 11, 7, 13, 15, 1, 3, 14, 5, 2, 8, 4, 3, 15, 0, 6, 10, 1, 13, 8, 9, 4, 5, 11, 12, 7, 2, 14}, new int[]{2, 12, 4, 1, 7, 10, 11, 6, 8, 5, 3, 15, 13, 0, 14, 9, 14, 11, 2, 12, 4, 7, 13, 1, 5, 0, 15, 10, 3, 9, 8, 6, 4, 2, 1, 11, 10, 13, 7, 8, 15, 9, 12, 5, 6, 3, 0, 14, 11, 8, 12, 7, 1, 14, 2, 13, 6, 15, 0, 9, 10, 4, 5, 3}, new int[]{12, 1, 10, 15, 9, 2, 6, 8, 0, 13, 3, 4, 14, 7, 5, 11, 10, 15, 4, 2, 7, 12, 9, 5, 6, 1, 13, 14, 0, 11, 3, 8, 9, 14, 15, 5, 2, 8, 12, 3, 7, 0, 4, 10, 1, 13, 11, 6, 4, 3, 2, 12, 9, 5, 15, 10, 11, 14, 1, 7, 6, 0, 8, 13}, new int[]{4, 11, 2, 14, 15, 0, 8, 13, 3, 12, 9, 7, 5, 10, 6, 1, 13, 0, 11, 7, 4, 9, 1, 10, 14, 3, 5, 12, 2, 15, 8, 6, 1, 4, 11, 13, 12, 3, 7, 14, 10, 15, 6, 8, 0, 5, 9, 2, 6, 11, 13, 8, 1, 4, 10, 7, 9, 5, 0, 15, 14, 2, 3, 12}, new int[]{13, 2, 8, 4, 6, 15, 11, 1, 10, 9, 3, 14, 5, 0, 12, 7, 1, 15, 13, 8, 10, 3, 7, 4, 12, 5, 6, 11, 0, 14, 9, 2, 7, 11, 4, 1, 9, 12, 14, 2, 0, 6, 10, 13, 15, 3, 5, 8, 2, 1, 14, 7, 4, 10, 8, 13, 15, 12, 9, 0, 3, 5, 6, 11}};

    /** 原 P0.e.a */
    public static String md5Hex(String str) {
        try {
            byte[] bArrDigest = MessageDigest.getInstance("MD5").digest(str.getBytes(StandardCharsets.US_ASCII));
            char[] cArr = new char[bArrDigest.length * 2];
            for (int i2 = 0; i2 < bArrDigest.length; i2++) {
                int i3 = i2 * 2;
                char[] cArr2 = b;
                byte b2 = bArrDigest[i2];
                cArr[i3] = cArr2[(b2 & 255) >>> 4];
                cArr[i3 + 1] = cArr2[b2 & 15];
            }
            return new String(cArr);
        } catch (NoSuchAlgorithmException e2) {
            throw new RuntimeException("设备不支持签名摘要算法", e2);
        }
    }

    /** 原 P0.e.b */
    public static void b(char[] cArr, int i2) {
        for (int i3 = 0; i3 < i2; i3++) {
            int length = (cArr.length - 1) - i3;
            char c2 = cArr[i3];
            cArr[i3] = cArr[length];
            cArr[length] = c2;
        }
    }

    /**
     * 原 P0.e.d 去掉 random10 生成与明文拼装之后的部分（逐字）。
     * 输入明文必须是非空可打印 ASCII。
     */
    public static String encodeRaw(String str, String key) {
        i(str, true);
        byte[] bytes = str.getBytes(StandardCharsets.US_ASCII);
        int length = 8 - (bytes.length % 8);
        byte[] bArrCopyOf = Arrays.copyOf(bytes, bytes.length + length);
        bArrCopyOf[bArrCopyOf.length - 1] = (byte) length;
        byte[] bArrE = desCore(bArrCopyOf, key, false);
        char[] cArr2 = new char[bArrE.length * 4];
        for (int i3 = 0; i3 < bArrE.length; i3++) {
            int i4 = i3 * 4;
            cArr2[i4] = '0';
            int iJ = j(bArrE[i3] & 15);
            char[] cArr3 = b;
            cArr2[i4 + 1] = cArr3[iJ];
            cArr2[i4 + 2] = '0';
            cArr2[i4 + 3] = cArr3[j((bArrE[i3] & 255) >>> 4)];
        }
        return new String(cArr2);
    }

    /** 原 P0.e.d 的明文拼装（含 random10 生成），保留作为参考实现。 */
    public static String d(String str) throws IOException {
        char[] cArr = new char[10];
        for (int i2 = 0; i2 < 10; i2++) {
            cArr[i2] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".charAt(f708a.nextInt(62));
        }
        String str2 = new String(cArr);
        i(str, true);
        if (str.length() > 1024 || !DIGEST.matches("[0-9a-f]{32}")) {
            throw g();
        }
        if (!str2.matches("[A-Za-z0-9]{10}")) {
            throw g();
        }
        String str3 = "8&%d*##" + str2 + "##" + DIGEST + "##" + str;
        return encodeRaw(str3, "@fG2SuLA");
    }

    /** 原 P0.e.f */
    public static String decodeCipher(String str, String str2) throws IOException {
        if (str == null || str.isEmpty() || str.length() > 16384 || str.length() % 32 != 0) {
            throw g();
        }
        int length = str.length() / 4;
        byte[] bArr = new byte[length];
        for (int i2 = 0; i2 < length; i2++) {
            int i3 = i2 * 4;
            char cCharAt = str.charAt(i3 + 1);
            int i4 = -1;
            int i5 = (cCharAt < '0' || cCharAt > '9') ? (cCharAt < 'a' || cCharAt > 'f') ? -1 : cCharAt - 'W' : cCharAt - '0';
            char cCharAt2 = str.charAt(i3 + 3);
            if (cCharAt2 >= '0' && cCharAt2 <= '9') {
                i4 = cCharAt2 - '0';
            } else if (cCharAt2 >= 'a' && cCharAt2 <= 'f') {
                i4 = cCharAt2 - 'W';
            }
            if (str.charAt(i3) != '0' || str.charAt(i3 + 2) != '0' || i5 < 0 || i4 < 0) {
                throw g();
            }
            bArr[i2] = (byte) (j(i5) | (j(i4) << 4));
        }
        byte[] bArrE = desCore(bArr, str2, true);
        int i6 = bArrE[bArrE.length - 1] & 255;
        if (i6 < 1 || i6 > 8 || i6 > bArrE.length) {
            throw g();
        }
        int length2 = bArrE.length - i6;
        for (int i7 = length2; i7 < bArrE.length - 1; i7++) {
            if (bArrE[i7] != 0) {
                throw g();
            }
        }
        String str3 = new String(bArrE, 0, length2, StandardCharsets.US_ASCII);
        i(str3, true);
        return str3;
    }

    /** 原 P0.e.c（去掉 P0.d 包装，直接返回 deviceSecret）。 */
    public static String parseDeviceSecret(String cuid, String signA, String signB) throws IOException {
        i(cuid, true);
        if (cuid.length() > 1024 || !DIGEST.matches("[0-9a-f]{32}")) {
            throw g();
        }
        String strF = decodeCipher(signA, "@fG2SuLA");
        if (strF.length() < 53 || !strF.startsWith("8&%d*##") || !strF.substring(17, 19).equals("##") || !strF.substring(51, 53).equals("##") || !strF.substring(53).equals(cuid) || !strF.substring(19, 51).equals(DIGEST)) {
            throw g();
        }
        String strSubstring = strF.substring(7, 17);
        if (!strSubstring.matches("[A-Za-z0-9]{10}")) {
            throw g();
        }
        String strF2 = decodeCipher(signB, strSubstring.substring(0, 5) + "#G4");
        if (strF2.length() == 22 && strF2.substring(0, 10).equals(strSubstring)) {
            return strF2.substring(12);
        }
        throw g();
    }

    /** 原 P0.e.g */
    public static IOException g() {
        return new IOException("设备签名材料无效或与当前身份不匹配");
    }

    /** 原 P0.e.h */
    public static int[] h(int[] iArr, int[] iArr2) {
        int[] iArr3 = new int[iArr2.length];
        for (int i2 = 0; i2 < iArr2.length; i2++) {
            iArr3[i2] = iArr[iArr2[i2]];
        }
        return iArr3;
    }

    /** 原 P0.e.i */
    public static void i(String str, boolean z2) {
        if (str == null || (z2 && str.isEmpty())) {
            throw new IllegalArgumentException("设备签名材料无效或与当前身份不匹配");
        }
        for (int i2 = 0; i2 < str.length(); i2++) {
            if (str.charAt(i2) < ' ' || str.charAt(i2) > '~') {
                throw new IllegalArgumentException("设备签名材料无效或与当前身份不匹配");
            }
        }
    }

    /** 原 P0.e.j */
    public static int j(int i2) {
        return ((i2 & 8) >>> 3) | ((i2 & 1) << 3) | ((i2 & 2) << 1) | ((i2 & 4) >>> 1);
    }

    /** 原 P0.e.k */
    public static int[] k(byte[] bArr, int i2) {
        int[] iArr = new int[64];
        for (int i3 = 0; i3 < 64; i3++) {
            iArr[i3] = (bArr[(i3 / 8) + i2] >>> (i3 % 8)) & 1;
        }
        return iArr;
    }

    /** 原 P0.e.e */
    public static byte[] desCore(byte[] bArr, String str, boolean z2) {
        int[] iArrH = h(k(str.getBytes(StandardCharsets.US_ASCII), 0), f711e);
        int i2 = 16;
        int[][] iArr = new int[16][];
        int i3 = 0;
        while (i3 < 16) {
            int[] iArr2 = new int[56];
            for (int i4 = 0; i4 < 56; i4++) {
                iArr2[i4] = iArrH[(((i4 % 28) + f712g[i3]) % 28) + ((i4 / 28) * 28)];
            }
            iArr[i3] = h(iArr2, f);
            i3++;
            iArrH = iArr2;
        }
        byte[] bArr2 = new byte[bArr.length];
        int i5 = 0;
        while (i5 < bArr.length) {
            int[] iArrH2 = h(k(bArr, i5), f709c);
            int[] iArrCopyOfRange = Arrays.copyOfRange(iArrH2, 0, 32);
            int[] iArrCopyOfRange2 = Arrays.copyOfRange(iArrH2, 32, 64);
            int i6 = 0;
            while (i6 < i2) {
                int[] iArrH3 = h(iArrCopyOfRange2, f713h);
                int[] iArr3 = iArr[z2 ? 15 - i6 : i6];
                for (int i7 = 0; i7 < 48; i7++) {
                    iArrH3[i7] = iArrH3[i7] ^ iArr3[i7];
                }
                int[] iArr4 = new int[32];
                int i8 = 0;
                while (i8 < 8) {
                    int i9 = i8 * 6;
                    int i10 = i2;
                    int i11 = f715j[i8][(((iArrH3[i9] * 2) + iArrH3[i9 + 5]) * 16) + (iArrH3[i9 + 3] * 2) + (iArrH3[i9 + 2] * 4) + (iArrH3[i9 + 1] * 8) + iArrH3[i9 + 4]];
                    for (int i12 = 0; i12 < 4; i12++) {
                        iArr4[(i8 * 4) + i12] = (i11 >>> (3 - i12)) & 1;
                    }
                    i8++;
                    i2 = i10;
                }
                int i13 = i2;
                int[] iArrH4 = h(iArr4, f714i);
                for (int i14 = 0; i14 < 32; i14++) {
                    iArrCopyOfRange[i14] = iArrCopyOfRange[i14] ^ iArrH4[i14];
                }
                if (i6 != 15) {
                    int[] iArr5 = iArrCopyOfRange2;
                    iArrCopyOfRange2 = iArrCopyOfRange;
                    iArrCopyOfRange = iArr5;
                }
                i6++;
                i2 = i13;
            }
            int i15 = i2;
            System.arraycopy(iArrCopyOfRange, 0, iArrH2, 0, 32);
            System.arraycopy(iArrCopyOfRange2, 0, iArrH2, 32, 32);
            int[] iArrH5 = h(iArrH2, f710d);
            for (int i16 = 0; i16 < 64; i16++) {
                int i17 = (i16 / 8) + i5;
                bArr2[i17] = (byte) (bArr2[i17] | (iArrH5[i16] << (i16 % 8)));
            }
            i5 += 8;
            i2 = i15;
        }
        return bArr2;
    }

    // ------------------------------------------------------------------ 向量生成

    private static final String CORPUS =
        "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ-.";

    private static final String[] KEYS = {
        "@fG2SuLA", "ABCDE#G4", "zzzzz#G4", "01234#G4", "QwErT#G4",
    };

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: OriginalDes <outputDir>");
            System.exit(2);
        }
        java.io.File dir = new java.io.File(args[0]);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IllegalStateException("cannot create " + dir);
        }

        // 1) encode/decode 固定向量
        try (PrintWriter w = new PrintWriter(new java.io.File(dir, "des-vectors.tsv"), "UTF-8")) {
            w.println("# plain<TAB>key<TAB>cipher   —— 由 OriginalDes 生成，勿手工编辑");
            for (String key : KEYS) {
                // 从 1 开始：原实现 i(str, true) 不接受空明文。
                for (int len = 1; len <= 64; len++) {
                    String plain = CORPUS.substring(0, len);
                    emit(w, plain, key);
                }
            }
            // 真实 signA 明文形状
            String[] cuids = {
                "3F2A1B4C5D6E7F8091A2B3C4D5E6F701|0",
                "00000000000000000000000000000000|0",
            };
            String[] r10s = {"Ab3xY9zQ1w", "0000000000", "ZZZZZZZZZZ"};
            for (String cuid : cuids) {
                for (String r10 : r10s) {
                    emit(w, "8&%d*##" + r10 + "##" + DIGEST + "##" + cuid, "@fG2SuLA");
                }
            }
        }

        // 2) signA/signB -> deviceSecret 固定向量
        try (PrintWriter w = new PrintWriter(new java.io.File(dir, "signb-vectors.tsv"), "UTF-8")) {
            w.println("# cuid<TAB>signA<TAB>signB<TAB>deviceSecret   —— 由 OriginalDes 生成，勿手工编辑");
            String[] cuids = {
                "3F2A1B4C5D6E7F8091A2B3C4D5E6F701|0",
                "AABBCCDDEEFF00112233445566778899|0",
            };
            String[] r10s = {"Ab3xY9zQ1w", "q7Wm2Zp0Kd"};
            String[] secrets = {"9fK2mQ7xLp", "0123456789"};
            for (String cuid : cuids) {
                for (int idx = 0; idx < r10s.length; idx++) {
                    String r10 = r10s[idx];
                    String secret = secrets[idx % secrets.length];
                    String signA = encodeRaw("8&%d*##" + r10 + "##" + DIGEST + "##" + cuid, "@fG2SuLA");
                    String signB = encodeRaw(r10 + "xy" + secret, r10.substring(0, 5) + "#G4");
                    String parsed = parseDeviceSecret(cuid, signA, signB);
                    if (!parsed.equals(secret)) {
                        throw new IllegalStateException("oracle self-check failed");
                    }
                    w.println(cuid + "\t" + signA + "\t" + signB + "\t" + secret);
                }
            }
        }

        System.out.println("wrote des-vectors.tsv and signb-vectors.tsv to " + dir.getAbsolutePath());
    }

    private static void emit(PrintWriter w, String plain, String key) throws IOException {
        String cipher = encodeRaw(plain, key);
        String back = decodeCipher(cipher, key);
        if (!back.equals(plain)) {
            throw new IllegalStateException("round-trip failed for len=" + plain.length());
        }
        w.println(plain + "\t" + key + "\t" + cipher);
    }
}
