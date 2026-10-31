package com.ruxi.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.ruxi.app.data.store.AppStore
import com.ruxi.app.ui.RuxiAppRoot
import com.ruxi.app.ui.theme.RuxiTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val store = AppStore.get(this)

        // 一次性把历史大图压掉。放后台线程，不拖慢启动；
        // 只有第一次装新版时会真的干活，之后靠标记位直接返回。
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { store.migrateCompressImages() }
        }

        setContent {
            val uiSettings by store.uiSettings.collectAsState()
            RuxiTheme(settings = uiSettings) {
                RuxiAppRoot(store)
            }
        }
    }
}
