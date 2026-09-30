package com.heikeji.phonesearch.protocol.crypto

/**
 * 依次交换 chars[i] 与 chars[lastIndex - i]（对应原实现 P0.e.b）。
 *
 * 调用方必须保证 count <= chars.size，否则会越界——原实现同样如此，
 * 协议内的实际调用为 swap(32 长度, 15)、swap(96 长度, 3)、swap(128 长度, 60)。
 */
object PairSwap {

    fun swap(chars: CharArray, count: Int) {
        for (i in 0 until count) {
            val j = chars.size - 1 - i
            val tmp = chars[i]
            chars[i] = chars[j]
            chars[j] = tmp
        }
    }
}
