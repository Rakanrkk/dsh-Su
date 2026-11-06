package io.github.ccch1mneyyy.dsh_app.runtime

import java.io.File
import java.security.MessageDigest

/**
 * Cross-process exclusive writer lock mirroring the engine's own protocol
 * (`@deepseek-ai/dsh-atomic-write` `withFileLock`, including its claim-based
 * takeover of a lock left by an exited holder):
 *
 * - acquire = create `<target>.lock` exclusively with the holder pid + newline
 *   (mode 0600 where the filesystem honors it);
 * - an existing lock whose record is a valid pid of a process that is still
 *   alive keeps us waiting with 20 ms→200 ms capped backoff until [waitMs];
 * - a record that parses but whose pid no longer exists is taken over through
 *   a per-record claim file (`<lock>.takeover-<hash>`) created exclusively and
 *   re-verified under the claim, so a racing takeover deletes the stale lock
 *   only after proving the record did not change; deleting the stale lock is
 *   not acquiring it — after a takeover the loop competes again for a fresh
 *   exclusive create, so the critical section is entered only while holding
 *   the lock;
 * - a record that does not parse (empty or garbage, e.g. a crash between the
 *   exclusive create and the pid write) is treated as a live holder — wait, never
 *   steal — matching the engine's `parseInt` semantics;
 * - the lock is always removed in `finally`; the block is only ever entered
 *   through a successful exclusive create and a live holder's lock is never
 *   stolen, so the release cannot remove a later owner's lock.
 *
 * The pid liveness probe is `kill(pid, 0)` (android.system.Os on device):
 * only ESRCH proves the holder is gone. A delivered signal means alive and
 * every other outcome — EPERM on a hidepid-hidden or root-owned holder, any
 * unexpected failure — fails closed: the holder is treated as alive and its
 * lock is never stolen. The app's Kotlin process is not uid 0 (only the
 * transient SuFile shell jobs are root), so /proc existence seen from the
 * app is not proof of death. There is deliberately no second probe and no
 * /proc fallback; callers can inject a kill adapter to classify errnos
 * directly (JVM unit tests do exactly that).
 */
internal object ProfileLock {
    /** Matches [dsh_app.runtime.LastKnownGood] restore's previous 3 s budget. */
    const val DEFAULT_WAIT_MS = 3000L
    private const val RETRY_INITIAL_MS = 20L
    private const val RETRY_MAX_MS = 200L
    private const val MAX_PID = 2147483647L

    /** Linux errno for "no such process" - the only proof of death (kernel ABI, stable).
     *  Compared literally: android.jar stub constants are not distinguishable on the JVM. */
    private const val ESRCH = 3

    fun lockPathFor(target: File): File = File(target.path + ".lock")

    fun <T> withLock(
        files: RuntimeFileAccess,
        target: File,
        busyConfig: String,
        waitMs: Long = DEFAULT_WAIT_MS,
        pid: () -> Long = ::currentPid,
        alive: (Long) -> Boolean = { isAlive(it) },
        sleep: (Long) -> Unit = Thread::sleep,
        block: () -> T,
    ): T {
        val lock = lockPathFor(target)
        val deadline = System.currentTimeMillis() + waitMs
        var backoff = RETRY_INITIAL_MS
        while (true) {
            if (files.createExclusive(lock, "${pid()}\n")) break
            val record = runCatching { files.readText(lock) }.getOrNull()
            if (record != null && holderExited(record, pid(), alive)) {
                // Deleting the stale lock is not acquiring it: the takeover only
                // removes the dead holder's lock; the loop then re-enters the
                // contention above, so the block is entered only through a
                // successful exclusive create and the holder always owns the
                // lock it removes in finally.
                takeOverExitedLock(files, lock, record, pid())
            }
            if (System.currentTimeMillis() >= deadline) throw IllegalStateException(busyConfig)
            sleep(backoff)
            backoff = (backoff * 2).coerceAtMost(RETRY_MAX_MS)
        }
        try {
            return block()
        } finally {
            // Only a createExclusive winner reaches the block and a live
            // holder's lock is never stolen, so this lock is ours and the
            // release cannot remove a later owner's lock.
            files.delete(lock, recursive = false)
        }
    }

    /** Holder is gone only when the record parses to a pid that no longer exists. */
    fun holderExited(record: String, selfPid: Long, alive: (Long) -> Boolean): Boolean {
        val pid = parsePid(record) ?: return false
        if (pid == selfPid) return false
        return !alive(pid)
    }

    private fun parsePid(record: String): Long? {
        val text = record.trim()
        if (!Regex("^\\d+$").matches(text)) return null
        val pid = text.toLongOrNull() ?: return null
        if (pid <= 0 || pid > MAX_PID) return null
        return pid
    }

    /**
     * Claims an exited holder's lock: create `<lock>.takeover-<hash>` exclusively,
     * re-read the lock record under the claim and remove the stale lock only when
     * the record is unchanged and its holder is still gone. The claim is removed
     * even when the takeover loses the race.
     */
    private fun takeOverExitedLock(
        files: RuntimeFileAccess,
        lock: File,
        record: String,
        pid: Long,
    ): Boolean {
        val digest = MessageDigest.getInstance("SHA-256").digest(record.toByteArray(Charsets.UTF_8))
        val claim = File(lock.path + ".takeover-" + digest.take(8).joinToString("") { "%02x".format(it) })
        if (!files.createExclusive(claim, "$pid\n")) return false
        try {
            val current = runCatching { files.readText(lock) }.getOrNull()
            return current == record && files.delete(lock, recursive = false)
        } finally {
            files.delete(claim, recursive = false)
        }
    }

    /** pid of the current process; local JVM tests get /proc/self, devices get Process.myPid(). */
    internal fun currentPid(): Long = try {
        android.os.Process.myPid().toLong()
    } catch (_: Throwable) {
        Regex("^(\\d+)").find(File("/proc/self/stat").readText())?.groupValues?.get(1)?.toLongOrNull() ?: -1L
    }

    /**
     * Only `ESRCH` from `kill(pid, 0)` proves death. A delivered signal means
     * alive; EPERM (a hidepid-hidden or root-owned holder the app cannot
     * inspect) and any other errno or probe failure mean alive — never steal
     * a lock whose holder might still be running. [kill] maps a pid to the
     * errno of `kill(pid, 0)` (null = delivered) and is injectable so JVM
     * tests can classify each errno without Android stubs.
     */
    internal fun isAlive(pid: Long, kill: (Long) -> Int? = ::killErrno): Boolean {
        if (pid <= 0) return false
        val errno = try {
            kill(pid)
        } catch (_: Throwable) {
            return true
        }
        return errno == null || errno != ESRCH
    }

    /**
     * null = signal delivered (alive); otherwise the errno of `kill(pid, 0)`.
     * Any non-Errno failure (android.jar stub on the host JVM, OEM quirks)
     * propagates: [isAlive] treats a probe it cannot classify as an alive
     * holder. There is deliberately no second probe and no /proc fallback —
     * only `ESRCH` from the real kernel proves death.
     */
    private fun killErrno(pid: Long): Int? = try {
        android.system.Os.kill(pid.toInt(), 0)
        null
    } catch (e: android.system.ErrnoException) {
        e.errno
    }
}
