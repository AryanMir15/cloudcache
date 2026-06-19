package com.lagradost.cloudstream3.ui.player

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.R

class CrunchyrollSplashActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var navigated = false

    private fun goToNext() {
        if (navigated) return
        navigated = true
        startActivity(Intent(this, com.lagradost.cloudstream3.ui.account.AccountSelectActivity::class.java))
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crunchyroll_splash)
        handler.postDelayed({ goToNext() }, 800)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
