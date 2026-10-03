package com.stanleymasinde.hrmapp.ble

/** A CCCD belongs to a peer and a link, not to the broadcast button. */
internal class ReceiverSubscriptions {
    private val peers = linkedMapOf<String, Boolean>()
    var running = false
    val connected: Boolean get() = peers.isNotEmpty()
    val measurementNeeded: Boolean get() = running && peers.values.any { it }
    val subscribers: List<String> get() = if (running) peers.filterValues { it }.keys.toList() else emptyList()
    fun connect(address: String, bondedSubscription: Boolean = false) {
        peers.putIfAbsent(address, bondedSubscription)
    }
    fun subscribe(address: String, enabled: Boolean) { peers[address] = enabled }
    fun subscribed(address: String): Boolean = peers[address] == true
    fun disconnect(address: String) { peers.remove(address) }
    fun clear() { peers.clear() }
}
