package com.heikeji.phonesearch.ui.common

import java.util.Calendar

/** 首页问候：一句话主问候 + 一句陪伴性的副文案。 */
data class Greeting(val title: String, val subtitle: String)

/**
 * 按时段给出问候。
 *
 * 刻意不拼账号信息（服务端返回的 uname 就是手机号，不该出现在界面上）。
 * 每个时段准备了几种说法，按「日期 + 时段」选一条：同一天内保持稳定，
 * 隔天再来会换一句，不至于每次回到首页都一模一样。
 */
object Greetings {

    private enum class Period {
        LATE_NIGHT, DAWN, MORNING, FORENOON, NOON, AFTERNOON, DUSK, EVENING, NIGHT
    }

    private val VARIANTS: Map<Period, List<Greeting>> = mapOf(
        Period.LATE_NIGHT to listOf(
            Greeting("夜深啦，别忘了照顾好自己哦 🌙", "实在困就先睡，明天再战也不迟"),
            Greeting("这个点还没睡呀 😴", "把屏幕调暗一点，别太累着眼睛"),
        ),
        Period.DAWN to listOf(
            Greeting("天刚亮，早呀 🌅", "清晨脑子最清醒，适合啃硬骨头"),
            Greeting("起得真早 🌄", "新的一天，慢慢来就好"),
        ),
        Period.MORNING to listOf(
            Greeting("早上好呀 ☀️", "今天也要好好加油哦"),
            Greeting("早安 🌤️", "先把最不想做的那道题解决掉"),
        ),
        Period.FORENOON to listOf(
            Greeting("上午好 📖", "学一阵就起来动动，别一直坐着"),
            Greeting("快到中午啦 ⏰", "再坚持一下，马上就能吃饭了"),
        ),
        Period.NOON to listOf(
            Greeting("中午好 🍚", "先吃口饭再继续吧，胃比题重要"),
            Greeting("午安 😌", "眯一会儿，下午效率会更高"),
        ),
        Period.AFTERNOON to listOf(
            Greeting("下午好 ☕", "来杯水提提神"),
            Greeting("下午啦 🌞", "困的话站起来走两步，比硬撑管用"),
        ),
        Period.DUSK to listOf(
            Greeting("天色暗下来了 🌇", "记得把灯打开，别在暗处看题"),
            Greeting("傍晚好 🌆", "今天过得还顺利吗"),
        ),
        Period.EVENING to listOf(
            Greeting("晚上好 🌃", "今天辛苦了，慢慢来"),
            Greeting("晚上好呀 ✨", "把今天的错题过一遍，比刷新题有用"),
        ),
        Period.NIGHT to listOf(
            Greeting("夜深啦，别忘了照顾好自己哦 🌙", "早点休息，明天才有精神"),
            Greeting("该睡啦 😪", "剩下的明天再想，脑子休息好了才转得快"),
        ),
    )

    fun forNow(): Greeting {
        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val period = when (hour) {
            in 0..4 -> Period.LATE_NIGHT
            in 5..7 -> Period.DAWN
            in 8..10 -> Period.MORNING
            11 -> Period.FORENOON
            in 12..13 -> Period.NOON
            in 14..17 -> Period.AFTERNOON
            18 -> Period.DUSK
            in 19..22 -> Period.EVENING
            else -> Period.NIGHT
        }
        val variants = VARIANTS.getValue(period)
        val index = (calendar.get(Calendar.DAY_OF_YEAR) + period.ordinal) % variants.size
        return variants[index]
    }
}
