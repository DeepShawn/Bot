package dev.qingreply.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import dev.qingreply.engine.SourceCandidate

enum class EventKind { INFO, SUCCESS, SKIPPED, ERROR }
data class BotEvent(val time: Long, val kind: EventKind, val message: String)
data class BotStats(val received: Int = 0, val matched: Int = 0, val submitted: Int = 0)

/** No message content, sender, conversation name or API key is written to logs. */
class RuntimeStore(context: Context) {
    private val prefs = context.getSharedPreferences("runtime", Context.MODE_PRIVATE)
    val connected = MutableStateFlow(false)
    val accessibilityConnected = MutableStateFlow(false)
    val captureUntil = MutableStateFlow(0L)
    private val _sources = MutableStateFlow<List<SourceCandidate>>(emptyList())
    val sources = _sources.asStateFlow()

    fun startCapture() { _sources.value = emptyList(); captureUntil.value = System.currentTimeMillis() + 60_000L }
    fun stopCapture() { captureUntil.value = 0L }
    fun observe(candidate: SourceCandidate) {
        if (System.currentTimeMillis() >= captureUntil.value) return
        _sources.value = (listOf(candidate) + _sources.value.filter { it.source != candidate.source }).take(12)
    }
    private val _events = MutableStateFlow(readEvents())
    val events = _events.asStateFlow()
    private val _stats = MutableStateFlow(BotStats(prefs.getInt("received", 0), prefs.getInt("matched", 0), prefs.getInt("submitted", 0)))
    val stats = _stats.asStateFlow()

    @Synchronized
    fun event(kind: EventKind, message: String) {
        _events.value = (listOf(BotEvent(System.currentTimeMillis(), kind, message)) + _events.value).take(80)
        persistEvents()
    }

    @Synchronized
    fun clearEvents() { _events.value = emptyList(); persistEvents() }

    @Synchronized
    fun count(received: Int = 0, matched: Int = 0, submitted: Int = 0) {
        val previous = _stats.value
        _stats.value = BotStats(previous.received + received, previous.matched + matched, previous.submitted + submitted)
        prefs.edit().putInt("received", _stats.value.received).putInt("matched", _stats.value.matched)
            .putInt("submitted", _stats.value.submitted).apply()
    }

    private fun readEvents(): List<BotEvent> = runCatching {
        val array = JSONArray(prefs.getString("events", "[]"))
        (0 until array.length()).map { index -> array.getJSONObject(index).let {
            BotEvent(it.getLong("time"), EventKind.valueOf(it.getString("kind")), it.getString("message"))
        } }.take(80)
    }.getOrDefault(emptyList())

    private fun persistEvents() {
        val array = JSONArray()
        _events.value.forEach { array.put(JSONObject().put("time", it.time).put("kind", it.kind.name).put("message", it.message)) }
        prefs.edit().putString("events", array.toString()).apply()
    }
}
