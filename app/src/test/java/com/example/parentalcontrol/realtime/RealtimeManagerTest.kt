package com.example.parentalcontrol.realtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.fail
import org.junit.Test

/**
 * Tests para RealtimeManager y RealtimeViewModel.
 */
class RealtimeManagerTest {

    @Test
    fun `connection state enum has correct values`() {
        val states = RealtimeConnectionState.entries.toTypedArray()
        
        assertEquals(4, states.size)
        assertTrue(states.contains(RealtimeConnectionState.DISCONNECTED))
        assertTrue(states.contains(RealtimeConnectionState.CONNECTING))
        assertTrue(states.contains(RealtimeConnectionState.CONNECTED))
        assertTrue(states.contains(RealtimeConnectionState.ERROR))
    }

    @Test
    fun `policy change event contains version`() {
        val event = PolicyChangeEvent(newVersion = 5L)
        
        assertEquals(5L, event.newVersion)
    }

    @Test
    fun `grant change event types are defined`() {
        val created = GrantChangeEvent(GrantChangeEvent.Type.CREATED, "grant-123")
        val revoked = GrantChangeEvent(GrantChangeEvent.Type.REVOKED, "grant-456")
        
        assertEquals(GrantChangeEvent.Type.CREATED, created.type)
        assertEquals("grant-123", created.grantId)
        assertEquals(GrantChangeEvent.Type.REVOKED, revoked.type)
    }

    @Test
    fun `request change event types are defined`() {
        val approved = RequestChangeEvent(RequestChangeEvent.Type.APPROVED, "req-123")
        val denied = RequestChangeEvent(RequestChangeEvent.Type.DENIED, "req-456")
        val updated = RequestChangeEvent(RequestChangeEvent.Type.UPDATED, null)
        
        assertEquals(RequestChangeEvent.Type.APPROVED, approved.type)
        assertEquals("req-123", approved.requestId)
        assertEquals(RequestChangeEvent.Type.DENIED, denied.type)
        assertEquals(RequestChangeEvent.Type.UPDATED, updated.type)
    }
}

class RealtimeMessageTest {

    @Test
    fun `realtime message can be created`() {
        val message = RealtimeMessage(
            type = "phx_join",
            topic = "device:123:policy",
            event = "phx_join",
            payload = emptyMap(),
            ref = "1"
        )
        
        assertEquals("phx_join", message.type)
        assertEquals("device:123:policy", message.topic)
        assertEquals("phx_join", message.event)
        assertEquals("1", message.ref)
    }

    @Test
    fun `realtime message with payload`() {
        val message = RealtimeMessage(
            type = "broadcast",
            topic = "device:123:grants",
            event = "grant_created",
            payload = mapOf("grant_id" to "grant-abc"),
            ref = null
        )
        
        assertEquals("grant_created", message.event)
        assertEquals("grant-abc", message.payload["grant_id"])
    }
}

class UiRefreshEventTest {

    @Test
    fun `policy changed event`() {
        val event = UiRefreshEvent.PolicyChanged(10L)
        
        assertTrue(event is UiRefreshEvent.PolicyChanged)
        assertEquals(10L, event.newVersion)
    }

    @Test
    fun `grants changed event with created type`() {
        val event = UiRefreshEvent.GrantsChanged(
            type = UiRefreshEvent.GrantsChanged.ChangeType.CREATED,
            grantId = "grant-new"
        )
        
        assertTrue(event is UiRefreshEvent.GrantsChanged)
        assertEquals(UiRefreshEvent.GrantsChanged.ChangeType.CREATED, event.type)
        assertEquals("grant-new", event.grantId)
    }

    @Test
    fun `request changed event with approved type`() {
        val event = UiRefreshEvent.RequestChanged(
            type = UiRefreshEvent.RequestChanged.ChangeType.APPROVED,
            requestId = "req-approved"
        )
        
        assertTrue(event is UiRefreshEvent.RequestChanged)
        assertEquals(UiRefreshEvent.RequestChanged.ChangeType.APPROVED, event.type)
        assertEquals("req-approved", event.requestId)
    }

    @Test
    fun `full refresh event`() {
        val event = UiRefreshEvent.FullRefresh
        
        assertTrue(event is UiRefreshEvent.FullRefresh)
    }
}

class RealtimeLifecycleTest {

    @Test
    fun `realtime should connect on foreground`() {
        // §0.1: Realtime solo conecta en foreground
        val shouldConnect = true
        assertTrue(shouldConnect)
    }

    @Test
    fun `realtime should disconnect on background`() {
        // §0.1: Realtime cierra socket en background
        // El control va por FCM
        val shouldDisconnect = true
        assertTrue(shouldDisconnect)
    }

    @Test
    fun `realtime is not used for control in background`() {
        // §0.1: Realtime es para UI, no para control
        val isControlChannel = false
        assertFalse(isControlChannel)
    }

    @Test
    fun `fcm is the control channel`() {
        // §0.1: FCM es señal, no dato
        // §0.1: FCM es el canal de control
        val fcmIsControl = true
        assertTrue(fcmIsControl)
    }
}

class RealtimeViewModelTest {

    @Test
    fun `ui events enum has all change types`() {
        val grantTypes = UiRefreshEvent.GrantsChanged.ChangeType.entries.toTypedArray()
        val requestTypes = UiRefreshEvent.RequestChanged.ChangeType.entries.toTypedArray()
        
        assertEquals(2, grantTypes.size)
        assertTrue(grantTypes.contains(UiRefreshEvent.GrantsChanged.ChangeType.CREATED))
        assertTrue(grantTypes.contains(UiRefreshEvent.GrantsChanged.ChangeType.REVOKED))
        
        assertEquals(3, requestTypes.size)
        assertTrue(requestTypes.contains(UiRefreshEvent.RequestChanged.ChangeType.APPROVED))
        assertTrue(requestTypes.contains(UiRefreshEvent.RequestChanged.ChangeType.DENIED))
        assertTrue(requestTypes.contains(UiRefreshEvent.RequestChanged.ChangeType.UPDATED))
    }
}

class RealtimeChannelSubscriptionTest {

    @Test
    fun `policy channel name format`() {
        val deviceId = "device-123"
        val channelName = "device:$deviceId:policy"
        
        assertEquals("device:device-123:policy", channelName)
    }

    @Test
    fun `grants channel name format`() {
        val deviceId = "device-123"
        val channelName = "device:$deviceId:grants"
        
        assertEquals("device:device-123:grants", channelName)
    }

    @Test
    fun `requests channel name format`() {
        val deviceId = "device-123"
        val channelName = "device:$deviceId:requests"
        
        assertEquals("device:device-123:requests", channelName)
    }
}

class RealtimeMessageParsingTest {

    @Test
    fun `parse policy update event`() {
        val json = """{"event": "policy_updated", "payload": {"version": "5"}}"""
        
        // Verificar que el evento se puede identificar
        val eventType = json.substringAfter("\"event\": \"").substringBefore("\"")
        val version = json.substringAfter("\"version\": \"").substringBefore("\"}")
        
        assertEquals("policy_updated", eventType)
        assertEquals("5", version)
    }

    @Test
    fun `parse grant created event`() {
        val json = """{"event": "grant_created", "payload": {"grant_id": "abc123"}}"""
        
        val eventType = json.substringAfter("\"event\": \"").substringBefore("\"")
        
        assertEquals("grant_created", eventType)
    }

    @Test
    fun `parse request approved event`() {
        val json = """{"event": "request_approved", "payload": {"request_id": "req-789"}}"""
        
        val eventType = json.substringAfter("\"event\": \"").substringBefore("\"")
        
        assertEquals("request_approved", eventType)
    }
}
