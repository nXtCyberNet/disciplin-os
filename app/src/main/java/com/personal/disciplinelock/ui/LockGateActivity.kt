package com.personal.disciplinelock.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.viewModels

/**
 * Clean Missions & Math Lock Activity.
 */
class LockGateActivity : ComponentActivity() {

    private val viewModel: LockGateViewModel by viewModels()

    companion object {
        private const val TAG = "LockGateActivity"
        const val EXTRA_NAVIGATE_TAB = "extra_navigate_tab"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "LockGateActivity onCreate")

        setupLockScreenFlags()
        handleIntentNavigation(intent)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val state = viewModel.uiState.value
                if (state.selectedTab != LockTab.MISSIONS) {
                    viewModel.selectTab(LockTab.MISSIONS)
                } else {
                    isEnabled = false
                    finish()
                }
            }
        })

        setContent {
            LockGateScreen(viewModel = viewModel)
        }
    }

    private fun setupLockScreenFlags() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntentNavigation(intent)
    }

    private fun handleIntentNavigation(intent: Intent?) {
        val targetTab = intent?.getStringExtra(EXTRA_NAVIGATE_TAB)
        when (targetTab) {
            "MATH_ENGINE" -> viewModel.selectTab(LockTab.MATH_ENGINE)
            "MISSIONS" -> viewModel.selectTab(LockTab.MISSIONS)
            "SETTINGS" -> viewModel.selectTab(LockTab.SETTINGS)
        }
    }
}
