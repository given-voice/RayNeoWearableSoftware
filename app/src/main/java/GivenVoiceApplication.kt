package com.givenvoice.wearable

import android.app.Application
import com.ffalcon.mercury.android.sdk.MercurySDK

/**
 * Must be registered in AndroidManifest.xml as android:name=".GivenVoiceApplication"
 * on the <application> tag. This runs before any Activity and initializes
 * the RayNeo AR SDK — required before BaseMirrorActivity will work.
 */
class GivenVoiceApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        MercurySDK.init(this)
    }
}
