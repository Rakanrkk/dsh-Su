package io.github.ccch1mneyyy.dsh_app.runtime

import android.content.Context
import android.util.Log
import com.topjohnwu.superuser.Shell
import io.github.ccch1mneyyy.dsh_app.phone.background.RootCommands

internal class RootShellSelection internal constructor(
    val shell: Shell,
    val useDefaultBinding: Boolean,
) {
    fun close() {
        if (useDefaultBinding) return
        RootShellSelector.close(shell)
    }
}

internal object RootShellSelector {
    private const val TAG = "RootShellSelector"

    internal fun close(shell: Shell) {
        Shell.EXECUTOR.execute {
            runCatching { shell.waitAndClose() }.onFailure {
                Log.w(TAG, "root shell close failed: ${it.javaClass.simpleName}")
                runCatching { shell.close() }
            }
        }
    }

    fun select(context: Context): RootShellSelection? {
        // A failed main-shell initialization is terminal: building another shell can
        // otherwise populate libsu's global main-shell cache with a non-root shell.
        val shell = Shell.getShell()
        if (shell.isRoot()) return RootShellSelection(shell, true)
        Log.i(TAG, "default root shell status=${shell.status}; trying absolute su candidates")
        for (path in RootCommands.suEntries.filter { it.startsWith('/') }) {
            val candidate = try {
                Shell.Builder.create()
                    .setContext(context.applicationContext)
                    .setCommands(path)
                    .build()
            } catch (error: Exception) {
                Log.w(TAG, "root shell candidate failed path=$path error=${error.javaClass.simpleName}")
                continue
            }
            if (candidate.isRoot()) {
                Log.i(TAG, "root shell candidate selected path=$path status=${candidate.status}")
                return RootShellSelection(candidate, false)
            }
            Log.w(TAG, "root shell candidate is not root path=$path status=${candidate.status}")
            close(candidate)
        }
        return null
    }
}
