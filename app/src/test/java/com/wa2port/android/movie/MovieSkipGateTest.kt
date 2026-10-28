package com.wa2port.android.movie

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MovieSkipGateTest {
    @Test
    fun watchedMovieSkipsOnFirstRequest() {
        val gate = MovieSkipGate(isFirstView = false)
        assertTrue(gate.requestSkip())
        assertFalse(gate.hintVisible)
    }

    @Test
    fun firstViewNeedsASecondRequestWhileHintIsVisible() {
        val gate = MovieSkipGate(isFirstView = true)
        assertFalse(gate.requestSkip())
        assertTrue(gate.hintVisible)
        assertTrue(gate.requestSkip())
    }

    @Test
    fun expiredHintRequiresTwoRequestsAgain() {
        val gate = MovieSkipGate(isFirstView = true)
        assertFalse(gate.requestSkip())
        gate.expireHint(gate.hintToken)
        assertFalse(gate.hintVisible)
        assertFalse(gate.requestSkip())
        assertTrue(gate.requestSkip())
    }

    @Test
    fun staleExpiryDoesNotHideANewerHint() {
        val gate = MovieSkipGate(isFirstView = true)
        gate.requestSkip()
        val first = gate.hintToken
        gate.expireHint(first)
        gate.requestSkip()
        gate.expireHint(first)
        assertTrue(gate.hintVisible)
    }
}
