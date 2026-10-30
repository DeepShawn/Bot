package dev.qingreply.engine

/** Persistent SHA-256 fingerprints and time reservations; never stores the message text. */
data class GateSnapshot(val seen: Map<String, Long>, val conversations: Map<String, Long>, val requests: List<Long>)
enum class GateResult { ALLOWED, DUPLICATE, COOLDOWN, HOURLY_LIMIT }

class ReplyGate(snapshot: GateSnapshot = GateSnapshot(emptyMap(), emptyMap(), emptyList())) {
    private val seen = snapshot.seen.toMutableMap()
    private val conversations = snapshot.conversations.toMutableMap()
    private val requests = snapshot.requests.toMutableList()

    @Synchronized
    fun reserve(fingerprint: String, conversation: String, now: Long, cooldownSeconds: Int, hourlyLimit: Int): GateResult {
        seen.entries.removeAll { now - it.value >= 24 * 60 * 60_000L }
        conversations.entries.removeAll { now - it.value >= 24 * 60 * 60_000L }
        requests.removeAll { now - it >= 60 * 60_000L }
        if (seen.containsKey(fingerprint)) return GateResult.DUPLICATE
        seen[fingerprint] = now
        if (seen.size > 1000) seen.remove(seen.minBy { it.value }.key)
        val last = conversations[conversation]
        if (last != null && now - last < cooldownSeconds * 1000L) return GateResult.COOLDOWN
        if (requests.size >= hourlyLimit) return GateResult.HOURLY_LIMIT
        conversations[conversation] = now
        requests.add(now)
        return GateResult.ALLOWED
    }

    @Synchronized
    fun snapshot() = GateSnapshot(seen.toMap(), conversations.toMap(), requests.toList())
}
