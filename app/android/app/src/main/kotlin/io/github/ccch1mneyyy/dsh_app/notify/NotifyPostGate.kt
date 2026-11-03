package io.github.ccch1mneyyy.dsh_app.notify

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import io.github.ccch1mneyyy.dsh_app.BuildConfig
import io.github.ccch1mneyyy.dsh_app.phone.PhoneControl

/**
 * The one readiness gate every active chat/live notification shares. [ready] is a pure OS
 * readback (Android 13+ POST_NOTIFICATIONS), so Remote-only builds post without any local
 * runtime or root. [ensure] goes through the one root chain only when POST is actually
 * missing, and a cancelled waiter ([stillCurrent] false) withdraws its root attempt.
 */
internal object NotifyPostGate {
    fun ready(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun ensure(context: Context, stillCurrent: () -> Boolean = { true }): Boolean {
        if (!stillCurrent()) return false
        if (ready(context)) return true
        if (!BuildConfig.ROOT_RUNTIME) return false
        val background = PhoneControl.get(context).background
        return runCatching {
            background.prepareRootForStartup(stillCurrent) != null && background.notificationsGranted()
        }.getOrDefault(false)
    }
}
