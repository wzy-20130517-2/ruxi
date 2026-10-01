package com.ruxi.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.ruxi.app.data.store.AppStore
import com.ruxi.app.ui.RuxiAppRoot
import com.ruxi.app.ui.theme.RuxiTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val store = AppStore.get(this)
        setContent {
            val uiSettings by store.uiSettings.collectAsState()
            RuxiTheme(themeMode = uiSettings.themeMode) {
                RuxiAppRoot(store)
            }
        }
    }
}
