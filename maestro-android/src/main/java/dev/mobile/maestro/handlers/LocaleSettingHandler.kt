package dev.mobile.maestro.handlers

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.InvocationTargetException
import java.util.*

class LocaleSettingHandler(context: Context) : AbstractSettingHandler(context, listOf(CHANGE_CONFIGURATION)) {
    fun setLocale(locale: Locale) {
        if (hasPermissions()) {
            setLocaleWith(locale)
        }
    }

    @SuppressLint("PrivateApi")
    @Throws(
        ClassNotFoundException::class,
        NoSuchMethodException::class,
        InvocationTargetException::class,
        IllegalAccessException::class,
        NoSuchFieldException::class
    )
    private fun setLocaleWith(locale: Locale) {
        var activityManagerNativeClass = Class.forName("android.app.ActivityManagerNative")
        val methodGetDefault = activityManagerNativeClass.getMethod("getDefault")
        methodGetDefault.isAccessible = true
        val amn = methodGetDefault.invoke(activityManagerNativeClass)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // getConfiguration moved from ActivityManagerNative to ActivityManagerProxy
            activityManagerNativeClass = Class.forName(amn.javaClass.name)
        }

        val methodGetConfiguration = activityManagerNativeClass.getMethod("getConfiguration")
        methodGetConfiguration.isAccessible = true
        val config = methodGetConfiguration.invoke(amn) as Configuration
        val configClass: Class<*> = config.javaClass
        val f = configClass.getField("userSetLocale")
        f.setBoolean(config, true)
        config.locale = locale
        config.setLayoutDirection(locale)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            HiddenApiBypass.invoke(
                activityManagerNativeClass,
                amn,
                "updateConfiguration",
                config
            )
        } else {
            val methodUpdateConfiguration = activityManagerNativeClass.getMethod(
                "updateConfiguration",
                Configuration::class.java
            )
            methodUpdateConfiguration.isAccessible = true
            methodUpdateConfiguration.invoke(amn, config)
        }
    }

    override fun setState(state: Boolean): Boolean {
        return false
    }

    override val settingDescription: String = "locale"

    companion object {
        private const val CHANGE_CONFIGURATION = "android.permission.CHANGE_CONFIGURATION"
    }
}

