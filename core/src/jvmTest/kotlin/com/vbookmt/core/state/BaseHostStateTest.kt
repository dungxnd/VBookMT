package com.vbookmt.core.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BaseHostStateTest {

    @Test
    fun testInitialState() {
        val state = BaseHostState()
        assertFalse(state.isRunning.value)
        assertEquals(BaseHostState.EngineState.STOPPED, state.engineState.value)
        assertEquals(0, state.inFlightCount.value)
        assertEquals(0, state.inFlight.get())
        assertEquals(0, state.requestCount.value)
    }

    @Test
    fun testRunningAndEngineTransitions() {
        val state = BaseHostState()
        state.setRunning(true)
        assertTrue(state.isRunning.value)

        state.setEngineState(BaseHostState.EngineState.LOADING)
        assertEquals(BaseHostState.EngineState.LOADING, state.engineState.value)

        state.setEngineState(BaseHostState.EngineState.READY)
        assertEquals(BaseHostState.EngineState.READY, state.engineState.value)

        state.setRunning(false)
        assertFalse(state.isRunning.value)
        assertEquals(BaseHostState.EngineState.STOPPED, state.engineState.value)
    }

    @Test
    fun testInFlightCounting() {
        val state = BaseHostState()
        state.setRunning(true)

        val c1 = state.changeInFlight(1)
        assertEquals(1, c1)
        assertEquals(1, state.inFlightCount.value)
        assertEquals(1, state.inFlight.get())

        state.noteRequestStarted()
        assertEquals(1, state.requestCount.value)

        val c2 = state.changeInFlight(-1)
        assertEquals(0, c2)
        assertEquals(0, state.inFlightCount.value)
        assertEquals(0, state.inFlight.get())
        assertEquals(1, state.requestCount.value)

        // Stopping automatically resets inFlight
        state.changeInFlight(2)
        assertEquals(2, state.inFlightCount.value)
        state.setRunning(false)
        assertEquals(0, state.inFlightCount.value)
        assertEquals(0, state.inFlight.get())
    }

    @Test
    fun testActiveModelChange() {
        val state = BaseHostState()
        state.setActiveModel("moxhi-30-qt-onnx")
        assertEquals("moxhi-30-qt-onnx", state.activeModel.value)
    }
}
