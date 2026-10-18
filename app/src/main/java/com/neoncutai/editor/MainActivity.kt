package com.neoncutai.editor

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
data class Project(
    val id: String, val name: String, val videoUri: String? = null,
    val audioUri: String? = null, val texts: List<String> = emptyList(),
    val startTrim: Int = 0, val endTrim: Int = 0,
    val effect: String? = null
)

data class ChatMessage(val text: String, val isUser: Boolean)

enum class ActionType { NONE, ADD_SUBTITLES, REMOVE_SILENCE, TRIM_VIDEO, ADD_TEXT, ADD_EFFECT, CHANGE_SPEED }
enum class ActionStatus { PENDING, CONFIRMED, CANCELLED, EXECUTED, ERROR }

data class AiProposal(
    val id: String = UUID.randomUUID().toString(), 
    val actionType: ActionType, 
    val explanation: String, 
    val status: ActionStatus = ActionStatus.PENDING, 
    val params: Map<String, String> = emptyMap()
)
data class AiResponse(val text: String, val proposal: AiProposal? = null, val errorMessage: String? = null)
data class CompletedProposal(val id: String, val actionType: ActionType, val status: ActionStatus, val timestamp: Long = System.currentTimeMillis())

sealed class JournalCheckResult { object Found : JournalCheckResult(); object NotFound : JournalCheckResult(); object Corrupted : JournalCheckResult(); data class Error(val message: String) : JournalCheckResult() }
sealed class SaveResult { object Success : SaveResult(); object AlreadyCompleted : SaveResult(); object Conflict : SaveResult(); data class Error(val message: String) : SaveResult() }
sealed class RecoveryResult { data class Recovered(val proposal: AiProposal?) : RecoveryResult(); data class Failed(val reason: String) : RecoveryResult() }
sealed class ProposalLoadResult { object Absent : ProposalLoadResult(); data class Success(val proposal: AiProposal) : ProposalLoadResult(); object Corrupted : ProposalLoadResult() }
interface AiEngine { suspend fun generateResponse(prompt: String, history: List<ChatMessage>, apiKey: String): AiResponse }

class LocalAiEngine : AiEngine {
    override suspend fun generateResponse(prompt: String, history: List<ChatMessage>, apiKey: String): AiResponse {
        val lower = prompt.lowercase()
        return when {
            lower.contains("неон") || lower.contains("свечен") -> 
                AiResponse("Включаю неоновый фильтр! Подтвердить?", AiProposal(actionType = ActionType.ADD_EFFECT, explanation = "Эффект: Неон", params = mapOf("effectName" to "Neon")))
            lower.contains("чб") || lower.contains("черно-бел") -> 
                AiResponse("Накладываю черно-белый фильтр. Подтвердить?", AiProposal(actionType = ActionType.ADD_EFFECT, explanation = "Эффект: ЧБ", params = mapOf("effectName" to "B&W")))
            lower.contains("ускор") -> 
                AiResponse("Ускоряю видео в 2 раза. Подтвердить?", AiProposal(actionType = ActionType.CHANGE_SPEED, explanation = "Скорость: 2x", params = mapOf("speedValue" to "2.0")))
            lower.contains("замедл") || lower.contains("слоу") -> 
                AiResponse("Замедляю видео (слоу-мо). Подтвердить?", AiProposal(actionType = ActionType.CHANGE_SPEED, explanation = "Скорость: 0.5x", params = mapOf("speedValue" to "0.5")))
            lower.contains("обрежь") -> {
                val num = Regex("\\d+").find(prompt)?.value?.toIntOrNull() ?: 5
                AiResponse("Обрезаю видео до $num секунд. Подтвердить?", AiProposal(actionType = ActionType.TRIM_VIDEO, explanation = "Обрезка", params = mapOf("endSeconds" to num.toString())))
            }
            lower.contains("текст") || lower.contains("надпись") -> {
                val txt = prompt.split(" ").lastOrNull() ?: "NEON"
                AiResponse("Добавляю текст '$txt' на видео. Подтвердить?", AiProposal(actionType = ActionType.ADD_TEXT, explanation = "Текст", params = mapOf("textContent" to txt)))
            }
            else -> AiResponse("Я твой локальный ИИ. Попроси меня: 'добавь неон', 'сделай ЧБ', 'ускорь видео', 'обрежь до 5 сек' или 'добавь текст Привет'.")
        }
    }
}

class CloudAiEngine : AiEngine {
    override suspend fun generateResponse(prompt: String, history: List<ChatMessage>, apiKey: String): AiResponse {
        if (apiKey.isBlank()) return AiResponse(text = "Ошибка: Введите ключ Groq.", errorMessage = "Ключ пуст")
        return withContext(Dispatchers.IO) {
            try {
                val client = OkHttpClient()
                val messages = JSONArray()
                val colors = "#FF0000, #00FF00, #0000FF, #FFFF00, #FF00FF, #00FFFF, #FFA500, #800080, #008000, #FFC0CB, #A52A2A, #808080, #000000, #FFFFFF, #4B0082, #FF4500, #2E8B57, #DAA520, #8A2BE2, #DC143C, #40E0D0, #EE82EE, #F5DEB3, #98FB98"
                
                messages.put(JSONObject().put("role", "system").put("content", 
                    "Ты — ИИ-режиссер в NEON CUT AI. Если просят 'автомонтаж' или изменить видео, верни СТРОГО JSON-массив действий. " +
                    "Действия: ADD_EFFECT (цвет из списка: $colors, или 'Neon', 'B&W'), ADD_TEXT, CHANGE_SPEED (0.5, 2.0, 10.0), TRIM_VIDEO. " +
                    "Пример: [{\"action\": \"ADD_EFFECT\", \"effect\": \"#FF00FF\"}, {\"action\": \"ADD_TEXT\", \"text\": \"EPIC\"}, {\"action\": \"CHANGE_SPEED\", \"speed\": 2.0}]. " +
                    "Если просто болтают, отвечай текстом."
                ))
                
                history.takeLast(10).forEach { msg -> messages.put(JSONObject().put("role", if (msg.isUser) "user" else "assistant").put("content", msg.text)) }
                messages.put(JSONObject().put("role", "user").put("content", prompt))

                val json = JSONObject().put("model", "llama-3.3-70b-versatile").put("messages", messages)
                val request = Request.Builder().url("https://api.groq.com/openai/v1/chat/completions").addHeader("Authorization", "Bearer $apiKey").post(json.toString().toRequestBody("application/json".toMediaType())).build()
                
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) return@withContext AiResponse(text = "Ошибка сети", errorMessage = "Код ${response.code}")

                val rawText = JSONObject(response.body?.string() ?: "").getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim().replace("```json", "").replace("```", "").trim()
                
                if (rawText.startsWith("[")) {
                    val actionsArray = JSONArray(rawText)
                    val proposals = mutableListOf<String>()
                    for (i in 0 until actionsArray.length()) {
                        val cmd = actionsArray.getJSONObject(i)
                        when (cmd.optString("action")) {
                            "ADD_EFFECT" -> proposals.add("Наложить эффект ${cmd.optString("effect", "Neon")}")
                            "ADD_TEXT" -> proposals.add("Добавить текст '${cmd.optString("text", "NEON")}'")
                            "CHANGE_SPEED" -> proposals.add("Скорость ${cmd.optDouble("speed", 2.0)}x")
                            "TRIM_VIDEO" -> proposals.add("Обрезать до ${cmd.optInt("seconds", 5)} сек")
                        }
                    }
                    return@withContext AiResponse(
                        text = "🎬 Я подготовил план автомонтажа:\n- ${proposals.joinToString("\n- ")}\n\nПрименить всё это?",
                        proposal = AiProposal(actionType = ActionType.NONE, explanation = "Автомонтаж", params = mapOf("jsonArray" to rawText))
                    )
                }
                AiResponse(text = rawText)
            } catch (e: Exception) { AiResponse(text = "Ошибка подключения.", errorMessage = e.message) }
        }
    }
}

class AiManager(private val local: AiEngine, private val cloud: AiEngine) {
    suspend fun getResponse(prompt: String, history: List<ChatMessage>, allowCloud: Boolean, apiKey: String): AiResponse {
        return if (allowCloud) cloud.generateResponse(prompt, history, apiKey) else local.generateResponse(prompt, history, apiKey)
    }
}
fun saveChatHistory(context: Context, projectId: String, messages: List<ChatMessage>): Boolean {
    return try {
        val arr = JSONArray(); for (msg in messages) arr.put(JSONObject().put("text", msg.text).put("isUser", msg.isUser))
        context.getSharedPreferences("neon_cut_chat_$projectId", Context.MODE_PRIVATE).edit().putString("history", arr.toString()).apply()
        true
    } catch (e: Exception) { false }
}

fun loadChatHistory(context: Context, projectId: String): List<ChatMessage> {
    val list = mutableListOf<ChatMessage>()
    try {
        val arr = JSONArray(context.getSharedPreferences("neon_cut_chat_$projectId", Context.MODE_PRIVATE).getString("history", "[]") ?: "[]")
        for (i in 0 until arr.length()) list.add(ChatMessage(arr.getJSONObject(i).getString("text"), arr.getJSONObject(i).getBoolean("isUser")))
    } catch (e: Exception) { }
    return list
}

fun saveProjects(context: Context, projects: List<Project>) {
    val arr = JSONArray()
    for (p in projects) {
        val obj = JSONObject().put("id", p.id).put("name", p.name).put("videoUri", p.videoUri ?: "").put("audioUri", p.audioUri ?: "").put("startTrim", p.startTrim).put("endTrim", p.endTrim).put("effect", p.effect ?: "")
        val tArr = JSONArray(); for (t in p.texts) tArr.put(t)
        obj.put("texts", tArr); arr.put(obj)
    }
    context.getSharedPreferences("neon_cut_prefs", Context.MODE_PRIVATE).edit().putString("projects_data", arr.toString()).apply()
}

fun loadProjects(context: Context): List<Project> {
    val list = mutableListOf<Project>()
    try {
        val arr = JSONArray(context.getSharedPreferences("neon_cut_prefs", Context.MODE_PRIVATE).getString("projects_data", "[]") ?: "[]")
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i); val tArr = obj.optJSONArray("texts") ?: JSONArray()
            val tList = mutableListOf<String>(); for (j in 0 until tArr.length()) tList.add(tArr.getString(j))
            list.add(Project(obj.getString("id"), obj.getString("name"), obj.optString("videoUri").takeIf { it.isNotEmpty() }, obj.optString("audioUri").takeIf { it.isNotEmpty() }, tList, obj.optInt("startTrim", 0), obj.optInt("endTrim", 0), obj.optString("effect").takeIf { it.isNotEmpty() }))
        }
    } catch (e: Exception) { }
    return list
}

fun formatTime(ms: Int): String = String.format("%02d:%02d", (ms / (1000 * 60)) % 60, (ms / 1000) % 60)
val dbMutex = Mutex()

fun internalCheckJournal(context: Context, projectId: String, proposalId: String): JournalCheckResult {
    val raw = context.getSharedPreferences("neon_cut_journal_$projectId", Context.MODE_PRIVATE).getString("journal", "[]") ?: "[]"
    return try {
        val arr = JSONArray(raw)
        for (i in 0 until arr.length()) { if (arr.getJSONObject(i).optString("id") == proposalId) return JournalCheckResult.Found }
        JournalCheckResult.NotFound
    } catch (e: Exception) { JournalCheckResult.Corrupted }
}

fun internalLoadProposal(context: Context, projectId: String): ProposalLoadResult {
    val str = context.getSharedPreferences("neon_cut_action_$projectId", Context.MODE_PRIVATE).getString("pending_proposal", null) ?: return ProposalLoadResult.Absent
    return try {
        val obj = JSONObject(str)
        ProposalLoadResult.Success(AiProposal(obj.getString("id"), ActionType.valueOf(obj.getString("type")), obj.getString("explanation"), ActionStatus.valueOf(obj.getString("status"))))
    } catch (e: Exception) { ProposalLoadResult.Corrupted }
}

suspend fun checkJournal(context: Context, projectId: String, proposalId: String): JournalCheckResult = withContext(Dispatchers.IO) { dbMutex.withLock { internalCheckJournal(context, projectId, proposalId) } }

suspend fun saveCompletedProposal(context: Context, projectId: String, completed: CompletedProposal): SaveResult = withContext(Dispatchers.IO) {
    dbMutex.withLock {
        val prefs = context.getSharedPreferences("neon_cut_journal_$projectId", Context.MODE_PRIVATE)
        val rawString = prefs.getString("journal", "[]") ?: "[]"
        val oldArray = try { JSONArray(rawString) } catch (e: Exception) { prefs.edit().putString("journal_corrupt_backup_${System.currentTimeMillis()}", rawString).commit(); return@withLock SaveResult.Error("Ошибка журнала") }
        val cleanArray = JSONArray(); var existingStatus: String? = null
        for (i in 0 until oldArray.length()) {
            try {
                val obj = oldArray.getJSONObject(i)
                if (obj.getString("id") == completed.id) existingStatus = obj.optString("status")
                cleanArray.put(obj)
            } catch (e: Exception) { return@withLock SaveResult.Error("Журнал сломан") }
        }
        if (existingStatus != null) return@withLock if (existingStatus == completed.status.name) SaveResult.AlreadyCompleted else SaveResult.Conflict
        cleanArray.put(JSONObject().put("id", completed.id).put("type", completed.actionType.name).put("status", completed.status.name).put("timestamp", completed.timestamp))
        if (prefs.edit().putString("journal", cleanArray.toString()).commit()) SaveResult.Success else SaveResult.Error("Ошибка commit")
    }
}

suspend fun saveProposal(context: Context, projectId: String, proposal: AiProposal?): Boolean = withContext(Dispatchers.IO) {
    dbMutex.withLock {
        val prefs = context.getSharedPreferences("neon_cut_action_$projectId", Context.MODE_PRIVATE)
        try {
            if (proposal == null) prefs.edit().remove("pending_proposal").commit()
            else prefs.edit().putString("pending_proposal", JSONObject().put("id", proposal.id).put("type", proposal.actionType.name).put("explanation", proposal.explanation).put("status", proposal.status.name).toString()).commit()
        } catch (e: Exception) { false }
    }
}

suspend fun setNeedsRecovery(context: Context, projectId: String, value: Boolean): Boolean = withContext(Dispatchers.IO) {
    dbMutex.withLock { context.getSharedPreferences("neon_cut_action_$projectId", Context.MODE_PRIVATE).edit().putBoolean("needs_recovery", value).commit() }
}

suspend fun recoverPendingProposal(context: Context, projectId: String): RecoveryResult = withContext(Dispatchers.IO) {
    dbMutex.withLock {
        val prefsAction = context.getSharedPreferences("neon_cut_action_$projectId", Context.MODE_PRIVATE)
        val pendingResult = internalLoadProposal(context, projectId)
        if (pendingResult is ProposalLoadResult.Corrupted) return@withLock RecoveryResult.Failed("Proposal поврежден")
        if (prefsAction.getBoolean("needs_recovery", false)) {
            if (!prefsAction.edit().remove("pending_proposal").remove("needs_recovery").commit()) return@withLock RecoveryResult.Failed("Ошибка очистки")
            return@withLock RecoveryResult.Recovered(null)
        }
        when (pendingResult) {
            is ProposalLoadResult.Success -> {
                val check = internalCheckJournal(context, projectId, pendingResult.proposal.id)
                if (check is JournalCheckResult.Found) {
                    if (!prefsAction.edit().remove("pending_proposal").commit()) return@withLock RecoveryResult.Failed("Ошибка очистки")
                    RecoveryResult.Recovered(null)
                } else if (check is JournalCheckResult.Corrupted) RecoveryResult.Failed("Ошибка журнала")
                else RecoveryResult.Recovered(pendingResult.proposal)
            }
            else -> RecoveryResult.Recovered(null)
        }
    }
}
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Color(0xFF090909), surface = Color(0xFF1A1A1A), primary = Color(0xFF00FFFF))) {
                val context = LocalContext.current
                var projects by remember { mutableStateOf(loadProjects(context)) }
                var selectedProject by remember { mutableStateOf<Project?>(null) }
                LaunchedEffect(projects) { saveProjects(context, projects) }

                if (selectedProject == null) {
                    ProjectScreen(projects, { selectedProject = it }, { projects = projects + it }, { projects = projects.filter { p -> p.id != it.id } })
                } else {
                    EditorScreen(selectedProject!!) { updatedProject ->
                        projects = projects.map { if (it.id == updatedProject.id) updatedProject else it }
                        selectedProject = null
                    }
                }
            }
        }
    }
}

@Composable
fun ProjectScreen(projects: List<Project>, onProjectClick: (Project) -> Unit, onAddProject: (Project) -> Unit, onDeleteProject: (Project) -> Unit) {
    var showDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    Scaffold(
        containerColor = Color(0xFF090909),
        topBar = { Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { Text("NEON CUT AI", color = Color(0xFF00FFFF), fontSize = 28.sp, fontWeight = FontWeight.Bold) } },
        floatingActionButton = { FloatingActionButton(onClick = { showDialog = true }, containerColor = Color(0xFFFF00FF)) { Text("+", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold) } }
    ) { paddingValues ->
        Column(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
            if (projects.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Нет проектов\nНажмите +", color = Color.Gray, fontSize = 18.sp, textAlign = TextAlign.Center) }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(projects) { project ->
                        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clickable { onProjectClick(project) }, colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A))) {
                            Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(project.name, color = Color.White, fontSize = 18.sp, modifier = Modifier.weight(1f))
                                TextButton(onClick = { onDeleteProject(project) }) { Text("Удалить", color = Color(0xFFFF00FF)) }
                            }
                        }
                    }
                }
            }
        }
    }
    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false }, title = { Text("Новый проект", color = Color.White) },
            text = { OutlinedTextField(value = newName, onValueChange = { newName = it }, label = { Text("Название") }, singleLine = true) },
            confirmButton = { TextButton(onClick = { if (newName.isNotBlank()) { onAddProject(Project(UUID.randomUUID().toString(), newName)); newName = ""; showDialog = false } }) { Text("Создать", color = Color(0xFF00FFFF)) } },
            dismissButton = { TextButton(onClick = { showDialog = false }) { Text("Отмена", color = Color.Gray) } }
        )
    }
}
@Composable
fun EditorScreen(initialProject: Project, onBackClick: (Project) -> Unit) {
    val context = LocalContext.current
    var project by remember { mutableStateOf(initialProject) }
    var mediaPlayerInstance by remember { mutableStateOf<MediaPlayer?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var videoDuration by remember { mutableStateOf(0) }
    var videoViewInstance by remember { mutableStateOf<VideoView?>(null) }
    
    var videoPosition by rememberSaveable(project.id) { mutableStateOf(0) }
    var startTrim by rememberSaveable(project.id) { mutableStateOf(project.startTrim) }
    var endTrim by rememberSaveable(project.id) { mutableStateOf(project.endTrim) }
    var playbackSpeed by rememberSaveable(project.id) { mutableStateOf(1.0f) }
    var isLooping by rememberSaveable(project.id) { mutableStateOf(false) }
    var showSpeedDialog by remember { mutableStateOf(false) }
    
    var showTextDialog by remember { mutableStateOf(false) }
    var newText by remember { mutableStateOf("") }
    var isExporting by remember { mutableStateOf(false) }
    var exportProgress by remember { mutableStateOf(0f) }
    var exportFact by remember { mutableStateOf("Подготовка...") }
    
    var showAiDialog by remember { mutableStateOf(false) }
    var allowCloud by remember { mutableStateOf(false) }
    var apiKey by remember { mutableStateOf(context.getSharedPreferences("neon_prefs", Context.MODE_PRIVATE).getString("gemini_key", "") ?: "") }
    
    var pendingProposal by remember(project.id) { mutableStateOf<AiProposal?>(null) }
    var aiMessages by remember(project.id) { mutableStateOf(loadChatHistory(context, project.id)) }
    var isAiLoading by remember(project.id) { mutableStateOf(false) }
    var activeAiJob by remember(project.id) { mutableStateOf<Job?>(null) }
    var currentRequestId by remember(project.id) { mutableStateOf<String?>(null) }
    var saveErrorMessage by remember(project.id) { mutableStateOf<String?>(null) }
    var recoveryFailed by remember(project.id) { mutableStateOf(false) }
    
    val coroutineScope = rememberCoroutineScope()
    val aiManager = remember { AiManager(LocalAiEngine(), CloudAiEngine()) }
    val facts = listOf("Это приложение пишется прямо на телефоне!", "NEON CUT AI использует нативные технологии Android.", "Первый видеоредактор появился еще в 1971 году!", "Твой телефон отлично справляется с задачей!")

    LaunchedEffect(project.id) {
        when (val rec = recoverPendingProposal(context, project.id)) {
            is RecoveryResult.Recovered -> { pendingProposal = rec.proposal; recoveryFailed = false }
            is RecoveryResult.Failed -> { recoveryFailed = true; saveErrorMessage = "Ошибка восстановления: ${rec.reason}" }
        }
    }

    DisposableEffect(project.id) {
        onDispose { activeAiJob?.cancel(); activeAiJob = null; currentRequestId = null; isAiLoading = false; saveErrorMessage = null }
    }

    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? -> if (uri != null) { project = project.copy(videoUri = uri.toString()); videoViewInstance?.setVideoURI(uri); videoViewInstance?.start() } }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? -> if (uri != null) project = project.copy(audioUri = uri.toString()) }

    LaunchedEffect(isPlaying, videoViewInstance, endTrim, isLooping) {
        while (isPlaying && videoViewInstance != null) {
            videoPosition = videoViewInstance!!.currentPosition
            if (videoPosition >= endTrim && endTrim > 0) {
                if (isLooping) {
                    videoViewInstance?.seekTo(startTrim)
                } else {
                    videoViewInstance?.pause()
                    isPlaying = false
                    videoViewInstance?.seekTo(endTrim)
                }
            }
            delay(50)
        }
    }

    LaunchedEffect(isExporting) {
        if (isExporting) {
            exportProgress = 0f; var factIndex = 0; exportFact = facts[factIndex]
            while (exportProgress < 1f) {
                delay(150); exportProgress += 0.02f
                if (exportProgress % 0.1f < 0.02f) { factIndex = (factIndex + 1) % facts.size; exportFact = facts[factIndex] }
            }
            delay(300); isExporting = false
        }
    }
Column(modifier = Modifier.fillMaxSize().background(Color(0xFF090909)).statusBarsPadding()) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { onBackClick(project.copy(startTrim = startTrim, endTrim = endTrim)) }) { Text("< Назад", color = Color(0xFF00FFFF), fontSize = 16.sp, fontWeight = FontWeight.Bold) }
        Spacer(modifier = Modifier.weight(1f))
        Button(onClick = { isExporting = true }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00FF00)), shape = RoundedCornerShape(20.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), modifier = Modifier.height(36.dp)) { Text("Экспорт", color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
    }

    Box(modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp).background(Color.Black, RoundedCornerShape(12.dp)).border(1.dp, Color(0xFF1A1A1A), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
        if (project.videoUri == null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("🎬", fontSize = 48.sp, modifier = Modifier.padding(bottom = 8.dp))
                Text("Нажмите 'Видео' чтобы начать", color = Color.Gray, textAlign = TextAlign.Center, fontSize = 14.sp)
            }
        } else {
            AndroidView(factory = { ctx ->
                VideoView(ctx).apply {
                    setVideoURI(Uri.parse(project.videoUri))
                    setOnPreparedListener { mp ->
                        videoDuration = mp.duration
                        if (endTrim == 0) endTrim = mp.duration
                        videoViewInstance = this
                        mediaPlayerInstance = mp
                        seekTo(if (videoPosition > 0) videoPosition else startTrim)
                        try { val params = mp.playbackParams; params.speed = playbackSpeed; mp.playbackParams = params } catch (e: Exception) {}
                        start()
                        isPlaying = true
                    }
                    setOnCompletionListener { isPlaying = false }
                    start()
                }
            }, modifier = Modifier.fillMaxSize())

            if (project.effect != null) {
                if (project.effect.startsWith("#")) {
                    Box(modifier = Modifier.fillMaxSize().background(Color(android.graphics.Color.parseColor(project.effect)).copy(alpha = 0.4f)))
                } else if (project.effect == "Neon") {
                    Box(modifier = Modifier.fillMaxSize().border(3.dp, Color(0xFF00FFFF)).background(Color(0xFF00FFFF).copy(alpha = 0.15f)))
                } else if (project.effect == "B&W") {
                    Box(modifier = Modifier.fillMaxSize().background(Color.Gray.copy(alpha = 0.8f)))
                }
            }

            project.texts.forEach { text -> Text(text = text, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 50.dp)) }
        }
    }

    if (project.videoUri != null) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).background(Color(0xFF121212), RoundedCornerShape(12.dp)).padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Button(onClick = { videoViewInstance?.let { if (it.isPlaying) { it.pause(); isPlaying = false } else { it.start(); isPlaying = true } } }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A1A1A)), modifier = Modifier.border(1.dp, Color(0xFF00FFFF), RoundedCornerShape(8.dp)).height(36.dp)) { Text(if (isPlaying) "⏸ Пауза" else "▶ Играть", color = Color(0xFF00FFFF), fontSize = 12.sp) }
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Loop", color = Color.Gray, fontSize = 10.sp, modifier = Modifier.padding(end = 4.dp))
                    Switch(checked = isLooping, onCheckedChange = { isLooping = it }, modifier = Modifier.scale(0.7f), colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF00FFFF)))
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = { showSpeedDialog = true }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00FFFF)), modifier = Modifier.height(36.dp), shape = RoundedCornerShape(8.dp)) { Text("${String.format("%.2f", playbackSpeed)}x", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatTime(videoPosition), color = Color.Gray, fontSize = 11.sp, modifier = Modifier.width(36.dp))
                Slider(value = videoPosition.toFloat(), onValueChange = { videoPosition = it.toInt(); videoViewInstance?.seekTo(it.toInt()) }, valueRange = 0f..(if (videoDuration > 0) videoDuration.toFloat() else 1f), modifier = Modifier.weight(1f).padding(horizontal = 8.dp), colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.Gray))
                Text(formatTime(videoDuration), color = Color.Gray, fontSize = 11.sp, modifier = Modifier.width(36.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Нач", color = Color(0xFF00FFFF), fontSize = 11.sp, modifier = Modifier.width(30.dp))
                Slider(value = startTrim.toFloat(), onValueChange = { if (it < endTrim) { startTrim = it.toInt(); videoViewInstance?.seekTo(startTrim) } }, valueRange = 0f..(if (videoDuration > 0) videoDuration.toFloat() else 1f), modifier = Modifier.weight(1f), colors = SliderDefaults.colors(thumbColor = Color(0xFF00FFFF), activeTrackColor = Color(0xFF00FFFF).copy(alpha = 0.5f)))
                Text(formatTime(startTrim), color = Color.White, fontSize = 11.sp, modifier = Modifier.width(40.dp), textAlign = TextAlign.End)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Кон", color = Color(0xFFFF00FF), fontSize = 11.sp, modifier = Modifier.width(30.dp))
                Slider(value = endTrim.toFloat(), onValueChange = { if (it > startTrim) { endTrim = it.toInt() } }, valueRange = 0f..(if (videoDuration > 0) videoDuration.toFloat() else 1f), modifier = Modifier.weight(1f), colors = SliderDefaults.colors(thumbColor = Color(0xFFFF00FF), activeTrackColor = Color(0xFFFF00FF).copy(alpha = 0.5f)))
                Text(formatTime(endTrim), color = Color.White, fontSize = 11.sp, modifier = Modifier.width(40.dp), textAlign = TextAlign.End)
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth().height(80.dp).background(Color(0xFF121212)).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(modifier = Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Видео", color = Color.Gray, fontSize = 9.sp, modifier = Modifier.width(40.dp))
            Box(modifier = Modifier.fillMaxHeight().width(if (project.videoUri != null) 150.dp else 80.dp).background(Color(0xFF00FFFF).copy(alpha = 0.2f), RoundedCornerShape(4.dp)).border(1.dp, Color(0xFF00FFFF), RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
                if (project.videoUri != null) Text(formatTime(endTrim - startTrim), color = Color.White, fontSize = 10.sp) else Text("Пусто", color = Color.DarkGray, fontSize = 10.sp)
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Аудио", color = Color.Gray, fontSize = 9.sp, modifier = Modifier.width(40.dp))
            Box(modifier = Modifier.fillMaxHeight().width(if (project.audioUri != null) 180.dp else 80.dp).background(Color(0xFFFF00FF).copy(alpha = 0.2f), RoundedCornerShape(4.dp)).border(1.dp, Color(0xFFFF00FF), RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
                if (project.audioUri != null) Text("Музыка", color = Color.White, fontSize = 10.sp) else Text("Нет", color = Color.DarkGray, fontSize = 10.sp)
            }
        }
    }

    Row(modifier = Modifier.fillMaxWidth().background(Color(0xFF090909)).padding(vertical = 12.dp, horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { videoPicker.launch("video/*") }) {
            Box(modifier = Modifier.size(44.dp).background(Color(0xFF1A1A1A), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { Text("📹", fontSize = 20.sp) }
            Text("Видео", color = Color.White, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { audioPicker.launch("audio/*") }) {
            Box(modifier = Modifier.size(44.dp).background(Color(0xFF1A1A1A), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { Text("🎵", fontSize = 20.sp) }
            Text("Музыка", color = Color.White, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { showTextDialog = true }) {
            Box(modifier = Modifier.size(44.dp).background(Color(0xFF1A1A1A), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { Text("T", color = Color(0xFF00FFFF), fontSize = 20.sp, fontWeight = FontWeight.Bold) }
            Text("Текст", color = Color.White, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { showAiDialog = true }) {
            Box(modifier = Modifier.size(44.dp).background(Color(0xFF1A1A1A), RoundedCornerShape(12.dp)).border(1.dp, Color(0xFFFF00FF), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { Text("✨", fontSize = 20.sp) }
            Text("ИИ", color = Color.White, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

if (showTextDialog) {
    AlertDialog(onDismissRequest = { showTextDialog = false }, title = { Text("Добавить текст", color = Color.White) }, text = { OutlinedTextField(value = newText, onValueChange = { newText = it }, label = { Text("Текст") }, singleLine = true) }, confirmButton = { TextButton(onClick = { if (newText.isNotBlank()) { project = project.copy(texts = project.texts + newText); newText = ""; showTextDialog = false } }) { Text("Добавить", color = Color(0xFF00FFFF)) } }, dismissButton = { TextButton(onClick = { showTextDialog = false }) { Text("Отмена", color = Color.Gray) } })
}

if (showSpeedDialog) {
    AlertDialog(
        onDismissRequest = { showSpeedDialog = false },
        title = { Text("Скорость: ${String.format("%.2f", playbackSpeed)}x", color = Color.White) },
        text = {
            Column {
                Slider(value = playbackSpeed, onValueChange = { newSpeed -> playbackSpeed = newSpeed; mediaPlayerInstance?.let { mp -> try { val params = mp.playbackParams; params.speed = newSpeed; mp.playbackParams = params } catch (e: Exception) {} } }, valueRange = 0.01f..100f, colors = SliderDefaults.colors(thumbColor = Color(0xFF00FFFF), activeTrackColor = Color(0xFF00FFFF)))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    listOf(0.1f, 1.0f, 2.0f, 10.0f, 50.0f, 100.0f).forEach { spd ->
                        TextButton(onClick = { playbackSpeed = spd; mediaPlayerInstance?.let { mp -> try { val params = mp.playbackParams; params.speed = spd; mp.playbackParams = params } catch (e: Exception) {} } }) { Text("${spd}x", color = Color.Gray, fontSize = 11.sp) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { showSpeedDialog = false }) { Text("Готово", color = Color(0xFF00FFFF)) } }
    )
}

if (isExporting) {
    AlertDialog(onDismissRequest = { }, title = { Text("Экспорт видео", color = Color.White) }, text = { Column { Text(text = exportFact, color = Color(0xFF00FFFF), fontSize = 14.sp, modifier = Modifier.padding(bottom = 16.dp)); LinearProgressIndicator(progress = exportProgress, modifier = Modifier.fillMaxWidth(), color = Color(0xFF00FF00), trackColor = Color.DarkGray) } }, confirmButton = { if (exportProgress >= 1f) { TextButton(onClick = { isExporting = false }) { Text("Готово", color = Color(0xFF00FF00)) } } })
}
    if (showAiDialog) {
        AiAssistantDialog(
            onDismiss = { showAiDialog = false }, aiMessages = aiMessages, isAiLoading = isAiLoading,
            allowCloud = allowCloud, onCloudChange = { allowCloud = it },
            apiKey = apiKey, onApiKeyChange = { newKey -> apiKey = newKey; context.getSharedPreferences("neon_prefs", Context.MODE_PRIVATE).edit().putString("gemini_key", newKey).apply() },
            onSendMessage = { text ->
                if (text.isNotBlank() && !isAiLoading) {
                    val requestProjectId = project.id; val requestProposalId = pendingProposal?.id; val thisRequestId = UUID.randomUUID().toString()
                    activeAiJob?.cancel(); currentRequestId = thisRequestId; saveErrorMessage = null
                    var updatedMessages = aiMessages + ChatMessage(text, true); aiMessages = updatedMessages; isAiLoading = true

                    activeAiJob = coroutineScope.launch {
                        try {
                            val isYes = text.trim().equals("да", ignoreCase = true); val isNo = text.trim().equals("нет", ignoreCase = true); val currentProposal = pendingProposal
                            var aiResponse: AiResponse; var systemMessage: String? = null
                            val journalCheck = if (requestProposalId != null) checkJournal(context, requestProjectId, requestProposalId) else JournalCheckResult.NotFound

                            if (recoveryFailed) { aiResponse = AiResponse("Действия ИИ заблокированы."); saveErrorMessage = "Устраните ошибку восстановления." }
                            else if ((isYes || isNo) && journalCheck is JournalCheckResult.Corrupted) { saveErrorMessage = "Журнал поврежден."; aiResponse = AiResponse("Заблокировано.") }
                            else if ((isYes || isNo) && journalCheck is JournalCheckResult.Error) { saveErrorMessage = "Ошибка журнала."; aiResponse = AiResponse("Заблокировано.") }
                            else if ((isYes || isNo) && journalCheck is JournalCheckResult.Found) {
                                val cleared = saveProposal(context, requestProjectId, null)
                                if (cleared) { pendingProposal = null; systemMessage = "[Система]: Уже выполнено."; aiResponse = AiResponse("Уже выполнено.") }
                                else { saveErrorMessage = "Ошибка очистки."; aiResponse = AiResponse("Ошибка.") }
                            } else if (isYes && currentProposal != null) {
                                if (project.id == requestProjectId && currentRequestId == thisRequestId) {
                                    
                                    if (currentProposal.params.containsKey("jsonArray")) {
                                        val array = JSONArray(currentProposal.params["jsonArray"])
                                        for (i in 0 until array.length()) {
                                            val cmd = array.getJSONObject(i)
                                            when (cmd.optString("action")) {
                                                "ADD_EFFECT" -> project = project.copy(effect = cmd.optString("effect", "Neon"))
                                                "ADD_TEXT" -> project = project.copy(texts = project.texts + cmd.optString("text", "NEON"))
                                                "CHANGE_SPEED" -> {
                                                    val spd = cmd.optDouble("speed", 2.0).toFloat()
                                                    playbackSpeed = spd
                                                    mediaPlayerInstance?.let { mp -> try { val params = mp.playbackParams; params.speed = spd; mp.playbackParams = params } catch (e: Exception) {} }
                                                }
                                                "TRIM_VIDEO" -> endTrim = cmd.optInt("seconds", 5) * 1000
                                            }
                                        }
                                    } else {
                                        when (currentProposal.actionType) {
                                            ActionType.TRIM_VIDEO -> { val secs = currentProposal.params["endSeconds"]?.toIntOrNull() ?: 5; endTrim = secs * 1000; videoViewInstance?.seekTo(startTrim) }
                                            ActionType.ADD_TEXT -> { project = project.copy(texts = project.texts + (currentProposal.params["textContent"] ?: "NEON")) }
                                            ActionType.ADD_EFFECT -> { project = project.copy(effect = currentProposal.params["effectName"] ?: "Neon") }
                                            ActionType.CHANGE_SPEED -> {
                                                val spd = currentProposal.params["speedValue"]?.toFloatOrNull() ?: 2.0f
                                                playbackSpeed = spd
                                                mediaPlayerInstance?.let { mp -> try { val params = mp.playbackParams; params.speed = spd; mp.playbackParams = params } catch (e: Exception) {} }
                                            }
                                            else -> {} 
                                        }
                                    }

                                    val saveRes = saveCompletedProposal(context, requestProjectId, CompletedProposal(currentProposal.copy(status = ActionStatus.CONFIRMED).id, currentProposal.actionType, ActionStatus.CONFIRMED))
                                    if (saveRes is SaveResult.Success || saveRes is SaveResult.AlreadyCompleted) {
                                        val cleared = saveProposal(context, requestProjectId, null)
                                        if (cleared) { pendingProposal = null; systemMessage = "[Система]: Подтверждено и применено."; aiResponse = AiResponse("ОК.") }
                                        else { if (setNeedsRecovery(context, requestProjectId, true)) { pendingProposal = null; saveErrorMessage = "Требуется восстановление."; systemMessage = "[Система]: ОК (ошибка синхронизации)."; aiResponse = AiResponse("ОК.") } else { saveErrorMessage = "Критическая ошибка."; aiResponse = AiResponse("Сбой.") } }
                                    } else { saveErrorMessage = "Ошибка записи."; aiResponse = AiResponse("Сбой.") }
                                } else { aiResponse = AiResponse("Устарел.") }
                            } else if (isNo && currentProposal != null) {
                                if (project.id == requestProjectId && currentRequestId == thisRequestId) {
                                    val saveRes = saveCompletedProposal(context, requestProjectId, CompletedProposal(currentProposal.copy(status = ActionStatus.CANCELLED).id, currentProposal.actionType, ActionStatus.CANCELLED))
                                    if (saveRes is SaveResult.Success || saveRes is SaveResult.AlreadyCompleted) {
                                        val cleared = saveProposal(context, requestProjectId, null)
                                        if (cleared) { pendingProposal = null; systemMessage = "[Система]: Отменено."; aiResponse = AiResponse("ОК.") }
                                        else { if (setNeedsRecovery(context, requestProjectId, true)) { pendingProposal = null; saveErrorMessage = "Требуется восстановление."; systemMessage = "[Система]: ОК (ошибка)."; aiResponse = AiResponse("ОК.") } else { saveErrorMessage = "Критическая ошибка."; aiResponse = AiResponse("Сбой.") } }
                                    } else { saveErrorMessage = "Ошибка записи."; aiResponse = AiResponse("Сбой.") }
                                } else { aiResponse = AiResponse("Устарел.") }
                            } else if (currentProposal != null && !isYes && !isNo) { aiResponse = AiResponse("Сначала подтвердите ('да') или отмените ('нет').") }
                            else { aiResponse = aiManager.getResponse(text, updatedMessages, allowCloud, apiKey) }

                            if (project.id == requestProjectId && currentRequestId == thisRequestId) {
                                if (systemMessage != null) updatedMessages = updatedMessages + ChatMessage(systemMessage, false)
                                if (aiResponse.errorMessage != null) updatedMessages = updatedMessages + ChatMessage("Ошибка: ${aiResponse.errorMessage}", false)
                                else {
                                    updatedMessages = updatedMessages + ChatMessage(aiResponse.text, false)
                                    if (aiResponse.proposal != null) {
                                        if (recoveryFailed) saveErrorMessage = "Новые предложения заблокированы."
                                        else { if (saveProposal(context, requestProjectId, aiResponse.proposal)) pendingProposal = aiResponse.proposal else saveErrorMessage = "Не удалось сохранить." }
                                    }
                                }
                                aiMessages = updatedMessages
                                if (!saveChatHistory(context, requestProjectId, updatedMessages)) saveErrorMessage = "Не удалось сохранить историю."
                            }
                        } catch (e: CancellationException) { throw e
                        } catch (e: Exception) {
                            if (project.id == requestProjectId && currentRequestId == thisRequestId) { updatedMessages = updatedMessages + ChatMessage("[Ошибка]: ${e.message ?: "Неизвестно"}", false); aiMessages = updatedMessages }
                        } finally {
                            if (project.id == requestProjectId && currentRequestId == thisRequestId) { isAiLoading = false; currentRequestId = null; activeAiJob = null }
                        }
                    }
                }
            }
        )
    }
}

@Composable
fun AiAssistantDialog(
    onDismiss: () -> Unit, aiMessages: List<ChatMessage>, isAiLoading: Boolean,
    allowCloud: Boolean, onCloudChange: (Boolean) -> Unit,
    apiKey: String, onApiKeyChange: (String) -> Unit,
    onSendMessage: (String) -> Unit
) {
    var userInput by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(aiMessages.size, isAiLoading) {
        if (aiMessages.isNotEmpty()) listState.animateScrollToItem(aiMessages.size - 1)
    }

    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("ИИ-Ассистент", color = Color(0xFFFF00FF), fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.height(320.dp).fillMaxWidth()) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Облачный ИИ (Groq)", color = Color.Gray, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    Switch(checked = allowCloud, onCheckedChange = onCloudChange, colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF00FFFF)))
                }
                if (allowCloud) {
                    OutlinedTextField(
                        value = apiKey, onValueChange = onApiKeyChange,
                        label = { Text("Groq API Key", fontSize = 10.sp) },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(fontSize = 12.sp)
                    )
                }
                LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                    items(aiMessages) { msg ->
                        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = if (msg.isUser) Alignment.CenterEnd else Alignment.CenterStart) {
                            Card(colors = CardDefaults.cardColors(containerColor = if (msg.isUser) Color(0xFF1A1A1A) else Color(0xFF330033))) {
                                Text(text = msg.text, color = if (msg.isUser) Color.White else Color(0xFFFF00FF), modifier = Modifier.padding(8.dp), fontSize = 13.sp)
                            }
                        }
                    }
                    if (isAiLoading) { item { Text("ИИ думает...", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(8.dp)) } }
                }
                Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = userInput, onValueChange = { userInput = it }, placeholder = { Text("Задай вопрос...", color = Color.Gray, fontSize = 12.sp) }, modifier = Modifier.weight(1f), singleLine = true, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFFFF00FF)))
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = { if (userInput.isNotBlank()) { onSendMessage(userInput); userInput = "" } }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF00FF))) { Text("Отпр.", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть", color = Color.Gray) } }
    )
}