package io.github.ccch1mneyyy.dsh_app.phone.a11y

import io.github.ccch1mneyyy.dsh_app.phone.PhoneAction

/** The narrow Root input seam for the main screen; the owner supplies [io.github.ccch1mneyyy.dsh_app.phone.PhoneRegistry.rootMainInput]. */
fun interface RootMainInput {
    data class Request(
        val displayId: Int,
        val x: Int? = null,
        val y: Int? = null,
        val sx: Int? = null,
        val sy: Int? = null,
        val ex: Int? = null,
        val ey: Int? = null,
        val long: Boolean = false,
        val key: PhoneAction.GlobalKey? = null,
    )

    /** True only when the system accepted and completed the explicit display0 event. */
    fun send(request: Request): Boolean
}

internal class RootMainActuator(private val input: RootMainInput?) {
    sealed interface Result {
        data object Unavailable : Result
        data class Rejected(val reason: String) : Result
        data object Accepted : Result
    }

    fun tapXY(x: Int, y: Int, long: Boolean): Result {
        if (input == null) return Result.Unavailable
        if (Thread.currentThread().isInterrupted) return Result.Rejected("已停止，没有发送 Root 输入。")
        val accepted = try {
            input.send(RootMainInput.Request(displayId = 0, x = x, y = y, long = long))
        } catch (_: Exception) {
            false
        }
        return if (accepted) Result.Accepted else Result.Rejected("Root 系统没有接受这次输入。")
    }

    fun key(key: PhoneAction.GlobalKey): Result {
        if (input == null) return Result.Unavailable
        if (Thread.currentThread().isInterrupted) return Result.Rejected("已停止，没有发送 Root 输入。")
        val accepted = try {
            input.send(RootMainInput.Request(displayId = 0, key = key))
        } catch (_: Exception) {
            false
        }
        return if (accepted) Result.Accepted else Result.Rejected("Root 系统没有执行这个按键。")
    }

    fun swipe(sx: Int, sy: Int, ex: Int, ey: Int): Result {
        if (input == null) return Result.Unavailable
        if (Thread.currentThread().isInterrupted) return Result.Rejected("已停止，没有发送 Root 输入。")
        val accepted = try {
            input.send(RootMainInput.Request(displayId = 0, sx = sx, sy = sy, ex = ex, ey = ey))
        } catch (_: Exception) {
            false
        }
        return if (accepted) Result.Accepted else Result.Rejected("Root 系统没有接受这次滚动。")
    }
}
