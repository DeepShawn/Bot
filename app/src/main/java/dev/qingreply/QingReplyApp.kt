package dev.qingreply

import android.app.Application
import dev.qingreply.ai.AiClient
import dev.qingreply.data.RuntimeStore
import dev.qingreply.data.SettingsStore
import dev.qingreply.engine.ConversationMemory

class QingReplyApp : Application() {
    val settings by lazy { SettingsStore(this) }
    val runtime by lazy { RuntimeStore(this) }
    val ai by lazy { AiClient() }
    val conversations = ConversationMemory()
}
