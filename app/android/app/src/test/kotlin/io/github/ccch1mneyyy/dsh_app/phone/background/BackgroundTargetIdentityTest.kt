package io.github.ccch1mneyyy.dsh_app.phone.background

import io.github.ccch1mneyyy.dsh_app.phone.PhoneAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The background write's identity contract, on both ends of the wire. The Bundle objects
 * themselves cannot be constructed in a JVM unit test (the framework jar is stubbed), so
 * the wire form is asserted as the exact key/value list both sides agree on, and the
 * privileged check runs on that same list through its map-shaped seam. Real Bundle
 * marshalling and the actual injection path stay device-only.
 */
class BackgroundTargetIdentityTest {
    private val button = PrivilegedDisplayCore.Identity("按钮", "确定", "com.a:id/ok")
    private val blankInput = PrivilegedDisplayCore.Identity("输入框", "", null)

    // ---- privileged side: identity completeness and matching ------------------------

    @Test fun anIdentityIsOnlyCompleteWithAllThreeFieldsPresent() {
        val full = mapOf(
            Proto.EXPECT_ROLE to "按钮",
            Proto.EXPECT_LABEL to "确定",
            Proto.EXPECT_VIEW_ID to "com.a:id/ok",
        )
        assertEquals(button, PrivilegedDisplayCore.identityOf(full))
        for (key in PrivilegedDisplayCore.identityKeys) {
            assertNull("missing $key must refuse the action", PrivilegedDisplayCore.identityOf(full - key))
        }
        assertNull("a null role is not an identity", PrivilegedDisplayCore.identityOf(full + (Proto.EXPECT_ROLE to null)))
        assertNull("a null label is not an identity", PrivilegedDisplayCore.identityOf(full + (Proto.EXPECT_LABEL to null)))
    }

    @Test fun matchingRequiresAllThreeValuesToBeEqual() {
        assertTrue(PrivilegedDisplayCore.identityMatches(button, button))
        assertTrue(PrivilegedDisplayCore.identityMatches(blankInput, blankInput))
    }

    @Test fun aSameNumberWithADifferentNodeIsRefused() {
        // label grew: same prefix, different node
        assertFalse(PrivilegedDisplayCore.identityMatches(button, PrivilegedDisplayCore.Identity("按钮", "确定更新", "com.a:id/ok")))
        // same label, different view id
        assertFalse(PrivilegedDisplayCore.identityMatches(button, PrivilegedDisplayCore.Identity("按钮", "确定", "com.a:id/send")))
        // view id lost
        assertFalse(PrivilegedDisplayCore.identityMatches(button, PrivilegedDisplayCore.Identity("按钮", "确定", null)))
        // role changed under the same label
        assertFalse(PrivilegedDisplayCore.identityMatches(button, PrivilegedDisplayCore.Identity("文本", "确定", "com.a:id/ok")))
        // the blank input got text
        assertFalse(PrivilegedDisplayCore.identityMatches(blankInput, PrivilegedDisplayCore.Identity("输入框", "138****0000", null)))
    }

    @Test fun anIdentityThatMatchesSeveralNodesIsAmbiguous() {
        val second = PrivilegedDisplayCore.Identity("按钮", "确定", "com.a:id/ok")
        assertFalse(PrivilegedDisplayCore.identityAmbiguous(button, listOf(button)))
        assertTrue("an unlabelled role row repeats on every list: refuse", PrivilegedDisplayCore.identityAmbiguous(blankInput, listOf(blankInput, blankInput)))
        assertTrue(PrivilegedDisplayCore.identityAmbiguous(button, listOf(button, second)))
    }

    @Test fun aWeakIdentityIsRefusedForWritesButStillCarried() {
        // 空文本且无 viewId：写操作拒绝；有 viewId 或有文本即可安全写。
        assertFalse("blank label with no view id cannot be re-verified", PrivilegedDisplayCore.writeIdentitySufficient(blankInput))
        assertTrue(PrivilegedDisplayCore.writeIdentitySufficient(PrivilegedDisplayCore.Identity("输入框", "", "com.a:id/q")))
        assertTrue(PrivilegedDisplayCore.writeIdentitySufficient(PrivilegedDisplayCore.Identity("输入框", "备注", null)))
        assertTrue(PrivilegedDisplayCore.writeIdentitySufficient(button))
        // The wire still carries the weak identity untouched — the refusal happens on the
        // privileged side, right before any text is sent.
        val fields = BackgroundDisplay.expectFields(BackgroundDisplay.ElementExpect(7, 33, blankInput.role, blankInput.label, blankInput.viewId))
            .filter { it.second is String? }
            .associate { it.first to it.second as String? }
        assertEquals(blankInput, PrivilegedDisplayCore.identityOf(fields))
    }

    // ---- privileged side: observation ticket and revision ----------------------------

    @Test fun anObservationTicketOnlyMatchesItselfExactly() {
        assertTrue(PrivilegedDisplayCore.ticketOk("a1b2", "a1b2"))
        assertFalse("no stored ticket: nothing was completely read", PrivilegedDisplayCore.ticketOk(null, "a1b2"))
        assertFalse("no echoed ticket: an old client", PrivilegedDisplayCore.ticketOk("a1b2", null))
        assertFalse("a rotated or revoked ticket is stale", PrivilegedDisplayCore.ticketOk("a1b2", "zzzz"))
    }

    @Test fun onlyRevTwoClientsHandshake() {
        assertEquals("dsh-vdisplay-2", Proto.REVISION)
        assertTrue(PrivilegedDisplayCore.revisionOk(Proto.REVISION))
        assertFalse("a rev-1 client predates the mandatory identity fields", PrivilegedDisplayCore.revisionOk("dsh-vdisplay-1"))
        assertFalse(PrivilegedDisplayCore.revisionOk(null))
    }

    // ---- app side: the wire form -----------------------------------------------------

    @Test fun theIdentityTravelsWithEveryKeyAlwaysPresent() {
        val expect = BackgroundDisplay.ElementExpect(7, 33, "输入框", "", null)
        val fields = BackgroundDisplay.expectFields(expect)
        assertEquals(
            listOf(
                Proto.EXPECT_DISPLAY, Proto.EXPECT_WINDOW,
                Proto.EXPECT_ROLE, Proto.EXPECT_LABEL, Proto.EXPECT_VIEW_ID,
            ),
            fields.map { it.first },
        )
        // The privileged side consumes exactly these keys: the display/window pair plus
        // the identity triple, all present, nulls kept.
        val keys = fields.map { it.first }
        assertTrue(PrivilegedDisplayCore.identityKeys.all { it in keys })
        assertEquals(listOf(Proto.EXPECT_DISPLAY, Proto.EXPECT_WINDOW), keys - PrivilegedDisplayCore.identityKeys.toSet())
    }

    @Test fun theWireFormRoundTripsIntoThePrivilegedCheck() {
        for (identity in listOf(button, blankInput)) {
            // A Bundle maps a present key to its value, null included; Int fields stay Ints
            // and are simply not part of the identity triple the privileged side matches on.
            val expect = BackgroundDisplay.ElementExpect(7, 33, identity.role, identity.label, identity.viewId)
            val fields = BackgroundDisplay.expectFields(expect)
                .filter { it.second is String? }
                .associate { it.first to it.second as String? }
            val roundTripped = PrivilegedDisplayCore.identityOf(fields)
            assertNotNull(roundTripped)
            assertTrue(PrivilegedDisplayCore.identityMatches(roundTripped!!, identity))
        }
    }

    // ---- app side: expectations come from the current snapshot only ------------------

    @Test fun anExpectationNeedsTheSnapshotItsDisplayAndItsWindow() {
        val display = BackgroundDisplay(
            owner = "task:test:7",
            channelProvider = { error("expectation building must not start a connection") },
            releaseChannelProvider = { error("expectation building must not release the display") },
        )
        assertNull("no snapshot yet", display.expectFor(1))

        setField(display, "lastElements", listOf(Element(1, "按钮", "确定", Region.MIDDLE, viewId = "com.a:id/ok")))
        assertNull("elements without a display binding are not addressable", display.expectFor(1))
        setField(display, "lastDisplayId", 7)
        assertNull("elements without a window binding are not addressable", display.expectFor(1))
        setField(display, "lastWindowId", 33)
        assertEquals(
            BackgroundDisplay.ElementExpect(7, 33, "按钮", "确定", "com.a:id/ok"),
            display.expectFor(1),
        )
        assertNull("numbers past the snapshot are not addressable", display.expectFor(2))
    }

    @Test fun anActionWithoutALiveTicketIsRefusedWithoutTouchingTheChannel() {
        val display = BackgroundDisplay(
            owner = "task:test:7",
            channelProvider = { error("a refused action must not reach the privileged channel") },
            releaseChannelProvider = { error("a refused action must not release the background screen") },
        )
        // The screen is "open" but nothing was completely observed: no ticket exists, so
        // every action is refused before any request is built.
        setField(display, "currentPackage", "example.opened")
        for (action in listOf(PhoneAction.Tap("b1", 1), PhoneAction.Tap("b1", 1, long = true), PhoneAction.Type("b1", 1, "hi"))) {
            val result = display.perform(action)
            assertFalse(result.ok)
            assertTrue(result.message.contains("重新观察"))
            assertNull(result.screen)
        }
        // With a live ticket but a snapshot that lacks the number, the identity gate
        // refuses next — still without building a request.
        setField(display, "observationTicket", "c0ffee")
        val result = display.perform(PhoneAction.Tap("b1", 1))
        assertFalse(result.ok)
        assertTrue(result.message.contains("重新观察"))
    }

    @Test fun invalidatingTheObservationDropsNumbersTheWindowAndTheTicket() {
        val display = BackgroundDisplay(
            owner = "task:test:7",
            channelProvider = { error("invalidation must not start a connection") },
            releaseChannelProvider = { error("invalidation must not release the display") },
        )
        setField(display, "lastScreenId", "b7")
        setField(display, "lastElements", listOf(Element(1, "按钮", "确定", Region.MIDDLE)))
        setField(display, "lastDisplayId", 7)
        setField(display, "lastWindowId", 33)
        setField(display, "observationTicket", "c0ffee")
        assertNotNull(display.target("b7", 1))
        assertNotNull(display.expectFor(1))

        display.invalidateObservation()

        assertNull(display.target("b7", 1))
        assertNull("the next write must need a fresh observation", display.expectFor(1))
        assertNull("no ticket survives an invalidation", fieldOf(display, "observationTicket"))

        // A fresh complete observation restores operability: new numbers, new ticket.
        display.applyObservation(
            id = "b8", partial = false, truncated = false, total = null, ticket = "fresh",
            elements = listOf(Element(1, "按钮", "确定", Region.MIDDLE)), displayId = 7, windowId = 33,
            appLabel = "示例", pkg = "example.opened", poor = false, portrait = true,
            keyboardUp = false, imageOmitted = false, image = null, imageMime = null,
        )
        assertNotNull("a fresh observation acts again", display.target("b8", 1))
        assertEquals("fresh", fieldOf(display, "observationTicket"))
    }

    // ---- the four observation outcomes (complete / truncated / cut / failed) ----------

    @Test fun anUnreadableObservationIsNotReportedAsAnEmptyScreen() {
        val display = BackgroundDisplay(
            owner = "task:test:7",
            channelProvider = { error("snapshot handling must not start a connection") },
            releaseChannelProvider = { error("snapshot handling must not release the display") },
        )
        setField(display, "lastScreenId", "b7")
        setField(display, "lastElements", listOf(Element(1, "按钮", "确定", Region.MIDDLE)))
        setField(display, "lastDisplayId", 7)
        setField(display, "lastWindowId", 33)
        setField(display, "observationTicket", "c0ffee")

        val snap = display.applyObservation(
            id = "b9", partial = true, truncated = false, total = null, ticket = null,
            elements = emptyList(), displayId = null, windowId = null,
            appLabel = "示例", pkg = "example.opened", poor = false, portrait = true,
            keyboardUp = false, imageOmitted = false, image = null, imageMime = null,
        )

        assertNull("old numbers are gone", display.target("b7", 1))
        assertNull("and the unreadable screen owns none either", display.target("b9", 1))
        assertTrue(snap.text.contains("重新观察"))
        assertFalse(snap.secure)
        assertNull("a failed read never leaves a writable ticket", fieldOf(display, "observationTicket"))
    }

    @Test fun anIncompleteCollectionIsViewOnlyAndNeverOperable() {
        val display = BackgroundDisplay(
            owner = "task:test:7",
            channelProvider = { error("snapshot handling must not start a connection") },
            releaseChannelProvider = { error("snapshot handling must not release the display") },
        )
        val elements = (1..5).map { Element(it, "按钮", "项 $it", Region.MIDDLE) }

        val snap = display.applyObservation(
            id = "b4", partial = true, truncated = false, total = null, ticket = null,
            elements = elements, displayId = 7, windowId = null,
            appLabel = "示例", pkg = "example.opened", poor = false, portrait = true,
            keyboardUp = false, imageOmitted = false, image = null, imageMime = null,
        )

        // The cut list stays readable, but without a window binding and a ticket none of
        // its numbers may be acted on.
        assertNotNull(display.target("b4", 5))
        assertNull("a budget-cut page hands out no operable numbers", display.expectFor(5))
        assertNull("no writable ticket from an incomplete read", fieldOf(display, "observationTicket"))
        assertTrue(snap.text.contains("暂不可操作"))
    }

    @Test fun aDisplayOnlyTruncationKeepsItsNumbersOperableAndSaysSo() {
        val display = BackgroundDisplay(
            owner = "task:test:7",
            channelProvider = { error("snapshot handling must not start a connection") },
            releaseChannelProvider = { error("snapshot handling must not release the display") },
        )
        val elements = (1..120).map { Element(it, "按钮", "项 $it", Region.MIDDLE) }

        val snap = display.applyObservation(
            id = "b3", partial = false, truncated = true, total = 250, ticket = "fresh",
            elements = elements, displayId = 7, windowId = 33,
            appLabel = "示例", pkg = "example.opened", poor = false, portrait = true,
            keyboardUp = false, imageOmitted = false, image = null, imageMime = null,
        )

        assertNotNull(display.target("b3", 120))
        assertEquals(
            "the display cap keeps the page fully operable",
            BackgroundDisplay.ElementExpect(7, 33, "按钮", "项 120", null),
            display.expectFor(120),
        )
        assertEquals("fresh", fieldOf(display, "observationTicket"))
        assertTrue(snap.text.contains("本页共 250 项"))
        assertFalse("truncation is not an incomplete collection", snap.text.contains("暂不可操作"))
    }

    @Test fun aCompleteObservationNeedsNoCaveat() {
        val display = BackgroundDisplay(
            owner = "task:test:7",
            channelProvider = { error("snapshot handling must not start a connection") },
            releaseChannelProvider = { error("snapshot handling must not release the display") },
        )
        val elements = listOf(Element(1, "按钮", "确定", Region.MIDDLE))

        val snap = display.applyObservation(
            id = "b2", partial = false, truncated = false, total = null, ticket = "fresh",
            elements = elements, displayId = 7, windowId = 33,
            appLabel = "示例", pkg = "example.opened", poor = false, portrait = true,
            keyboardUp = false, imageOmitted = false, image = null, imageMime = null,
        )

        assertNotNull(display.target("b2", 1))
        assertEquals(BackgroundDisplay.ElementExpect(7, 33, "按钮", "确定", null), display.expectFor(1))
        assertFalse(snap.text.contains("不完整"))
        assertFalse(snap.text.contains("重新观察"))
    }

    // ---- contract verdicts on response shapes ------------------------------------------

    @Test fun aResponseWithElementsButNoTicketBreaksTheContract() {
        assertTrue("elements without a ticket are a rev mismatch, not a screen", BackgroundDisplay.missingTicketViolation(hasCount = true, partial = false, ticket = null))
        assertFalse("a partial read legitimately carries no ticket", BackgroundDisplay.missingTicketViolation(hasCount = true, partial = true, ticket = null))
        assertFalse(BackgroundDisplay.missingTicketViolation(hasCount = true, partial = false, ticket = "fresh"))
        assertFalse("no elements: nothing to bind", BackgroundDisplay.missingTicketViolation(hasCount = false, partial = false, ticket = null))
    }

    @Test fun anyFailureAfterARequestWasSentInvalidatesTheCache() {
        assertFalse("success keeps everything", BackgroundDisplay.invalidatesCache(ok = true, dispatched = false, errorCode = null))
        // Conservative by contract: once the request was sent, every failure response —
        // a cancelled settle, a timeout, an unreachable device, even an unrelated
        // refusal — kills the cached observation and its ticket.
        for (code in listOf("cancelled", "timeout", "deviceUnavailable", "locked", "identityInsufficient", "staleSnapshot", "revisionMismatch")) {
            assertTrue(code, BackgroundDisplay.invalidatesCache(ok = false, dispatched = false, errorCode = code))
        }
        assertTrue("dispatched with unknown outcome: the cache dies", BackgroundDisplay.invalidatesCache(ok = false, dispatched = true, errorCode = "locked"))
    }

    @Test fun aTapRefusesWeakTargetsButARealViewIdStillCounts() {
        // The tap resolver applies the same sufficiency rule as writes: a blank label with
        // no view id cannot be re-verified after the screen changes, so it is not injected.
        assertFalse("blank label, no view id: tap refused", PrivilegedDisplayCore.writeIdentitySufficient(PrivilegedDisplayCore.Identity("按钮", "", null)))
        // An identifiable empty input (a real view id) stays usable for tap and text alike.
        assertTrue("empty box with a real view id stays tappable", PrivilegedDisplayCore.writeIdentitySufficient(PrivilegedDisplayCore.Identity("输入框", "", "com.a:id/q")))
        // The weak identity still travels on the wire untouched; the refusal is the core's.
        val fields = BackgroundDisplay.expectFields(BackgroundDisplay.ElementExpect(7, 33, "按钮", "", null))
            .filter { it.second is String? }
            .associate { it.first to it.second as String? }
        assertEquals(PrivilegedDisplayCore.Identity("按钮", "", null), PrivilegedDisplayCore.identityOf(fields))
    }

    @Test fun everyRequestCarriesTheRealOwnerNotAConstant() {
        // The driver was constructed with a real owner token; perform echoes that
        // token into every request's OWNER key, never the historical constant "task".
        val display = BackgroundDisplay(
            owner = "task:epoch-1:7",
            channelProvider = { error("request building must not start a connection") },
            releaseChannelProvider = { error("request building must not release the display") },
        )
        setField(display, "currentPackage", "example.opened")
        setField(display, "lastElements", listOf(Element(1, "按钮", "确定", Region.MIDDLE)))
        setField(display, "lastDisplayId", 7)
        setField(display, "lastWindowId", 33)
        setField(display, "observationTicket", "fresh")
        assertEquals("task:epoch-1:7", fieldOf(display, "owner"))
    }

    @Test fun aReleaseWithoutConfirmationIsReportedNotAssumed() {
        // release() returns the privileged side's RELEASED verdict; with no channel it
        // is false, so the control layer can record a pending cleanup instead of
        // believing the display is gone.
        val display = BackgroundDisplay(
            owner = "task:epoch-1:7",
            channelProvider = { error("release must not use the action channel") },
            releaseChannelProvider = { null },
        )
        assertFalse("no channel: no confirmation", display.release())
    }

    @Test fun aQueuedScreenOffEffectSparesANewTask() {
        // The stop effect is captured for one task. If a new task registered its own
        // driver before the queued effect runs, the effect is stale: it must neither
        // end the new driver nor drop the channels that new owner is using.
        assertTrue("old effect vs new task", BackgroundControl.interruptIsStale(driverTaskId = 8L, expectedTaskId = 7L, endedTaskId = 7L))
        assertTrue("already ended", BackgroundControl.interruptIsStale(driverTaskId = 7L, expectedTaskId = 7L, endedTaskId = 7L))
        assertTrue("nothing is driving", BackgroundControl.interruptIsStale(driverTaskId = null, expectedTaskId = 7L, endedTaskId = 6L))
        // The live pair passes the ownership check and proceeds.
        assertFalse("own effect still applies", BackgroundControl.interruptIsStale(driverTaskId = 7L, expectedTaskId = 7L, endedTaskId = 6L))
    }

    @Test fun aLostReleaseAckConvergesWithoutTouchingANewOwner() {
        // Release succeeded on the core but the ack was lost: the same owner retries
        // and is re-confirmed from the tombstone.
        var releases = 0
        assertTrue(
            "tombstoned owner re-confirms",
            PrivilegedDisplayCore.releaseConfirmed(liveOwner = null, lastReleased = "task:e:7", requester = "task:e:7") { releases++; true },
        )
        assertEquals(0, releases)
        // A different owner while a NEW owner is live: the receipt is refused and the
        // release lambda is never invoked — the new owner's resources are untouched.
        var touched = false
        assertFalse(
            "stale release never claims the live owner",
            PrivilegedDisplayCore.releaseConfirmed(liveOwner = "task:e:8", lastReleased = null, requester = "task:e:7") { touched = true; false },
        )
        assertFalse("no release attempted against the new owner", touched)
        // A genuine failure of the live owner's release reports false, not fake success.
        assertFalse(
            "failed release stays failed",
            PrivilegedDisplayCore.releaseConfirmed(liveOwner = "task:e:8", lastReleased = null, requester = "task:e:8") { false },
        )
    }

    @Test fun aPendingReleaseFromAnOldEpochIsTerminalNotRetriedForever() {
        // A previous App session's epoch names a dead privileged core (stdin EOF /
        // binder death took its displays): such an entry is dropped as terminal, so
        // the live core is never asked to confirm an owner it never knew.
        assertTrue(BackgroundControl.releaseEntryIsCurrentEpoch("task:epoch-2:9", "epoch-2"))
        assertFalse(BackgroundControl.releaseEntryIsCurrentEpoch("task:epoch-1:7", "epoch-2"))
    }

    @Test fun anInterruptDropsOnlyTheCapturedGenerationNotANewerOwnersChannel() {
        // The queued effect asks only the transport the interrupted generation itself
        // used to drop the captured instance (the starters re-check identity before
        // retiring); root's channel survives an interrupt (re-opening needs su again).
        assertEquals(setOf("shizuku"), BackgroundControl.interruptTargets("shizuku"))
        assertEquals(setOf("shizuku"), BackgroundControl.interruptTargets("sui"))
        assertEquals(setOf("adb"), BackgroundControl.interruptTargets("adb"))
        assertTrue("root survives an interrupt", BackgroundControl.interruptTargets("root").isEmpty())
        assertTrue("an unknown mode drops nothing", BackgroundControl.interruptTargets("off").isEmpty())
        // The stale guard stays the first wall: after a newer task takes over, the old
        // screen-off effect neither ends it nor drops its channels.
        assertTrue(BackgroundControl.interruptIsStale(driverTaskId = 8L, expectedTaskId = 7L, endedTaskId = 7L))
    }

    @Test fun aFailedCleanupKeepsItsReferenceUntilARealRetrySucceeds() {
        var calls = 0
        var cleared = false
        // First attempt: the cleanup itself throws — the step fails and the reference
        // stays, so the same owner's next attempt retries a real cleanup instead of
        // "succeeding" against a reference the failed attempt would have dropped.
        assertFalse(
            PrivilegedDisplayCore.cleanupStep(
                release = { calls++; throw IllegalStateException("release failed") },
                clear = { cleared = true },
            ),
        )
        assertEquals(1, calls)
        assertFalse("a failed step never drops the reference", cleared)
        // Second attempt: the real cleanup runs; only then is the reference cleared
        // and the release allowed to confirm.
        assertTrue(PrivilegedDisplayCore.cleanupStep(release = { calls++ }, clear = { cleared = true }))
        assertEquals(2, calls)
        assertTrue(cleared)
    }

    @Test fun anInterruptRetireDecisionNeverTouchesAChannelANewerOwnerPublished() {
        val captured = ProbeChannel()
        val newer = ProbeChannel()
        // A newer owner is already published: the teardown aborts entirely — it neither
        // drops the shared field nor marks anything retiring (single critical section
        // with beginTask's publish, not a double generation read).
        assertEquals(
            BackgroundControl.InterruptRetire(proceed = false, dropShared = false, markRetiring = false),
            BackgroundControl.interruptRetire(driverTaskId = 9L, channel = newer, captured = captured, disconnects = true),
        )
        // No owner yet: the shared field is dropped only while it still is the captured
        // instance, and the captured instance is marked retiring so a later publish
        // (same instance re-adopted from the shared field or the starter cache) refuses.
        assertEquals(
            BackgroundControl.InterruptRetire(proceed = true, dropShared = true, markRetiring = true),
            BackgroundControl.interruptRetire(driverTaskId = null, channel = captured, captured = captured, disconnects = true),
        )
        // The shared field was already re-homed elsewhere: nothing to drop, still retire.
        assertEquals(
            BackgroundControl.InterruptRetire(proceed = true, dropShared = false, markRetiring = true),
            BackgroundControl.interruptRetire(driverTaskId = null, channel = newer, captured = captured, disconnects = true),
        )
        // Root never closes its transport on interrupt: nothing is marked retiring.
        assertEquals(
            BackgroundControl.InterruptRetire(proceed = true, dropShared = true, markRetiring = false),
            BackgroundControl.interruptRetire(driverTaskId = null, channel = captured, captured = captured, disconnects = false),
        )
    }

    @Test fun aBeginPublishRefusesAChannelInstanceAnInterruptIsRetiring() {
        val retiring = ProbeChannel()
        val fresh = ProbeChannel()
        // Same-instance re-adoption is exactly what the publish path must refuse:
        // adopting a retiring instance would hand the new task a channel the
        // in-flight teardown is about to drop.
        assertTrue(BackgroundControl.publishRefusesRetiring(retiring, retiring))
        assertFalse(BackgroundControl.publishRefusesRetiring(retiring, fresh))
        assertFalse("nothing retiring adopts anything", BackgroundControl.publishRefusesRetiring(null, retiring))
        assertFalse("no candidate adopts nothing", BackgroundControl.publishRefusesRetiring(retiring, null))
    }

    @Test fun theKeyboardStateIsARealTriStateNeverAFakeDown() {
        val up = BackgroundTree.render("b1", null, true, true, emptyList(), background = true, poorTree = false)
        val down = BackgroundTree.render("b1", null, true, false, emptyList(), background = true, poorTree = false)
        val unknown = BackgroundTree.render("b1", null, true, null, emptyList(), background = true, poorTree = false)
        assertTrue("a real observation renders 键盘弹出", up.contains("键盘弹出"))
        assertTrue("a real observation renders 键盘收起", down.contains("键盘收起"))
        assertTrue("an unobservable IME renders 键盘状态未知", unknown.contains("键盘状态未知"))
        assertFalse("unknown must not claim 键盘收起", unknown.contains("键盘收起"))
    }

    @Test fun aFailedOpenNeverClaimsTheTargetItNeverOpened() {
        // First failure: there is no confirmed target yet — the failed package must not
        // become current, or the next action would address a package that never opened.
        assertEquals(
            null,
            BackgroundDisplay.targetAfterOpen(ok = false, confirmed = null, requested = "com.b"),
        )
        // A previously confirmed app stays the target: its display is untouched by the
        // failed switch, and the observation cache dies through toResult's existing path.
        assertEquals(
            "com.a",
            BackgroundDisplay.targetAfterOpen(ok = false, confirmed = "com.a", requested = "com.b"),
        )
    }

    @Test fun anOpenPublishesItsTargetOnlyOnceConfirmed() {
        assertEquals("com.b", BackgroundDisplay.targetAfterOpen(ok = true, confirmed = "com.a", requested = "com.b"))
        assertEquals("com.c", BackgroundDisplay.targetAfterOpen(ok = true, confirmed = null, requested = "com.c"))
    }

    @Test fun anOpenWhoseSendThrowsDropsTheCachedObservationButKeepsTheConfirmedApp() {
        val display = BackgroundDisplay(
            owner = "task:test:7",
            channelProvider = { error("the send failure is surfaced directly") },
            releaseChannelProvider = { error("a failed open must not release the display") },
        )
        // App A is confirmed with a live cached observation, then the open of B dies
        // on the wire (no answer at all). The stale ticket/elements/window must die
        // with it — the next write needs a fresh observation — while the confirmed
        // package stays A and the real error surfaces untouched (no replay, no fake
        // unknown-failure message).
        setField(display, "currentPackage", "com.a")
        setField(display, "lastScreenId", "b7")
        setField(display, "lastElements", listOf(Element(1, "按钮", "确定", Region.MIDDLE)))
        setField(display, "lastDisplayId", 7)
        setField(display, "lastWindowId", 33)
        setField(display, "observationTicket", "c0ffee")
        assertNotNull(display.target("b7", 1))
        assertNotNull(display.expectFor(1))

        val failure = assertThrows("the real send error surfaces", IllegalStateException::class.java) {
            display.onOpenSendFailed(IllegalStateException("channel died mid-open"))
        }
        assertEquals("channel died mid-open", failure.message)

        assertNull("no stale ticket survives a dead send", fieldOf(display, "observationTicket"))
        assertNull(display.target("b7", 1))
        assertNull("the next write must need a fresh observation", display.expectFor(1))
        assertEquals("the confirmed app stays the target", "com.a", fieldOf(display, "currentPackage"))
    }

    private class ProbeChannel : PrivilegedChannel {
        override fun execute(request: android.os.Bundle): android.os.Bundle =
            throw IllegalStateException("the identity probes never touch a channel")
        override fun alive(): Boolean = true
        override fun close() {}
    }

    private fun setField(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply {
            isAccessible = true
            set(target, value)
        }
    }

    private fun fieldOf(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
}
