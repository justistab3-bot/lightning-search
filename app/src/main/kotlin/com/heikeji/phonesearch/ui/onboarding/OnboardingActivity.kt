package com.heikeji.phonesearch.ui.onboarding

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.heikeji.phonesearch.R
import com.heikeji.phonesearch.data.UserPrefs
import com.heikeji.phonesearch.protocol.aiwriting.AiWritingRequest
import com.heikeji.phonesearch.ui.common.applySystemBarPadding
import com.heikeji.phonesearch.ui.home.HomeActivity
import com.heikeji.phonesearch.ui.login.LoginActivity

/**
 * 首次启动引导：选年级 + 账号说明。
 *
 * **为什么需要这一步**：实测确认搜题、整页搜题、快问 AI、AI 作文**都不需要登录**
 * （见 `SearchProbeTest`），所以登录从「必须」变成了「可选」。但年级是这些接口的必传参数，
 * 以前从登录会话里取，现在没会话了就得让用户直接给一个。
 *
 * 账号那一栏是**如实告知**，不是推销：不登录确实能用，登录的价值在于搜题更稳、不容易触发风控。
 * 用户点了「先不登录」就真的不再打扰他。
 */
class OnboardingActivity : AppCompatActivity() {

    private lateinit var gradeGroup: ChipGroup
    private var selectedGrade = AiWritingRequest.DEFAULT_GRADE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)
        findViewById<View>(R.id.root)
            .applySystemBarPadding(top = true, bottom = true, horizontal = true)

        selectedGrade = UserPrefs.grade(this)
        gradeGroup = findViewById(R.id.gradeGroup)
        buildGradeChips()

        findViewById<MaterialButton>(R.id.loginButton).setOnClickListener {
            // 先把年级定下来，再交给登录页；登录成功后 LoginActivity 会进首页
            UserPrefs.setGrade(this, selectedGrade)
            UserPrefs.setOnboarded(this, true)
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }

        findViewById<MaterialButton>(R.id.skipButton).setOnClickListener {
            UserPrefs.setGrade(this, selectedGrade)
            UserPrefs.setOnboarded(this, true)
            startActivity(Intent(this, HomeActivity::class.java))
            finish()
        }
    }

    /** 12 个年级做成可单选的气泡，比下拉框更直观，也更好按。 */
    private fun buildGradeChips() {
        // 循环变量不能叫 id —— 会在 apply 里遮蔽 Chip 自己的 id 属性
        for ((gradeId, label) in AiWritingRequest.GRADES) {
            val chip = Chip(this).apply {
                text = label
                isCheckable = true
                isCheckedIconVisible = false
                setOnClickListener { selectedGrade = gradeId }
            }
            chip.isChecked = gradeId == selectedGrade
            gradeGroup.addView(chip)
        }
    }

    companion object {
        fun newIntent(context: Context): Intent = Intent(context, OnboardingActivity::class.java)
    }
}
