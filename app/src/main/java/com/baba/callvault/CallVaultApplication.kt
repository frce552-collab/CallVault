/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present kitsumed (Med)
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault

import android.app.Application

/**
 * CallVaultApplication is run when the app process is created. Can be seen as the very first entry point of the app.
 */
class CallVaultApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // ABP Call: the start-up work lives in [RecorderRuntime] so a host app can run the same.
        RecorderRuntime.init(this)
    }
}
