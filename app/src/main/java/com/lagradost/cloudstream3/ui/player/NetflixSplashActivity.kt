package com.lagradost.cloudstream3.ui.player

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.airbnb.lottie.LottieAnimationView
import com.lagradost.cloudstream3.R

class NetflixSplashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_netflix_splash)

        val lottie = findViewById<LottieAnimationView>(R.id.lottie_splash)
        lottie.addAnimatorListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                startActivity(Intent(this@NetflixSplashActivity, com.lagradost.cloudstream3.ui.account.AccountSelectActivity::class.java))
                finish()
            }
        })
    }
}
