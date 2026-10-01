package com.heikeji.phonesearch.protocol.model

import com.heikeji.phonesearch.protocol.ProtocolProfile

/**
 * 一个题框的四角坐标及包围矩形，对应原 `P0.e`。
 *
 * 坐标顺序为左上、右上、右下、左下，格式 `x1@y1@x2@y2@x3@y3@x4@y4`，
 * 位于**服务端返回图片的像素坐标系**。
 */
class QuestionQuad private constructor(
    val points: IntArray,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {

    val width: Int get() = right - left
    val height: Int get() = bottom - top

    /** 服务端图片坐标系下的包围矩形，用于构造 `pageExtraInfo.loc`。 */
    fun boundingRectString(): String = "${left.toFloat()}${ProtocolProfile.LOC_SEPARATOR}" +
        "${top.toFloat()}${ProtocolProfile.LOC_SEPARATOR}" +
        "${right.toFloat()}${ProtocolProfile.LOC_SEPARATOR}" +
        "${bottom.toFloat()}"

    override fun toString(): String =
        "QuestionQuad($left,$top-$right,$bottom points=${points.joinToString(",")})"

    companion object {

        /**
         * 解析 `locs[i]`。
         *
         * 校验（对齐原 `P0.e`）：
         * - 图片宽高在 `1..100000`
         * - 恰好 8 个坐标
         * - 每个点不越界
         * - 四角顺序一致且多边形非空
         *
         * @return 不满足任一条时返回 null
         */
        fun parse(raw: String, pictureWidth: Int, pictureHeight: Int): QuestionQuad? {
            if (pictureWidth !in 1..ProtocolProfile.LOC_MAX_COORDINATE) return null
            if (pictureHeight !in 1..ProtocolProfile.LOC_MAX_COORDINATE) return null

            val parts = raw.trim().split(ProtocolProfile.LOC_SEPARATOR)
            if (parts.size != ProtocolProfile.LOC_POINT_COUNT) return null

            val points = IntArray(ProtocolProfile.LOC_POINT_COUNT)
            for (i in points.indices) {
                val value = parts[i].trim().toDoubleOrNull() ?: return null
                if (!value.isFinite()) return null
                points[i] = Math.round(value).toInt()
            }

            val xs = intArrayOf(points[0], points[2], points[4], points[6])
            val ys = intArrayOf(points[1], points[3], points[5], points[7])
            for (i in 0 until 4) {
                if (xs[i] !in 0 until pictureWidth) return null
                if (ys[i] !in 0 until pictureHeight) return null
            }

            val left = xs.min()
            val top = ys.min()
            val right = xs.max()
            val bottom = ys.max()
            if (right - left < 1 || bottom - top < 1) return null

            // 四角顺序一致且多边形非空：用鞋带公式算有向面积，退化或自交为 0。
            val area = shoelace(xs, ys)
            if (area == 0L) return null

            return QuestionQuad(points, left, top, right, bottom)
        }

        private fun shoelace(xs: IntArray, ys: IntArray): Long {
            var sum = 0L
            for (i in 0 until 4) {
                val j = (i + 1) % 4
                sum += xs[i].toLong() * ys[j] - xs[j].toLong() * ys[i]
            }
            return sum / 2
        }
    }
}
