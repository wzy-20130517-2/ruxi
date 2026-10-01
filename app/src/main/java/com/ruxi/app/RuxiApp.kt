package com.ruxi.app

import android.app.Application

/**
 * 应用入口。
 *
 * 这里不做重量级初始化 —— 数据层的懒加载交给 [com.ruxi.app.data.store.AppStore]，
 * 它内部按需初始化，避免冷启动时阻塞首帧。
 */
class RuxiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: RuxiApp
            private set
    }
}
