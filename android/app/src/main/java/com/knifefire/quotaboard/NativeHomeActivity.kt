package com.knifefire.quotaboard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.knifefire.quotaboard.nativeui.HomeScreen
import com.knifefire.quotaboard.nativeui.Repository

/** 原生 Compose 核心页（分阶段重构第一阶段） */
class NativeHomeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = Repository(this)
        setContent { HomeScreen(repo) }
    }
}
