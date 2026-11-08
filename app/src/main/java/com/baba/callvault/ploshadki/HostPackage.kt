/*
 * ABP Call — Ploshadki.kz fork of CallVault. GPL-3.0-or-later with the Section 7 terms in LICENSE.
 */
package com.baba.callvault.ploshadki

import android.content.Context
import com.baba.callvault.BuildConfig

/**
 * The package the recorder runs inside. In ABP Call it equals the build's application id; when the
 * recorder is embedded as a library, `BuildConfig.APPLICATION_ID` would name the LIBRARY, not the host —
 * so code that needs the real package (Shizuku service component, own APK on the classpath, provider
 * authority) asks here. Set by [com.baba.callvault.RecorderRuntime.init]; processes without a Context
 * (the shell-uid daemon) receive it as a launch argument instead.
 */
object HostPackage {
    @Volatile
    var name: String = BuildConfig.APPLICATION_ID
        private set

    fun init(context: Context) {
        name = context.packageName
    }

    /** ContentProvider authority of [com.baba.callvault.server.RecorderBinderProvider] in package [pkg]. */
    fun recorderAuthority(pkg: String = name): String = "$pkg.recorder"
}
