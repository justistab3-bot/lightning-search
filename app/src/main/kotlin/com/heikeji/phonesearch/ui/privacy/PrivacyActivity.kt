package com.heikeji.phonesearch.ui.privacy

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import com.heikeji.phonesearch.R
import com.heikeji.phonesearch.ui.common.applySystemBarPadding

/** 隐私政策全文。首次启动的同意弹窗和设置里都指向这里。 */
class PrivacyActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_privacy)
        findViewById<android.view.View>(R.id.root)
            .applySystemBarPadding(horizontal = true)
        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }
    }

    companion object {
        fun newIntent(context: Context): Intent = Intent(context, PrivacyActivity::class.java)
    }
}
