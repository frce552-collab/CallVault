/*
 * ABP Call — Ploshadki.kz fork of CallVault. GPL-3.0-or-later with the Section 7 terms in LICENSE.
 */
package com.baba.callvault.ploshadki

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.WindowManager

/**
 * The recorder's screens show phone numbers, contact names and transcripts. A host app that must keep them
 * out of screenshots, screen recording and the recents thumbnail sets `@bool/abp_secure_screens` to true;
 * then every recorder Activity gets FLAG_SECURE. The host's own screens are left to the host.
 */
object SecureScreens {

    /** Recorder screens are the ones whose class lives in the recorder's code (package com.baba.callvault). */
    internal fun isRecorderActivity(className: String): Boolean = className.startsWith(RECORDER_PACKAGE)

    private const val RECORDER_PACKAGE = "com.baba.callvault."

    fun install(app: Application) {
        val enabled = runCatching { app.resources.getBoolean(com.baba.callvault.R.bool.abp_secure_screens) }.getOrDefault(false)
        if (!enabled) return
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (isRecorderActivity(activity.javaClass.name)) {
                    activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
