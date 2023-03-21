package com.maestrornsdk

import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.Promise
import dev.mobile.maestro.sdk.MaestroSdk

class MaestroRnSdkModule(private val reactContext: ReactApplicationContext) :
  ReactContextBaseJavaModule(reactContext) {

  override fun getName(): String {
    return NAME
  }

  @ReactMethod
  fun setup(projectId: String, promise: Promise) {
    MaestroSdk.init(projectId)

    promise.resolve(true)
  }

  @ReactMethod
  fun mockServerUrl(baseUrl: String, promise: Promise) {
    promise.resolve(
      MaestroSdk.mockServer().url(baseUrl)
    )
  }

  companion object {
    const val NAME = "MaestroRnSdk"
  }
}
