package com.lagradost.cloudstream3.ui.player

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.airbnb.lottie.LottieAnimationView
import com.lagradost.cloudstream3.R

class NetflixSplashActivity : AppCompatActivity() {

    private var navigated = false
    private val handler = Handler(Looper.getMainLooper())

    private fun goToNext() {
        if (navigated) return
        navigated = true
        startActivity(Intent(this, com.lagradost.cloudstream3.ui.account.AccountSelectActivity::class.java))
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_netflix_splash)

        val lottie = findViewById<LottieAnimationView>(R.id.lottie_splash)
        lottie.speed = 1.3f
        lottie.addAnimatorUpdateListener { animation ->
            val progress = animation.animatedFraction
            if (progress > 0.55f) {
                val t = ((progress - 0.55f) / 0.45f).coerceIn(0f, 1f)
                lottie.speed = 1.3f - (0.9f * t)
            }
        }
        lottie.addAnimatorListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                goToNext()
            }
        })

        handler.postDelayed({ goToNext() }, 2500)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
