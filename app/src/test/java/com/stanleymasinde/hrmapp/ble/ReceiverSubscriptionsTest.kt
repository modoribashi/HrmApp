package com.stanleymasinde.hrmapp.ble

import org.junit.Assert.*
import org.junit.Test

class ReceiverSubscriptionsTest {
    @Test fun pauseResumeKeepsTheSubscriptionOfAnExistingLink() {
        val state = ReceiverSubscriptions()
        state.running = true
        state.connect("bike")
        state.subscribe("bike", true)
        state.running = false
        assertTrue(state.connected)
        assertFalse(state.measurementNeeded)
        assertTrue(state.subscribed("bike"))
        assertTrue(state.subscribers.isEmpty())
        state.running = true
        assertTrue(state.measurementNeeded)
        assertEquals(listOf("bike"), state.subscribers)
    }

    @Test fun unbondedReconnectRequiresANewSubscription() {
        val state = ReceiverSubscriptions()
        state.running = true
        state.connect("bike")
        state.subscribe("bike", true)
        state.disconnect("bike")
        assertFalse(state.measurementNeeded)
        state.connect("bike")
        assertTrue(state.connected)
        assertFalse(state.subscribed("bike"))
        assertTrue(state.subscribers.isEmpty())
    }

    @Test fun AnotherPeerDisconnectCannotCancelTheBikeSubscription() {
        val state = ReceiverSubscriptions()
        state.running = true
        state.subscribe("bike", true)
        state.connect("phone")
        state.disconnect("phone")
        assertTrue(state.measurementNeeded)
        assertEquals(listOf("bike"), state.subscribers)
    }

    @Test fun duplicateConnectionCallbackDoesNotEraseAnAcknowledgedWrite() {
        val state = ReceiverSubscriptions()
        state.running = true
        state.subscribe("bike", true)
        state.connect("bike")
        assertTrue(state.measurementNeeded)
    }

    @Test fun bondedSubscriptionCanBeRestoredAndExplicitlyDisabled() {
        val state = ReceiverSubscriptions()
        state.running = true
        state.connect("bike", bondedSubscription = true)
        assertTrue(state.measurementNeeded)
        state.subscribe("bike", false)
        assertFalse(state.measurementNeeded)
        state.disconnect("bike")
        state.connect("bike", bondedSubscription = false)
        assertFalse(state.measurementNeeded)
    }
}
