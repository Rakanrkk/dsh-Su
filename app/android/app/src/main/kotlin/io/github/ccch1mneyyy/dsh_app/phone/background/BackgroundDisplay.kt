package io.github.ccch1mneyyy.dsh_app.phone.background

import android.os.Build
import android.os.Bundle
import io.github.ccch1mneyyy.dsh_app.phone.ActionResult
import io.github.ccch1mneyyy.dsh_app.phone.Availability
import io.github.ccch1mneyyy.dsh_app.phone.CollectResult
import io.github.ccch1mneyyy.dsh_app.phone.PhoneAction
import io.github.ccch1mneyyy.dsh_app.phone.PhoneDriver
import io.github.ccch1mneyyy.dsh_app.phone.PhoneRegistry
import io.github.ccch1mneyyy.dsh_app.phone.PhoneScreen
import io.github.ccch1mneyyy.dsh_app.phone.ScreenSnapshot
import io.github.ccch1mneyyy.dsh_app.phone.TargetInfo

/**
 * BACKGROUND [PhoneDriver] (screen ids `b<n>`): reads and drives the trusted virtual display
 * through whichever privileged channel is live (Shizuku or self-ADB). Registered in
 * [io.github.ccch1mneyyy.dsh_app.phone.PhoneRegistry].backgroundDriver only while a task uses it.
 * All calls are serialised by the bridge; this class never retries a dispatched action.
 */
class BackgroundDisplay(
    private val owner: String,
    private val channelProvider: () -> PrivilegedChannel?,
    private val releaseChannelProvider: () -> PrivilegedChannel?,
) : PhoneDriver {

    override val screen: PhoneScreen = PhoneScreen.BACKGROUND

    /** The real cross-privileged owner token; visible to [BackgroundControl] for
     *  release compensation after an unconfirmed release. */
    internal val ownerId: String = owner

    @Volatile private var currentPackage: String? = null
    private var counter = 0
    private var lastScreenId: String? = null
    private var lastElements: List<Element> = emptyList()
    private var previousLabels: Set<String> = emptySet()
    /** The display and application window the current snapshot was read from; a write
     *  carries them back so the core can refuse anything read elsewhere. */
    private var lastDisplayId: Int? = null
    private var lastWindowId: Int? = null
    /** The random observation ticket the current complete snapshot was minted with; every
     *  action echoes it and the core refuses anything else before injecting. Null after
     *  partial reads, failures, releases and dispatches with unknown outcome. */
    @Volatile private var observationTicket: String? = null

    override fun availability(): Availability {
        if (Build.VERSION.SDK_INT < BackgroundPolicy.MIN_SDK)
            // l10n:keep agent screen protocol
            return Availability(false, "不打扰模式需要 Android 14 或更高版本")
        val channel = channelProvider()
        if (channel == null || !channel.alive())
            // l10n:keep agent screen protocol
            return Availability(false, "不打扰模式的特权通道未就绪")
        return Availability(true)
    }

    private fun channel(): PrivilegedChannel =
        // l10n:keep agent screen protocol
        channelProvider() ?: throw IllegalStateException("背景屏通道未就绪")

    override fun open(packageName: String): ActionResult {
        val request = Bundle().apply {
            putString(Proto.OP, Proto.OP_LAUNCH)
            putString(Proto.OWNER, owner)
            putString(Proto.PKG, packageName)
            putString(Proto.CLIENT_REV, Proto.REVISION)
            putInt(Proto.SHORT_SIDE, 720)
            putInt(Proto.WANT_IMAGE, 0)
        }
        val response = try {
            channel().execute(request)
        } catch (e: Exception) {
            // The wire died mid-open: the cached observation for the previous app is
            // no longer trustworthy — drop it (the confirmed package stays), and let
            // the caller see the real failure. The open itself is never replayed.
            onOpenSendFailed(e)
        }
        // The target app is claimed only once the launch is confirmed. A failed or
        // thrown-away open keeps the previously confirmed package (or none), so no
        // later action ever addresses a package that never opened; the observation
        // cache still dies through toResult's existing failure path.
        currentPackage = targetAfterOpen(response.getBoolean(Proto.OK), currentPackage, packageName)
        // l10n:keep agent screen protocol
        val note = if (response.getBoolean(Proto.IME_HIDE_FAILED))
            "（键盘隐藏失败：后台屏可能弹出输入法，请重新观察确认）" else ""
        return toResult(response, "已在后台屏打开$note")
    }

    override fun observe(detail: Boolean, image: Boolean): ScreenSnapshot {
        val pkg = currentPackage ?: return idleSnapshot()
        val request = Bundle().apply {
            putString(Proto.OP, Proto.OP_OBSERVE)
            putString(Proto.OWNER, owner)
            putString(Proto.PKG, pkg)
            putString(Proto.CLIENT_REV, Proto.REVISION)
            putBoolean(Proto.DETAIL, detail)
            putBoolean(Proto.WANT_IMAGE, image)
        }
        val response = channel().execute(request)
        requireObservation(response.getBoolean(Proto.OK), response.getString(Proto.ERROR), response.getString(Proto.ERROR_DETAIL))
        return snapshot(response, markNew = false)
    }

    /** A failed read is not an empty snapshot; old element numbers are no longer actionable. */
    internal fun requireObservation(ok: Boolean, code: String?, detail: String?) {
        if (ok) return
        invalidateObservation()
        throw IllegalStateException(Reasons.of(code, detail))
    }

    /** Old element numbers stop resolving and the window binding is dropped; the next
     *  action needs a fresh observation (the display itself is unchanged). The observation
     *  ticket dies with them: a partial or failed read never leaves a writable ticket. */
    internal fun invalidateObservation() {
        lastScreenId = null
        lastElements = emptyList()
        previousLabels = emptySet()
        lastWindowId = null
        observationTicket = null
    }

    /** The exception path behind [open]: a request that never got an answer means the
     *  cached observation may outlive the screen it was read from, so it dies here —
     *  through the same [invalidateObservation] path as every other failure — before
     *  the real error surfaces. The confirmed package is untouched and the caller
     *  decides whether to retry; the driver never replays the open itself. */
    internal fun onOpenSendFailed(e: Exception): Nothing {
        invalidateObservation()
        throw e
    }

    override fun collect(screenId: String, listElementId: Int, maxPages: Int, until: String?): CollectResult {
        val seen = LinkedHashSet<String>()
        var pages = 0
        var stop = "listEnd"
        var last: ScreenSnapshot? = null
        while (pages < maxPages.coerceIn(1, 30)) {
            val snap = observe(detail = false, image = false)
            last = snap
            val before = seen.size
            for (e in lastElements) if (e.label.isNotEmpty()) seen.add(e.label)
            pages++
            if (until != null && seen.any { it.contains(until) }) { stop = "untilMatched"; break }
            if (seen.size == before) { stop = "listEnd"; break }
            if (pages >= maxPages) { stop = "pageLimit"; break }
            perform(PhoneAction.Scroll(screenId, listElementId, PhoneAction.Direction.DOWN))
        }
        return CollectResult(seen.toList(), pages, stop, last)
    }

    override fun target(screenId: String, elementId: Int): TargetInfo? {
        if (screenId != lastScreenId) return null
        val e = lastElements.getOrNull(elementId - 1) ?: return null
        return TargetInfo(
            screenId = screenId,
            elementId = elementId,
            role = e.role,
            label = e.label,
            packageName = e.packageName,
            appLabel = null,
            isPassword = e.isPassword,
            isSecretField = e.isSecretField,
            isEditable = e.isEditable,
            viewId = e.viewId,
        )
    }

    override fun perform(action: PhoneAction): ActionResult {
        // l10n:keep agent screen protocol
        val pkg = currentPackage ?: return ActionResult(false, "后台屏还没有打开应用")
        if (action is PhoneAction.Key && action.key != PhoneAction.GlobalKey.BACK) {
            // l10n:keep agent screen protocol
            return ActionResult(false, "后台屏不支持该系统键，未执行；请用返回或打开指定应用")
        }
        // 观测票据先于一切：没有当前完整观测的票据，任何动作都不发送（确知未派发）。
        val ticket = observationTicket
        if (ticket == null) {
            return ActionResult(false, Reasons.of("observationStale", null))
        }
        // 身份核对先于任何请求构建：裸元素编号永远不进入发往后台屏的请求。
        var expect: ElementExpect? = null
        when (action) {
            is PhoneAction.Tap -> expect = expectFor(action.elementId)
            is PhoneAction.Type -> expect = expectFor(action.elementId)
            else -> {}
        }
        if (expect == null && (action is PhoneAction.Tap || action is PhoneAction.Type)) {
            return ActionResult(
                // l10n:keep agent screen protocol
                false, "目标编号不在当前快照里：请重新观察后台屏，不要按编号直接操作",
            )
        }
        val request = Bundle().apply {
            putString(Proto.OWNER, owner)
            putString(Proto.PKG, pkg)
            putString(Proto.CLIENT_REV, Proto.REVISION)
            putString(Proto.OBS_TICKET, ticket)
        }
        when (action) {
            is PhoneAction.Tap -> {
                request.putString(Proto.OP, Proto.OP_TAP)
                request.putInt(Proto.ELEMENT, action.elementId)
                request.putBoolean(Proto.LONG, action.long)
                putExpect(request, expect!!)
            }
            is PhoneAction.Type -> {
                request.putString(Proto.OP, Proto.OP_TEXT)
                request.putInt(Proto.ELEMENT, action.elementId)
                putExpect(request, expect!!)
                request.putString(Proto.TEXT, action.text)
                request.putBoolean(Proto.SUBMIT, action.submit)
                // 解除限制 › 密码和验证码: the privileged side refuses such inputs unless told so.
                request.putBoolean(Proto.ALLOW_SECRET, PhoneRegistry.unlocks().secretFields)
            }
            is PhoneAction.Scroll -> {
                request.putString(Proto.OP, Proto.OP_SCROLL)
                request.putString(Proto.DIR, action.direction.name.lowercase())
            }
            is PhoneAction.Key -> {
                request.putString(Proto.OP, Proto.OP_KEY)
                request.putString(Proto.DIR, "back")
            }
            // l10n:keep agent screen protocol
            is PhoneAction.TapXY -> return ActionResult(false, "后台屏按元素编号操作，不用坐标")
        }
        val response = try {
            channel().execute(request)
        } catch (e: Exception) {
            // transport 边界：通道抛出时动作是否已送达未知——保守失效，绝不返回可重放的成功。
            invalidateObservation()
            // l10n:keep agent screen protocol
            return ActionResult(false, "后台屏通道异常，动作是否已送达未知；请重新观察，不要重复未确认的动作")
        }
        // l10n:keep agent screen protocol
        return toResult(response, "已在后台屏执行")
    }

    override fun foregroundPackage(): String? = currentPackage

    /** Releases the display and reports whether the privileged side confirmed it.
     *  False means the caller must surface a pending cleanup — never assume the
     *  display is gone. */
    fun release(): Boolean {
        val released = runCatching {
            // Cleanup may use an existing session, but must not connect or start a new process.
            releaseChannelProvider()?.execute(Bundle().apply {
                putString(Proto.OP, Proto.OP_RELEASE); putString(Proto.OWNER, owner)
            })
        }.getOrNull()?.getBoolean(Proto.RELEASED) == true
        currentPackage = null
        lastScreenId = null
        lastElements = emptyList()
        previousLabels = emptySet()
        lastDisplayId = null
        lastWindowId = null
        observationTicket = null
        return released
    }

    /** The full observed identity of one element, sent with every tap and type so the
     *  privileged side can refuse a stale number instead of hitting whatever sits there now. */
    internal data class ElementExpect(
        val displayId: Int,
        val windowId: Int,
        val role: String,
        val label: String,
        val viewId: String?,
    )

    /** Null: the snapshot no longer knows this number, the display, or the window — the
     *  action must not be sent at all, certainly not as a bare index. */
    internal fun expectFor(elementId: Int): ElementExpect? {
        val displayId = lastDisplayId ?: return null
        val windowId = lastWindowId ?: return null
        val e = lastElements.getOrNull(elementId - 1) ?: return null
        return ElementExpect(displayId, windowId, e.role, e.label, e.viewId)
    }

    private fun putExpect(request: Bundle, expect: ElementExpect) {
        for ((key, value) in expectFields(expect)) {
            if (value is Int) request.putInt(key, value) else request.putString(key, value as String?)
        }
    }

    private fun toResult(response: Bundle, okMessage: String): ActionResult {
        val ok = response.getBoolean(Proto.OK)
        if (invalidatesCache(
                ok = ok,
                dispatched = response.getBoolean(Proto.DISPATCHED),
                errorCode = response.getString(Proto.ERROR),
            )
        ) {
            // 保守失效：请求已发出后任何失败——stale 码、settling 被取消的
            // cancelled/timeout/deviceUnavailable，甚至 locked——都让旧快照与票据作废；
            // 看不见的屏幕上不重放动作。
            invalidateObservation()
        }
        val message = if (ok) okMessage else Reasons.of(response.getString(Proto.ERROR), response.getString(Proto.ERROR_DETAIL))
        val snap = if (response.containsKey(Proto.COUNT) || response.getBoolean(Proto.SECURE))
            snapshot(response, markNew = true) else null
        return ActionResult(ok, message, snap)
    }

    private fun snapshot(response: Bundle, markNew: Boolean): ScreenSnapshot {
        val id = "b${++counter}"
        val pkg = response.getString(Proto.FG_PKG) ?: currentPackage
        val appLabel = response.getString(Proto.APP_LABEL)
        val imageOmitted = response.getBoolean(Proto.IMAGE_OMITTED)
        if (response.getBoolean(Proto.SECURE)) {
            invalidateObservation()
            // l10n:keep agent screen protocol
            val text = "屏幕 $id · 后台屏 · 受保护页面（支付或密码），已交还用户"
            return ScreenSnapshot(id, text, appLabel, pkg, secure = true)
        }
        val partial = response.getBoolean(Proto.PARTIAL)
        val truncated = response.getBoolean(Proto.TRUNCATED)
        val total = response.getInt(Proto.TOTAL, -1).takeIf { it > 0 }
        val ticket = response.getString(Proto.OBS_TICKET)
        if (missingTicketViolation(response.containsKey(Proto.COUNT), partial, ticket)) {
            // A complete read from a rev-2 core always carries a fresh ticket; absence
            // means the peer is not rev-2 — nothing here may be acted on.
            invalidateObservation()
            // l10n:keep agent screen protocol
            return ScreenSnapshot(id, "屏幕 $id · 后台屏 · 协议版本不匹配（响应缺少观测票据）；请重启不打扰模式后再试", appLabel, pkg)
        }
        return applyObservation(
            id = id,
            partial = partial,
            truncated = truncated,
            total = total,
            ticket = ticket,
            elements = buildElements(response, markNew),
            displayId = response.getInt(Proto.DISPLAY_ID, -1).takeIf { it >= 0 },
            windowId = response.getInt(Proto.WINDOW, -1).takeIf { it >= 0 },
            appLabel = appLabel,
            pkg = pkg,
            poor = response.getBoolean(Proto.POOR),
            portrait = response.getBoolean(Proto.PORTRAIT, true),
            keyboardUp = if (response.getBoolean(Proto.KEYBOARD_UNKNOWN)) null
                else response.getBoolean(Proto.KEYBOARD_UP, false),
            imageOmitted = imageOmitted,
            image = if (imageOmitted) null else response.getByteArray("imageBytes"),
            imageMime = response.getString("imageMime"),
        )
    }

    /** Decides what a response's observation means for the cached numbers. A partial read
     *  with no elements is not an empty screen: nothing is known, so nothing stays usable.
     *  A partial read with elements (scan budget or missing-child cut) keeps those elements
     *  for display only — with no ticket and no window binding none of them is operable.
     *  A complete read binds display, window and a fresh ticket; a display-only truncation
     *  says so without touching operability. */
    internal fun applyObservation(
        id: String,
        partial: Boolean,
        truncated: Boolean,
        total: Int?,
        ticket: String?,
        elements: List<Element>,
        displayId: Int?,
        windowId: Int?,
        appLabel: String?,
        pkg: String?,
        poor: Boolean,
        portrait: Boolean,
        keyboardUp: Boolean?,
        imageOmitted: Boolean,
        image: ByteArray?,
        imageMime: String?,
    ): ScreenSnapshot {
        if (partial && elements.isEmpty()) {
            invalidateObservation()
            // l10n:keep agent screen protocol
            val text = "屏幕 $id · 后台屏 · 读取未完成（当前界面未知）；请重新观察，不要依赖旧编号，也不要重复未确认的动作"
            return ScreenSnapshot(id, text, appLabel, pkg, secure = false)
        }
        if (partial) {
            // 采集不完整：元素仅供查看；没有票据、没有窗口绑定，任何编号都不可操作。
            lastElements = elements
            lastScreenId = id
            lastWindowId = null
            observationTicket = null
            var text = BackgroundTree.render(id, appLabel, portrait, keyboardUp, elements, background = true, poorTree = poor)
            // l10n:keep agent screen protocol
            text += "\n（本页控件树读取不完整：以下编号仅用于查看，暂不可操作；请重新观察，不要按这些编号执行动作）"
            if (imageOmitted) {
                // l10n:keep agent screen protocol
                text += "\n（本页含敏感输入或控件树未完整读取，已省略截图；可继续使用已脱敏的文字信息）"
            }
            previousLabels = elements.map { it.label }.filter { it.isNotEmpty() }.toSet()
            return ScreenSnapshot(id, text, appLabel, pkg, secure = false, poorTree = poor,
                image = image, imageMime = if (image == null) null else imageMime)
        }
        // 完整观测：绑定 display、window 与新票据；列表内编号可操作。
        lastElements = elements
        lastScreenId = id
        displayId?.let { lastDisplayId = it }
        lastWindowId = windowId
        observationTicket = ticket
        var text = BackgroundTree.render(id, appLabel, portrait, keyboardUp, elements, background = true, poorTree = poor)
        if (truncated) {
            val shown = elements.size
            // l10n:keep agent screen protocol
            text += "\n（本页共 ${total ?: shown} 项，列表只显示前 $shown 项；列表之外的编号不可操作，用 collect 或 scroll 确认其余内容）"
        }
        if (imageOmitted) {
            // l10n:keep agent screen protocol
            text += "\n（本页含敏感输入或控件树未完整读取，已省略截图；可继续使用已脱敏的文字信息）"
        }
        previousLabels = elements.map { it.label }.filter { it.isNotEmpty() }.toSet()
        return ScreenSnapshot(id, text, appLabel, pkg, secure = false, poorTree = poor,
            image = image, imageMime = if (image == null) null else imageMime)
    }

    private fun buildElements(response: Bundle, markNew: Boolean): List<Element> {
        val n = response.getInt(Proto.COUNT, 0)
        val role = response.getStringArray(Proto.A_ROLE) ?: return emptyList()
        val label = response.getStringArray(Proto.A_LABEL) ?: return emptyList()
        val viewIds = response.getStringArray(Proto.A_VIEW_ID)
        val states = response.getStringArray(Proto.A_STATES) ?: arrayOfNulls(n)
        val flags = response.getIntArray(Proto.A_FLAGS) ?: IntArray(n)
        val region = response.getIntArray(Proto.A_REGION) ?: IntArray(n)
        val indent = response.getIntArray(Proto.A_INDENT) ?: IntArray(n)
        val out = ArrayList<Element>(n)
        for (i in 0 until n) {
            val lbl = label[i].orEmpty()
            val f = flags.getOrElse(i) { 0 }
            out.add(
                Element(
                    id = i + 1,
                    role = role[i].orEmpty(),
                    label = lbl,
                    region = regionOf(region.getOrElse(i) { 1 }),
                    indent = indent.getOrElse(i) { 0 },
                    states = states[i]?.split('\u0001')?.filter { it.isNotEmpty() } ?: emptyList(),
                    sensitive = f and Proto.FLAG_SENSITIVE != 0,
                    isNew = markNew && lbl.isNotEmpty() && lbl !in previousLabels,
                    isPassword = f and Proto.FLAG_PASSWORD != 0,
                    isSecretField = f and Proto.FLAG_SECRET != 0,
                    isEditable = f and Proto.FLAG_EDITABLE != 0,
                    viewId = viewIds?.getOrNull(i),
                ),
            )
        }
        return out
    }

    private fun regionOf(code: Int): Region = when (code) {
        0 -> Region.TOP
        2 -> Region.BOTTOM
        else -> Region.MIDDLE
    }

    private fun idleSnapshot(): ScreenSnapshot {
        val id = "b${++counter}"
        lastScreenId = id; lastElements = emptyList(); lastWindowId = null; observationTicket = null
        // l10n:keep agent screen protocol
        return ScreenSnapshot(id, "屏幕 $id · 后台屏 · 空闲，用 open 打开应用", null, null)
    }

    companion object {
        /** The identity as it travels on the wire: every key always present, null values kept,
         *  so the core can tell a null view id from a missing field. */
        internal fun expectFields(expect: ElementExpect): List<Pair<String, Any?>> = listOf(
            Proto.EXPECT_DISPLAY to expect.displayId,
            Proto.EXPECT_WINDOW to expect.windowId,
            Proto.EXPECT_ROLE to expect.role,
            Proto.EXPECT_LABEL to expect.label,
            Proto.EXPECT_VIEW_ID to expect.viewId,
        )

        /** Production decision behind [toResult]; kept pure so the wire rules stay testable.
         *  Conservative by contract: once a request was sent, ANY failure response — known
         *  stale codes, cancelled/timeout/deviceUnavailable from a cancelled settle, even
         *  an unrelated refusal like locked — kills the cached observation and its ticket,
         *  so nothing is replayed on a screen we can no longer see. Only refusals decided
         *  before any request was built keep the cache untouched. */
        internal fun invalidatesCache(ok: Boolean, dispatched: Boolean, errorCode: String?): Boolean =
            !ok

        /** Production decision behind [open]; kept pure so the wire rules stay testable.
         *  The target package is published only on a confirmed launch. A failed open
         *  keeps whatever was confirmed before — a first failure keeps none — so the
         *  next action never addresses a package that never opened. */
        internal fun targetAfterOpen(ok: Boolean, confirmed: String?, requested: String): String? =
            if (ok) requested else confirmed

        /** A complete read (COUNT present, not partial) must carry a fresh ticket; anything
         *  else answering with elements is not a rev-2 core and must not be acted on. */
        internal fun missingTicketViolation(hasCount: Boolean, partial: Boolean, ticket: String?): Boolean =
            hasCount && !partial && ticket == null
    }
}
