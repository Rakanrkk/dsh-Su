package io.github.ccch1mneyyy.dsh_app.phone.a11y

import io.github.ccch1mneyyy.dsh_app.phone.PhoneAction
import io.github.ccch1mneyyy.dsh_app.phone.a11y.Box
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollPlanTest {
    private val screen = Box(0, 0, 1000, 2000)

    @Test fun downMovesTheFingerUp() {
        val bounds = Box(100, 200, 900, 1800)
        val plan = Actuator.scrollPlan(bounds, screen, PhoneAction.Direction.DOWN)!!
        assertEquals(bounds.centerX, plan.sx)
        assertEquals(plan.sx, plan.ex)
        assertTrue(plan.sy > plan.ey)
    }

    @Test fun upMovesTheFingerDown() {
        val bounds = Box(100, 200, 900, 1800)
        val plan = Actuator.scrollPlan(bounds, screen, PhoneAction.Direction.UP)!!
        assertEquals(bounds.centerX, plan.sx)
        assertEquals(plan.sx, plan.ex)
        assertTrue(plan.sy < plan.ey)
    }

    @Test fun rightMovesTheFingerLeft() {
        val bounds = Box(100, 200, 900, 1800)
        val plan = Actuator.scrollPlan(bounds, screen, PhoneAction.Direction.RIGHT)!!
        assertEquals(bounds.centerY, plan.sy)
        assertEquals(plan.sy, plan.ey)
        assertTrue(plan.sx > plan.ex)
    }

    @Test fun leftMovesTheFingerRight() {
        val bounds = Box(100, 200, 900, 1800)
        val plan = Actuator.scrollPlan(bounds, screen, PhoneAction.Direction.LEFT)!!
        assertEquals(bounds.centerY, plan.sy)
        assertEquals(plan.sy, plan.ey)
        assertTrue(plan.sx < plan.ex)
    }

    @Test fun tooSmallAnAreaYieldsNoPlan() {
        assertNull(Actuator.scrollPlan(Box(0, 0, 30, 30), screen, PhoneAction.Direction.DOWN))
    }

    @Test fun offScreenBoundsAreClippedToTheVisibleArea() {
        val plan = Actuator.scrollPlan(Box(-500, -500, 500, 500), screen, PhoneAction.Direction.DOWN)!!
        assertTrue(plan.sx > 0 && plan.sy > 0 && plan.ex > 0 && plan.ey > 0)
    }
}
