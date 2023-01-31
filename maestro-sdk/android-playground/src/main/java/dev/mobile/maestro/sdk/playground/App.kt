package dev.mobile.maestro.sdk.playground

import android.app.Application
import dev.mobile.maestro.sdk.MaestroSdk

class App : Application() {

    override fun onCreate() {
        super.onCreate()

        MaestroSdk.init("dima")
    }
}