package com.jslw.genz

import android.content.Context
import androidx.multidex.MultiDexApplication
import com.jslw.genz.data.LibraryDb
import com.jslw.genz.data.Prefs
import com.jslw.genz.skin.SkinManager

class GenZApp : MultiDexApplication() {
    override fun onCreate() {
        super.onCreate()
        app = this
        Prefs.init(this)
        LibraryDb.init(this)
        SkinManager.init(this)
    }

    companion object {
        lateinit var app: GenZApp
            private set
        val ctx: Context get() = app
    }
}
