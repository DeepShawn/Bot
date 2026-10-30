package dev.qingreply.ui

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.qingreply.ai.AiClient
import dev.qingreply.ai.AiProviders
import dev.qingreply.data.*
import dev.qingreply.engine.MentionMatcher
import dev.qingreply.engine.NotificationSource
import dev.qingreply.notification.QqNotificationService
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Teal = Color(0xFF16796B)
private val Amber = Color(0xFFAD762A)

@Composable
fun BotScreen(vm: BotViewModel) {
    val dark = isSystemInDarkTheme()
    val colors = if (dark) darkColorScheme(primary = Color(0xFF76CDB8), onPrimary = Color(0xFF10392F))
        else lightColorScheme(primary = Teal, background = Color(0xFFF5F6F8), surface = Color.White)
    MiuixTheme(colors = colors) {
        val context = LocalContext.current
        val lifecycle = LocalLifecycleOwner.current
        var permission by remember { mutableStateOf(hasNotificationAccess(context)) }
        DisposableEffect(lifecycle) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) permission = hasNotificationAccess(context)
            }
            lifecycle.lifecycle.addObserver(observer)
            onDispose { lifecycle.lifecycle.removeObserver(observer) }
        }
        val saved by vm.saved.collectAsStateWithLifecycle()
        val connected by vm.connected.collectAsStateWithLifecycle()
        val stats by vm.stats.collectAsStateWithLifecycle()
        val events by vm.events.collectAsStateWithLifecycle()
        var page by rememberSaveable { mutableIntStateOf(0) }
        Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                AppMark()
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("轻答", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    Text("让每一次 @ 都有回应", fontSize = 10.sp, color = muted())
                }
                Pill("QQ · AI 助手", MiuixTheme.colorScheme.primary)
            }
            Box(Modifier.weight(1f)) {
                AnimatedContent(targetState = page, label = "page") { selected ->
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp).padding(top = 14.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        when (selected) {
                            0 -> Overview(saved, connected, permission, stats,
                                onToggle = { vm.toggle(it, permission) },
                                onPermission = { openSettings(context, Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, vm) },
                                onReconnect = { runCatching { QqNotificationService.reconnect(context) }
                                    .onSuccess { vm.message("已请求重新连接，请稍候") }.onFailure { vm.message("请重新授予通知使用权") } },
                                onApi = { page = 1 }, onRules = { page = 2 },
                                onBattery = { openSettings(context, Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, vm) })
                            1 -> ApiPage(vm)
                            2 -> RulesPage(vm, permission)
                            else -> EventsPage(events, vm::clearEvents)
                        }
                    }
                }
            }
            if (vm.notice.isNotBlank()) {
                Row(Modifier.fillMaxWidth().background(MiuixTheme.colorScheme.secondaryContainer)
                    .padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(vm.notice, Modifier.weight(1f), fontSize = 13.sp)
                    Text("关闭", Modifier.padding(start = 12.dp).clickable { vm.dismissNotice() },
                        fontSize = 13.sp, color = MiuixTheme.colorScheme.primary)
                }
            }
            BottomTabs(page) { page = it }
        }
    }
}

@Composable
private fun Overview(config: BotSettings, connected: Boolean, permission: Boolean, stats: BotStats,
    onToggle: (Boolean) -> Unit, onPermission: () -> Unit, onReconnect: () -> Unit,
    onApi: () -> Unit, onRules: () -> Unit, onBattery: () -> Unit) {
    PageTitle("留一点时间，给自己", "被叫到时，让 AI 帮你接上话。")
    val ready = permission && connected && config.startError() == null
    val active = ready && config.enabled
    val state = when {
        !permission -> "等待通知授权"
        config.startError() != null -> "完成配置，即可开始"
        !config.enabled -> "已暂停自动回复"
        !connected -> "正在等待监听连接"
        else -> "正在守候你的消息"
    }
    val heroColors = if (isSystemInDarkTheme()) listOf(Color(0xFF193E35), Color(0xFF263E39))
        else listOf(Color(0xFFDCECE2), Color(0xFFEEF2DC))
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(Brush.linearGradient(heroColors)).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).background(if (active) Teal else Amber, CircleShape))
            Text(if (active) "轻答在线" else "准备就绪后，轻轻开启", Modifier.padding(start = 8.dp),
                color = if (isSystemInDarkTheme()) Color(0xFFC1DCCE) else Color(0xFF45624C), fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            Icon(Icons.Default.Notifications, null, Modifier.size(25.dp), tint = MiuixTheme.colorScheme.primary)
        }
        Text(state, fontSize = 23.sp, fontWeight = FontWeight.Bold, lineHeight = 31.sp)
        Text("被 @ 后，由小号直接把 AI 回复发回原群。", fontSize = 13.sp,
            color = if (isSystemInDarkTheme()) Color(0xFFBAD4C7) else Color(0xFF58715F))
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("自动回复", Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Switch(checked = config.enabled, onCheckedChange = onToggle)
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatTile("通知事件", stats.received, Modifier.weight(1f))
        StatTile("@ 匹配", stats.matched, Modifier.weight(1f))
        StatTile("回复已提交", stats.submitted, Modifier.weight(1f))
    }
    SectionLabel("开始之前", "连接你的 QQ 小号")
    Panel {
        SetupRow("01", "通知使用权", if (permission) "已授权 · ${if (connected) "监听已连接" else "等待连接"}" else "读取 QQ 消息，并使用通知快捷回复",
            permission && connected, if (!permission) onPermission else if (!connected) onReconnect else onPermission)
        ThinLine()
        SetupRow("02", "连接 AI 服务", if (config.apiError() == null) config.model else "填写 API 地址、密钥与模型", config.apiError() == null, onApi)
        ThinLine()
        SetupRow("03", "设置小号的昵称", if (config.aliasList().isEmpty()) "填写被 @ 的小号昵称或群名片" else "已设置 ${config.aliasList().size} 个称呼 · ${config.cooldownSeconds} 秒冷却",
            config.aliasList().isNotEmpty(), onRules)
        ThinLine()
        SetupRow("04", "绑定小号通知实例", if (NotificationSource.parse(config.boundSource) == null) "支持区分小米双开中的 QQ 实例" else "已绑定 · 只回复这个实例的消息",
            NotificationSource.parse(config.boundSource) != null, onRules)
    }
    Note("自动回复到原群", "小号收到 @ → 同群上下文 + 系统提示词 → AI 生成 → 小号直接回复原群\n无需复制粘贴。开启后，问题及启用的上下文会发往配置的 API 服务。")
    Panel {
        Row(Modifier.fillMaxWidth().clickable(onClick = onBattery), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Settings, null, Modifier.size(20.dp), tint = muted())
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text("后台运行设置", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text("在系统中允许后台运行，避免监听中断", fontSize = 12.sp, color = muted(), modifier = Modifier.padding(top = 4.dp))
            }
            Text("›", color = muted(), fontSize = 24.sp)
        }
    }
    Text("优先使用通知快捷回复。QQ 未提供时，可在触发规则中启用无障碍发送。静音群、前台聊天或隐藏内容可能无法触发。", color = muted(), fontSize = 12.sp, lineHeight = 19.sp)
}

@Composable
private fun ApiPage(vm: BotViewModel) {
    val draft = vm.draft
    val provider = AiProviders.find(draft.providerId)
    PageTitle("给轻答一点灵感", "接入你习惯的 AI，保留自己的表达方式。")
    Pill("OpenAI 兼容接口", MiuixTheme.colorScheme.primary)
    SectionLabel("提供商模板", "自动填写地址与模型")
    Panel {
        AiProviders.all.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { item ->
                    TextButton(item.name, { vm.selectProvider(item) }, Modifier.weight(1f),
                        colors = if (item.id == draft.providerId) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors())
                }
            }
        }
        Text(provider.hint, color = muted(), fontSize = 12.sp, lineHeight = 19.sp)
        Text("切换服务商会清空当前密钥，请填写所选平台自己的 API Key。", color = muted(), fontSize = 11.sp, lineHeight = 18.sp)
    }
    Panel {
        Field("API 地址", draft.baseUrl, "https://api.openai.com/v1", keyboard = KeyboardType.Uri) { value -> vm.edit { it.copy(baseUrl = value) } }
        Text("填写以 /v1 结尾的基础地址，或完整的 /chat/completions 地址。仅支持 HTTPS。", color = muted(), fontSize = 12.sp, lineHeight = 18.sp)
        Field("API Key", draft.apiKey, "填写服务商提供的密钥", secret = true) { value -> vm.edit { it.copy(apiKey = value) } }
        Field("模型名称", draft.model, "填写平台实际开通的模型 / 接入点 ID") { value -> vm.edit { it.copy(model = value) } }
        if (provider.models.isNotEmpty()) {
            Text("常用模型", fontSize = 12.sp, color = muted())
            provider.models.forEach { model ->
                TextButton(model, { vm.edit { it.copy(model = model) } }, Modifier.fillMaxWidth(),
                    colors = if (draft.model == model) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors())
            }
        }
        TextButton(if (vm.fetchingModels) "正在获取模型…" else "用当前密钥获取模型", vm::fetchModels,
            Modifier.fillMaxWidth(), enabled = !vm.fetchingModels)
        if (vm.availableModels.isNotEmpty()) {
            var filter by remember { mutableStateOf("") }
            Field("搜索可用模型", filter, "输入模型名称筛选") { filter = it }
            val models = vm.availableModels.filter { it.contains(filter, ignoreCase = true) }
            models.take(12).forEach { model ->
                TextButton(model, { vm.edit { it.copy(model = model) } }, Modifier.fillMaxWidth())
            }
            Text("共 ${models.size} 项匹配，最多显示 12 项。模型是否可调用以平台授权为准。", color = muted(), fontSize = 11.sp, lineHeight = 18.sp)
        }
    }
    SectionLabel("回复风格", "一句话，定义它的性格")
    Panel {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PromptTemplates.all.forEach { template ->
                TextButton(template.name, { vm.edit { it.copy(systemPrompt = template.prompt) } }, Modifier.weight(1f))
            }
        }
        Field("系统提示词", draft.systemPrompt, "你希望 AI 如何回答？", multiline = true) { value -> vm.edit { it.copy(systemPrompt = value) } }
        Text("每次请求均以 system 角色注入已保存的提示词，不拼进用户问题。留空使用默认群聊助手。开启群聊上下文后会附带同群最近消息，回复最长 500 个字符。", color = muted(), fontSize = 12.sp, lineHeight = 18.sp)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton("保存配置", vm::save, Modifier.weight(1f), colors = ButtonDefaults.textButtonColorsPrimary())
        TextButton(if (vm.testing) "连接中…" else "测试连接", vm::test, Modifier.weight(1f), enabled = !vm.testing)
    }
    if (vm.testResult.isNotBlank()) Note("连接测试", vm.testResult)
    Note("连接测试不会发 QQ", "测试只检查 AI 接口；正式自动回复会通过小号发送到原群。API Key 使用 Android Keystore 加密保存。测试会调用一次模型，按服务商规则计费。")
    val endpoint = runCatching { AiClient.endpoint(draft.baseUrl).toString() }.getOrDefault("请填写有效的 HTTPS API 地址")
    Text("请求地址：$endpoint", color = muted(), fontSize = 11.sp, lineHeight = 18.sp)
}

@Composable
private fun InstanceBinding(vm: BotViewModel, permission: Boolean) {
    val saved by vm.saved.collectAsStateWithLifecycle()
    val sources by vm.sources.collectAsStateWithLifecycle()
    val bound = NotificationSource.parse(saved.boundSource)
    SectionLabel("绑定 QQ 小号", "小米应用双开")
    Panel {
        if (bound != null) {
            Pill("已绑定一个 QQ 实例", MiuixTheme.colorScheme.primary)
            Text("${bound.packageName}\nAndroid 用户 ${bound.userId} · UID ${bound.uid}", fontSize = 12.sp, color = muted(), lineHeight = 19.sp)
            TextButton("解除绑定并暂停回复", vm::unbind, Modifier.fillMaxWidth())
            ThinLine()
        }
        Text("1. QQ 双开里登录小号，打开通知和消息详情。\n2. 点击检测，用大号给小号发一条新消息。\n3. 在下方选择刚收到通知的实例，绑定为小号。", fontSize = 13.sp, lineHeight = 22.sp)
        Text("检测会暂停自动回复。只读取 QQ 通知来源和回复能力，不保存消息正文。这里绑定的是应用实例，请自行确认其中登录的是小号。", color = muted(), fontSize = 11.sp, lineHeight = 18.sp)
        TextButton(if (vm.captureSecondsLeft > 0) "正在检测 · 剩余 ${vm.captureSecondsLeft} 秒" else "检测小号的新通知（60 秒）",
            { vm.startCapture(permission) }, Modifier.fillMaxWidth(), enabled = vm.captureSecondsLeft == 0,
            colors = ButtonDefaults.textButtonColorsPrimary())
        if (vm.captureSecondsLeft > 0) TextButton("停止检测", vm::stopCapture, Modifier.fillMaxWidth())
        sources.forEach { candidate ->
            ThinLine()
            val source = candidate.source
            Text("QQ · Android 用户 ${source.userId}", fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Text("${source.packageName} · UID ${source.uid}\n" +
                "正文：${if (candidate.hasText) "可读取" else "不可用"} · 快捷回复：${if (candidate.canReply) "可用" else "未提供"}",
                color = muted(), fontSize = 11.sp, lineHeight = 19.sp)
            Text(candidate.diagnostic, color = muted(), fontSize = 11.sp, lineHeight = 18.sp)
            if (!candidate.canReply && candidate.hasText) Text("消息可以读取，仍可绑定。发送需要下方的无障碍方案。", color = MiuixTheme.colorScheme.primary, fontSize = 12.sp, lineHeight = 19.sp)
            val ready = candidate.hasText
            TextButton(if (source == bound) "当前已绑定" else if (ready) "将这个实例绑定为小号" else "请先开启 QQ 通知正文",
                { vm.bind(candidate) }, Modifier.fillMaxWidth(), enabled = ready && source != bound)
        }
    }
    Note("读取与发送分开配置", "识别到 Android 用户 999 说明已收到双开通知。没有快捷回复入口时，绑定后开启无障碍发送。若小号通知完全不可见，无障碍不能补齐通知监听的跨用户权限。")
}

@Composable
private fun AccessibilitySending(vm: BotViewModel) {
    val context = LocalContext.current
    val connected by vm.accessibilityConnected.collectAsStateWithLifecycle()
    val draft = vm.draft
    SectionLabel("无障碍发送", "手机端备用方案")
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("没有快捷回复时自动操作 QQ", fontSize = 15.sp)
                Text("打开小号原会话 → 核对群名 → 填写并点击发送。", color = muted(), fontSize = 12.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 5.dp))
            }
            Switch(draft.accessibilityFallback, { value -> vm.edit { it.copy(accessibilityFallback = value) } })
        }
        if (draft.accessibilityFallback) {
            Field("QQ 聊天页顶部的完整群名", draft.accessibilityChatTitle, "例如：测试群（请与 QQ 页顶部文字完全一致）") { value ->
                vm.edit { it.copy(accessibilityChatTitle = value) }
            }
            Pill(if (connected) "无障碍服务已连接" else "无障碍服务未连接", if (connected) MiuixTheme.colorScheme.primary else Amber)
            TextButton("打开系统无障碍设置", { openSettings(context, Settings.ACTION_ACCESSIBILITY_SETTINGS, vm) }, Modifier.fillMaxWidth())
            Text("开启“轻答 · QQ 自动发送”，小米如有拦截，还需在应用权限中允许后台弹出界面。安装来源受限制时，在应用信息中允许受限制的设置。修改后点击本页下方“保存规则”。", color = muted(), fontSize = 12.sp, lineHeight = 19.sp)
        }
        Text("需要保持解锁。发送时会把 QQ 切到前台，完成后回到桌面，便于接收下一条通知。只核对你设置的一个群名；不覆盖已有草稿，不解锁手机，不使用剪贴板或坐标盲点。QQ 界面版本不兼容时会停止并记录原因。", color = muted(), fontSize = 12.sp, lineHeight = 19.sp)
    }
}

@Composable
private fun RulesPage(vm: BotViewModel, permission: Boolean) {
    val draft = vm.draft
    var sample by rememberSaveable { mutableStateOf("") }
    var cooldown by rememberSaveable { mutableStateOf(draft.cooldownSeconds.toString()) }
    var hourly by rememberSaveable { mutableStateOf(draft.hourlyLimit.toString()) }
    PageTitle("被叫到，才回应", "把触发条件设清楚，让回复刚刚好。")
    InstanceBinding(vm, permission)
    AccessibilitySending(vm)
    SectionLabel("识别小号", "填写被 @ 的账号")
    Panel {
        Field("小号的昵称 / 群名片", draft.aliases, "每行一个，例如：\n小明的小号\n群聊助手", multiline = true) { value -> vm.edit { it.copy(aliases = value) } }
        Text("填写 QQ 通知里 @ 后的完整名称，无需输入 @。昵称后需有空格、标点或消息结束；不会把 @所有人 当作 @你。", color = muted(), fontSize = 12.sp, lineHeight = 19.sp)
        ThinLine()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("兼容“[有人@我]”标记", fontSize = 15.sp)
                Text("仅识别消息开头的标记。QQ 可能保留旧标记，建议先关闭。", color = muted(), fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 5.dp))
            }
            Switch(draft.allowMentionMarker, { value -> vm.edit { it.copy(allowMentionMarker = value) } })
        }
    }
    SectionLabel("群聊上下文", "按群与小号实例隔离")
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("结合最近聊天再回答", fontSize = 15.sp)
                Text("仅缓存绑定小号收到、且符合回复范围的消息。", color = muted(), fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 5.dp))
            }
            Switch(draft.contextEnabled, { value -> vm.edit { it.copy(contextEnabled = value) } })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(5, 10, 20).forEach { count ->
                TextButton("最近 $count 条", { vm.edit { it.copy(contextMessages = count) } }, Modifier.weight(1f),
                    enabled = draft.contextEnabled,
                    colors = if (draft.contextMessages == count) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors())
            }
        }
        Text("缓存只在本机内存中，最多使用最近 30 分钟、5000 字符；不能读取 QQ 完整历史。开启后，同群近期消息会随问题发送给你配置的 AI。保存后生效。", color = muted(), fontSize = 12.sp, lineHeight = 19.sp)
        TextButton("清空本机上下文缓存", vm::clearContext, Modifier.fillMaxWidth())
    }
    SectionLabel("回复范围", "留空表示所有 QQ 会话")
    Panel {
        Field("允许的群 / 会话名称", draft.allowedConversations, "与 QQ 通知标题完全一致，每行一个", multiline = true) { value -> vm.edit { it.copy(allowedConversations = value) } }
        Text("只处理已绑定 QQ 实例的通知；双开的另一个实例不会触发。此版本不监听 TIM 或第三方改包 QQ。", color = muted(), fontSize = 12.sp, lineHeight = 18.sp)
    }
    SectionLabel("回复节奏", "防重复，避免连续刷屏")
    Panel {
        Field("同一会话冷却（秒）", cooldown, "10～300", keyboard = KeyboardType.Number) { value ->
            cooldown = value.filter(Char::isDigit).take(3)
            cooldown.toIntOrNull()?.let { seconds -> vm.edit { it.copy(cooldownSeconds = seconds) } }
        }
        Field("每小时 AI 请求上限", hourly, "1～120", keyboard = KeyboardType.Number) { value ->
            hourly = value.filter(Char::isDigit).take(3)
            hourly.toIntOrNull()?.let { count -> vm.edit { it.copy(hourlyLimit = count) } }
        }
        Text("同一通知内容不会重复回复。请求失败也占用冷却和次数；开启、重连后不补回历史消息。", color = muted(), fontSize = 12.sp, lineHeight = 18.sp)
    }
    TextButton("保存规则", {
        val seconds = cooldown.toIntOrNull(); val limit = hourly.toIntOrNull()
        if (seconds == null || seconds !in 10..300 || limit == null || limit !in 1..120) vm.message("冷却应为 10～300 秒，每小时上限应为 1～120")
        else { vm.edit { it.copy(cooldownSeconds = seconds, hourlyLimit = limit) }; vm.save() }
    }, Modifier.fillMaxWidth(), colors = ButtonDefaults.textButtonColorsPrimary())
    SectionLabel("试试触发规则", "只在本地匹配，不发送消息")
    Panel {
        Field("模拟收到的消息", sample, "例如：@小明 今天有什么安排？", multiline = true) { sample = it }
        val matches = MentionMatcher.matches(sample, draft.aliasList(), draft.allowMentionMarker)
        Text(if (sample.isBlank()) "填写一条消息，检查是否能识别到你。" else if (matches) "✓ 会触发 @ 匹配" else "未匹配，不会回复", fontSize = 13.sp,
            color = if (sample.isNotBlank() && matches) MiuixTheme.colorScheme.primary else muted())
    }
}

@Composable
private fun EventsPage(events: List<BotEvent>, clear: () -> Unit) {
    PageTitle("每一次回应，都有迹可循", "保留最近 80 条状态，不记录聊天正文和密钥。")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Pill("本机记录 · ${events.size} 条", MiuixTheme.colorScheme.primary)
        Spacer(Modifier.weight(1f))
        TextButton("清空", clear, enabled = events.isNotEmpty())
    }
    if (events.isEmpty()) {
        Panel {
            Column(Modifier.fillMaxWidth().padding(vertical = 34.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.AutoMirrored.Filled.List, null, Modifier.size(38.dp), tint = muted())
                Text("安静等待第一条消息", Modifier.padding(top = 18.dp), fontWeight = FontWeight.Medium, fontSize = 17.sp)
                Text("开启自动回复后，运行状态会出现在这里。", color = muted(), fontSize = 12.sp, modifier = Modifier.padding(top = 9.dp))
            }
        }
    } else {
        Panel {
            events.forEachIndexed { index, event ->
                val color = when (event.kind) { EventKind.SUCCESS -> MiuixTheme.colorScheme.primary; EventKind.ERROR -> Color(0xFFD26C55); EventKind.SKIPPED -> Amber; else -> muted() }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.padding(top = 7.dp).size(7.dp).background(color, CircleShape))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(event.message, fontSize = 14.sp, lineHeight = 21.sp)
                        Text(SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date(event.time)), fontSize = 11.sp, color = muted())
                    }
                }
                if (index < events.lastIndex) ThinLine()
            }
        }
    }
    Note("排查没有回复", "确认监听已连接、昵称与小号群名片一致。通知有正文但没有快捷回复时，开启无障碍发送、授予权限并填写 QQ 页顶部群名；保持手机解锁，检查运行记录中的具体原因。")
}

@Composable
private fun BottomTabs(selected: Int, onSelect: (Int) -> Unit) {
    val labels = listOf("总览", "AI 配置", "触发规则", "运行记录")
    val icons = listOf(Icons.Default.Home, Icons.Default.Settings, Icons.Default.Notifications, Icons.AutoMirrored.Filled.List)
    Row(Modifier.fillMaxWidth().background(MiuixTheme.colorScheme.surface).padding(vertical = 8.dp)) {
        labels.forEachIndexed { index, label ->
            val color = if (selected == index) MiuixTheme.colorScheme.primary else muted()
            Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).clickable(role = Role.Tab) { onSelect(index) }
                .padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(Modifier.clip(RoundedCornerShape(10.dp)).background(if (selected == index) color.copy(alpha = 0.10f) else Color.Transparent)
                    .padding(horizontal = 17.dp, vertical = 4.dp)) {
                    Icon(icons[index], null, Modifier.size(21.dp), tint = color)
                }
                Text(label, fontSize = 11.sp, color = color, fontWeight = if (selected == index) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}

@Composable
private fun PageTitle(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, fontSize = 26.sp, fontWeight = FontWeight.Bold, lineHeight = 35.sp)
        Text(subtitle, fontSize = 13.sp, lineHeight = 20.sp, color = muted())
    }
}

@Composable
private fun AppMark() {
    Box(Modifier.size(38.dp).clip(RoundedCornerShape(13.dp)).background(Teal), contentAlignment = Alignment.Center) {
        Icon(Icons.Default.Email, null, Modifier.size(23.dp), tint = Color.White)
    }
}

@Composable
private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), cornerRadius = 22.dp, insideMargin = PaddingValues(18.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}

@Composable
private fun Pill(text: String, color: Color) {
    Text(text, Modifier.clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = 0.09f))
        .padding(horizontal = 9.dp, vertical = 6.dp), color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium)
}

@Composable
private fun StatTile(label: String, count: Int, modifier: Modifier) {
    Card(modifier, cornerRadius = 20.dp, insideMargin = PaddingValues(horizontal = 10.dp, vertical = 18.dp)) {
        Text(count.toString(), fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(label, color = muted(), fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun SectionLabel(title: String, hint: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(hint, color = muted(), fontSize = 11.sp)
    }
}

@Composable
private fun SetupRow(number: String, title: String, detail: String, done: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(33.dp).clip(RoundedCornerShape(11.dp)).background(MiuixTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
            Text(if (done) "✓" else number, color = if (done) MiuixTheme.colorScheme.primary else muted(), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(detail, fontSize = 11.sp, color = muted(), lineHeight = 17.sp, modifier = Modifier.padding(top = 4.dp))
        }
        Text("›", fontSize = 24.sp, color = muted())
    }
}

@Composable
private fun Field(title: String, value: String, hint: String, secret: Boolean = false,
    multiline: Boolean = false, keyboard: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit) {
    var visible by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.Medium)
            if (secret) Text(if (visible) "隐藏" else "显示", Modifier.clickable { visible = !visible }, color = MiuixTheme.colorScheme.primary, fontSize = 12.sp)
        }
        TextField(value, onChange, Modifier.fillMaxWidth().semantics { contentDescription = title }, label = hint,
            useLabelAsPlaceholder = true, singleLine = !multiline, minLines = if (multiline) 3 else 1,
            maxLines = if (multiline) 8 else 1,
            textStyle = TextStyle(fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurface, lineHeight = 22.sp),
            keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboard, autoCorrectEnabled = false),
            visualTransformation = if (secret && !visible) PasswordVisualTransformation() else VisualTransformation.None)
    }
}

@Composable
private fun Note(title: String, text: String) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
        .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.055f)).padding(17.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.primary)
        Text(text, color = muted(), fontSize = 12.sp, lineHeight = 20.sp)
    }
}

@Composable private fun ThinLine() { Box(Modifier.fillMaxWidth().height(0.5.dp).background(muted().copy(alpha = 0.12f))) }
@Composable private fun muted() = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.55f)

private fun hasNotificationAccess(context: Context): Boolean {
    val component = ComponentName(context, QqNotificationService::class.java)
    return if (Build.VERSION.SDK_INT >= 27) context.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(component)
    else Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        ?.split(':')?.any { ComponentName.unflattenFromString(it) == component } == true
}

private fun openSettings(context: Context, action: String, vm: BotViewModel) {
    runCatching { context.startActivity(Intent(action)) }.onFailure { vm.message("无法打开该系统设置，请在手机设置中手动查找通知使用权或电池设置") }
}
