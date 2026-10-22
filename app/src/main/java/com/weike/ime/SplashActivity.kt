package com.weike.ime

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 开屏页：全屏品牌启动图，短暂停留后进入主页。 */
class SplashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            ImageView(this).apply {
                setImageResource(R.drawable.splash_love)
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = "恋爱键盘启动图"
            },
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        lifecycleScope.launch {
            delay(1800)
            startActivity(Intent(this@SplashActivity, MainActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            finish()
        }
    }
}
