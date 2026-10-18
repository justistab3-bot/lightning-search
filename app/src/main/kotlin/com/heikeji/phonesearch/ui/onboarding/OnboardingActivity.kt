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

/** 首次使用选择年级；账号可稍后登录，搜题按服务端返回的要求处理。 */
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
