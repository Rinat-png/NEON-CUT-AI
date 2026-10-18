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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

// --- МОДЕЛИ ДАННЫХ ---
data class Project(
    val id: String, val name: String, val videoUri: String? = null,
    val audioUri: String? = null, val texts: List<String> = emptyList(),
    val startTrim: Int = 0, val endTrim: Int = 0
)

data class ChatMessage(val text: String, val isUser: Boolean)

enum class ActionType { NONE, ADD_SUBTITLES, REMOVE_SILENCE }
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

// --- РЕЗУЛЬТАТЫ СОСТОЯНИЙ ---
sealed class JournalCheckResult {
    object Found : JournalCheckResult(); object NotFound : JournalCheckResult(); object Corrupted : JournalCheckResult(); data class Error(val message: String) : JournalCheckResult()
}
sealed class SaveResult {
    object Success : SaveResult(); object AlreadyCompleted : SaveResult(); object Conflict : SaveResult(); data class Error(val message: String) : SaveResult()
}
sealed class RecoveryResult {
    data class Recovered(val proposal: AiProposal?) : RecoveryResult(); data class Failed(val reason: String) : RecoveryResult()
}
sealed class ProposalLoadResult {
    object Absent : ProposalLoadResult(); data class Success(val proposal: AiProposal) : ProposalLoadResult(); object Corrupted : ProposalLoadResult()
}
interface AiEngine { suspend fun generateResponse(prompt: String, history: List<ChatMessage>): AiResponse }

class LocalAiEngine : AiEngine {
    override suspend fun generateResponse(prompt: String, history: List<ChatMessage>): AiResponse {
                    return when {
    prompt.contains("субтитр", ignoreCase = true) -> {
        val proposal = AiProposal(actionType = ActionType.ADD_SUBTITLES, explanation = "Демо: добавить субтитры.")
        AiResponse("Локальный ИИ: могу добавить субтитры. Подтвердить? (Демо)", proposal)
    }
    prompt.contains("пауз", ignoreCase = true) -> {
        val proposal = AiProposal(actionType = ActionType.REMOVE_SILENCE, explanation = "Демо: убрать паузы.")
        AiResponse("Локальный ИИ: могу убрать паузы. Подтвердить? (Демо)", proposal)
    }
    else -> AiResponse("Локальный ИИ (демо). Попроси добавить субтитры или убрать паузы.")
}
    }
}

class CloudAiEngine : AiEngine {
    override suspend fun generateResponse(prompt: String, history: List<ChatMessage>): AiResponse {
        if (prompt.contains("ошибка", ignoreCase = true)) throw Exception("Сбой облачного сервера")
        return AiResponse("☁️ Облачный ИИ (демо).")
    }
}

class AiManager(private val localEngine: AiEngine, private val cloudEngine: AiEngine) {
    suspend fun getResponse(prompt: String, history: List<ChatMessage>, allowCloud: Boolean, allowWebSearch: Boolean): AiResponse {
        if (allowCloud) {
            try {
                val cloudRes = cloudEngine.generateResponse(prompt, history)
                val textWithSearch = if (allowWebSearch) "${cloudRes.text}\n🌐 [Веб-поиск включен, но не реализован]" else cloudRes.text
                return AiResponse(textWithSearch.trim(), cloudRes.proposal)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { return AiResponse(text = "Ошибка облачного ИИ.", errorMessage = e.message ?: "Неизвестно") }
        } else {
            if (allowWebSearch) return AiResponse(text = "Веб-поиск без облачного ИИ не поддерживается.")
            return localEngine.generateResponse(prompt, history)
        }
    }
}
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
        val obj = JSONObject(str); val paramsMap = mutableMapOf<String, String>()
        obj.optJSONObject("params")?.keys()?.forEach { key -> paramsMap[key] = obj.optJSONObject("params")!!.getString(key) }
        ProposalLoadResult.Success(AiProposal(obj.getString("id"), ActionType.valueOf(obj.getString("type")), obj.getString("explanation"), ActionStatus.valueOf(obj.getString("status")), paramsMap))
    } catch (e: Exception) { ProposalLoadResult.Corrupted }
}

suspend fun checkJournal(context: Context, projectId: String, proposalId: String): JournalCheckResult = withContext(Dispatchers.IO) { dbMutex.withLock { internalCheckJournal(context, projectId, proposalId) } }
suspend fun loadProposalSafe(context: Context, projectId: String): ProposalLoadResult = withContext(Dispatchers.IO) { dbMutex.withLock { internalLoadProposal(context, projectId) } }

suspend fun saveCompletedProposal(context: Context, projectId: String, completed: CompletedProposal): SaveResult = withContext(Dispatchers.IO) {
    dbMutex.withLock {
        val prefs = context.getSharedPreferences("neon_cut_journal_$projectId", Context.MODE_PRIVATE)
        val rawString = prefs.getString("journal", "[]") ?: "[]"
        val oldArray = try { JSONArray(rawString) } catch (e: Exception) {
            val backupSuccess = prefs.edit().putString("journal_corrupt_backup_${System.currentTimeMillis()}", rawString).commit()
            if (!backupSuccess) return@withLock SaveResult.Error("Ошибка бэкапа")
            return@withLock SaveResult.Error("Журнал поврежден")
        }
        val cleanArray = JSONArray(); var existingStatus: String? = null
        for (i in 0 until oldArray.length()) {
            try {
                val obj = oldArray.getJSONObject(i)
                if (obj.getString("id") == completed.id) existingStatus = obj.optString("status")
                cleanArray.put(obj)
            } catch (e: Exception) { return@withLock SaveResult.Error("Журнал содержит ошибки") }
        }
        if (existingStatus != null) return@withLock if (existingStatus == completed.status.name) SaveResult.AlreadyCompleted else SaveResult.Conflict
        val newObj = JSONObject().put("id", completed.id).put("type", completed.actionType.name).put("status", completed.status.name).put("timestamp", completed.timestamp)
        cleanArray.put(newObj)
        if (prefs.edit().putString("journal", cleanArray.toString()).commit()) SaveResult.Success else SaveResult.Error("Ошибка commit")
    }
}

suspend fun saveProposal(context: Context, projectId: String, proposal: AiProposal?): Boolean = withContext(Dispatchers.IO) {
    dbMutex.withLock {
        val prefs = context.getSharedPreferences("neon_cut_action_$projectId", Context.MODE_PRIVATE)
        try {
            if (proposal == null) prefs.edit().remove("pending_proposal").commit()
            else {
                val obj = JSONObject().put("id", proposal.id).put("type", proposal.actionType.name).put("explanation", proposal.explanation).put("status", proposal.status.name)
                prefs.edit().putString("pending_proposal", obj.toString()).commit()
            }
        } catch (e: Exception) { false }
    }
}

suspend fun setNeedsRecovery(context: Context, projectId: String, value: Boolean): Boolean = withContext(Dispatchers.IO) {
    dbMutex.withLock { context.getSharedPreferences("neon_cut_action_$projectId", Context.MODE_PRIVATE).edit().putBoolean("needs_recovery", value).commit() }
}

suspend fun recoverPendingProposal(context: Context, projectId: String): RecoveryResult = withContext(Dispatchers.IO) {
    dbMutex.withLock {
        val prefsAction = context.getSharedPreferences("neon_cut_action_$projectId", Context.MODE_PRIVATE)
        val needsRecovery = prefsAction.getBoolean("needs_recovery", false)
        val pendingResult = internalLoadProposal(context, projectId)
        if (pendingResult is ProposalLoadResult.Corrupted) return@withLock RecoveryResult.Failed("Proposal поврежден")
        
        if (needsRecovery) {
            if (pendingResult is ProposalLoadResult.Success) {
                val journalCheck = internalCheckJournal(context, projectId, pendingResult.proposal.id)
                if (journalCheck is JournalCheckResult.Found) {
                    if (!prefsAction.edit().remove("pending_proposal").remove("needs_recovery").commit()) return@withLock RecoveryResult.Failed("Ошибка очистки")
                    return@withLock RecoveryResult.Recovered(null)
                } else return@withLock RecoveryResult.Failed("Неоднозначное состояние журнала")
            } else if (pendingResult is ProposalLoadResult.Absent) {
                if (!prefsAction.edit().remove("needs_recovery").commit()) return@withLock RecoveryResult.Failed("Ошибка очистки флага")
                return@withLock RecoveryResult.Recovered(null)
            }
        }
        when (pendingResult) {
            is ProposalLoadResult.Success -> {
                val journalCheck = internalCheckJournal(context, projectId, pendingResult.proposal.id)
                if (journalCheck is JournalCheckResult.Found) {
                    if (!prefsAction.edit().remove("pending_proposal").commit()) return@withLock RecoveryResult.Failed("Ошибка очистки")
                    RecoveryResult.Recovered(null)
                } else if (journalCheck is JournalCheckResult.Corrupted || journalCheck is JournalCheckResult.Error) RecoveryResult.Failed("Ошибка журнала")
                else RecoveryResult.Recovered(pendingResult.proposal)
            }
            else -> RecoveryResult.Recovered(null)
        }
    }
}

fun saveChatHistory(context: Context, projectId: String, messages: List<ChatMessage>): Boolean {
    return try {
        val jsonArray = JSONArray()
        for (msg in messages) jsonArray.put(JSONObject().put("text", msg.text).put("isUser", msg.isUser))
        context.getSharedPreferences("neon_cut_chat_$projectId", Context.MODE_PRIVATE).edit().putString("history", jsonArray.toString()).apply()
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
    val jsonArray = JSONArray()
    for (p in projects) {
        val obj = JSONObject().put("id", p.id).put("name", p.name).put("videoUri", p.videoUri ?: "").put("audioUri", p.audioUri ?: "").put("startTrim", p.startTrim).put("endTrim", p.endTrim)
        val textsArray = JSONArray(); for (t in p.texts) textsArray.put(t)
        obj.put("texts", textsArray); jsonArray.put(obj)
    }
    context.getSharedPreferences("neon_cut_prefs", Context.MODE_PRIVATE).edit().putString("projects_data", jsonArray.toString()).apply()
}

fun loadProjects(context: Context): List<Project> {
    val list = mutableListOf<Project>()
    try {
        val arr = JSONArray(context.getSharedPreferences("neon_cut_prefs", Context.MODE_PRIVATE).getString("projects_data", "[]") ?: "[]")
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i); val textsArray = obj.optJSONArray("texts") ?: JSONArray()
            val textsList = mutableListOf<String>(); for (j in 0 until textsArray.length()) textsList.add(textsArray.getString(j))
            list.add(Project(obj.getString("id"), obj.getString("name"), obj.optString("videoUri").takeIf { it.isNotEmpty() }, obj.optString("audioUri").takeIf { it.isNotEmpty() }, textsList, obj.optInt("startTrim", 0), obj.optInt("endTrim", 0)))
        }
    } catch (e: Exception) { }
    return list
}

fun formatTime(ms: Int): String = String.format("%02d:%02d", (ms / (1000 * 60)) % 60, (ms / 1000) % 60)
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
    var newProjectName by remember { mutableStateOf("") }

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
            text = { OutlinedTextField(value = newProjectName, onValueChange = { newProjectName = it }, label = { Text("Название") }, singleLine = true) },
            confirmButton = { TextButton(onClick = { if (newProjectName.isNotBlank()) { onAddProject(Project(UUID.randomUUID().toString(), newProjectName)); newProjectName = ""; showDialog = false } }) { Text("Создать", color = Color(0xFF00FFFF)) } },
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
    var videoPosition by remember { mutableStateOf(0) }
    var videoViewInstance by remember { mutableStateOf<VideoView?>(null) }
    var startTrim by remember { mutableStateOf(project.startTrim) }
    var endTrim by remember { mutableStateOf(project.endTrim) }
    var playbackSpeed by remember { mutableStateOf(1.0f) }
    
    var showTextDialog by remember { mutableStateOf(false) }
    var newText by remember { mutableStateOf("") }
    var isExporting by remember { mutableStateOf(false) }
    var exportProgress by remember { mutableStateOf(0f) }
    var exportFact by remember { mutableStateOf("Подготовка...") }
    
    var showAiDialog by remember { mutableStateOf(false) }
    var allowCloud by remember { mutableStateOf(false) }
    var allowWebSearch by remember { mutableStateOf(false) }
    
    var pendingProposal by remember(project.id) { mutableStateOf<AiProposal?>(null) }
    var aiMessages by remember(project.id) { mutableStateOf(loadChatHistory(context, project.id)) }
    var isAiLoading by remember(project.id) { mutableStateOf(false) }
    var activeAiJob by remember(project.id) { mutableStateOf<Job?>(null) }
    var currentRequestId by remember(project.id) { mutableStateOf<String?>(null) }
    var saveErrorMessage by remember(project.id) { mutableStateOf<String?>(null) }
    var recoveryFailed by remember(project.id) { mutableStateOf(false) }
    
    val coroutineScope = rememberCoroutineScope()
    val aiManager = remember { AiManager(LocalAiEngine(), CloudAiEngine()) }
    val facts = listOf("Это приложение пишется прямо на телефоне!", "NEON CUT AI использует нативные технологии Android.", "Первый видеоредактор появился еще в 1971 году!", "Твой телефон TECNO отлично справляется с задачей!")

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

    LaunchedEffect(isPlaying, videoViewInstance, endTrim) {
        while (isPlaying && videoViewInstance != null) {
            videoPosition = videoViewInstance!!.currentPosition
            if (videoPosition >= endTrim && endTrim > 0) { videoViewInstance?.pause(); isPlaying = false; videoViewInstance?.seekTo(endTrim) }
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

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF090909))) {
        Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { onBackClick(project.copy(startTrim = startTrim, endTrim = endTrim)) }) { Text("< Назад", color = Color(0xFF00FFFF), fontSize = 14.sp) }
            Row {
                Button(onClick = { videoPicker.launch("video/*") }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00FFFF)), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp), modifier = Modifier.height(32.dp)) { Text("Видео", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                Spacer(modifier = Modifier.width(4.dp))
                Button(onClick = { audioPicker.launch("audio/*") }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF00FF)), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp), modifier = Modifier.height(32.dp)) { Text("Музыка", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                Spacer(modifier = Modifier.width(4.dp))
                Button(onClick = { showTextDialog = true }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A1A1A)), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp), modifier = Modifier.height(32.dp).border(1.dp, Color(0xFF00FFFF), RoundedCornerShape(20.dp))) { Text("Текст", color = Color(0xFF00FFFF), fontSize = 11.sp) }
                Spacer(modifier = Modifier.width(4.dp))
                Button(onClick = { showAiDialog = true }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A1A1A)), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp), modifier = Modifier.height(32.dp).border(1.dp, Color(0xFFFF00FF), RoundedCornerShape(20.dp))) { Text("ИИ", color = Color(0xFFFF00FF), fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                Spacer(modifier = Modifier.width(4.dp))
                Button(onClick = { isExporting = true }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00FF00)), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp), modifier = Modifier.height(32.dp)) { Text("Сохр.", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
            }
        }

        Box(modifier = Modifier.fillMaxWidth().weight(1f).padding(16.dp).background(Color.Black), contentAlignment = Alignment.Center) {
            if (project.videoUri == null) {
                Text("Окно предпросмотра\n(Импортируйте видео)", color = Color.DarkGray, textAlign = TextAlign.Center)
            } else {
                AndroidView(factory = { ctx ->
                    VideoView(ctx).apply {
                        setVideoURI(Uri.parse(project.videoUri))
                        setOnPreparedListener { mp ->
                            videoDuration = mp.duration; if (endTrim == 0) endTrim = mp.duration
                            videoViewInstance = this; mediaPlayerInstance = mp; seekTo(startTrim)
                            try { val params = mp.playbackParams; params.speed = playbackSpeed; mp.playbackParams = params } catch (e: Exception) {}
                            start(); isPlaying = true
                        }
                        setOnCompletionListener { isPlaying = false }
                        start()
                    }
                }, modifier = Modifier.fillMaxSize())
                project.texts.forEach { text -> Text(text = text, color = Color(0xFF00FFFF), fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 50.dp)) }
            }
        }
        if (project.videoUri != null) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    Button(onClick = { videoViewInstance?.let { if (it.isPlaying) { it.pause(); isPlaying = false } else { it.start(); isPlaying = true } } }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A1A1A)), modifier = Modifier.border(1.dp, Color(0xFF00FFFF), RoundedCornerShape(20.dp))) { Text(if (isPlaying) "⏸ Пауза" else "▶ Играть", color = Color(0xFF00FFFF), fontSize = 12.sp) }
                    Spacer(modifier = Modifier.width(8.dp))
                    listOf(0.5f, 1.0f, 1.5f, 2.0f).forEach { speed ->
                        TextButton(onClick = { playbackSpeed = speed; mediaPlayerInstance?.let { mp -> try { val params = mp.playbackParams; params.speed = speed; mp.playbackParams = params } catch (e: Exception) {} } }, colors = ButtonDefaults.textButtonColors(contentColor = if (playbackSpeed == speed) Color(0xFF00FFFF) else Color.Gray)) { Text("${speed}x", fontSize = 12.sp) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(formatTime(videoPosition), color = Color.Gray, fontSize = 12.sp)
                    Slider(value = videoPosition.toFloat(), onValueChange = { videoPosition = it.toInt(); videoViewInstance?.seekTo(it.toInt()) }, valueRange = 0f..(if (videoDuration > 0) videoDuration.toFloat() else 1f), modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                    Text(formatTime(videoDuration), color = Color.Gray, fontSize = 12.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Нач: ${formatTime(startTrim)}", color = Color(0xFF00FFFF), fontSize = 11.sp)
                    Slider(value = startTrim.toFloat(), onValueChange = { if (it < endTrim) { startTrim = it.toInt(); videoViewInstance?.seekTo(startTrim) } }, valueRange = 0f..(if (videoDuration > 0) videoDuration.toFloat() else 1f), modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Кон: ${formatTime(endTrim)}", color = Color(0xFFFF00FF), fontSize = 11.sp)
                    Slider(value = endTrim.toFloat(), onValueChange = { if (it > startTrim) { endTrim = it.toInt() } }, valueRange = 0f..(if (videoDuration > 0) videoDuration.toFloat() else 1f), modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                }
            }
        }

        Column(modifier = Modifier.fillMaxWidth().height(100.dp).background(Color(0xFF121212)).padding(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("V", color = Color.Gray, fontSize = 10.sp, modifier = Modifier.width(20.dp))
                Box(modifier = Modifier.fillMaxHeight().width(180.dp).background(Color(0xFF00FFFF).copy(alpha = 0.2f), RoundedCornerShape(4.dp)).border(1.dp, Color(0xFF00FFFF), RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
                    if (project.videoUri != null) Text("Видео: ${formatTime(endTrim - startTrim)}", color = Color.White, fontSize = 10.sp) else Text("Пусто", color = Color.DarkGray, fontSize = 10.sp)
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("A", color = Color.Gray, fontSize = 10.sp, modifier = Modifier.width(20.dp))
                Box(modifier = Modifier.fillMaxHeight().width(220.dp).background(Color(0xFFFF00FF).copy(alpha = 0.2f), RoundedCornerShape(4.dp)).border(1.dp, Color(0xFFFF00FF), RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
                    if (project.audioUri != null) Text("Аудио трек", color = Color.White, fontSize = 10.sp) else Text("Нет музыки", color = Color.DarkGray, fontSize = 10.sp)
                }
            }
        }
    }

    if (showTextDialog) {
        AlertDialog(onDismissRequest = { showTextDialog = false }, title = { Text("Добавить текст", color = Color.White) }, text = { OutlinedTextField(value = newText, onValueChange = { newText = it }, label = { Text("Текст") }, singleLine = true) }, confirmButton = { TextButton(onClick = { if (newText.isNotBlank()) { project = project.copy(texts = project.texts + newText); newText = ""; showTextDialog = false } }) { Text("Добавить", color = Color(0xFF00FFFF)) } }, dismissButton = { TextButton(onClick = { showTextDialog = false }) { Text("Отмена", color = Color.Gray) } })
    }

    if (isExporting) {
        AlertDialog(onDismissRequest = { }, title = { Text("Экспорт видео", color = Color.White) }, text = { Column { Text(text = exportFact, color = Color(0xFF00FFFF), fontSize = 14.sp, modifier = Modifier.padding(bottom = 16.dp)); LinearProgressIndicator(progress = exportProgress, modifier = Modifier.fillMaxWidth(), color = Color(0xFF00FF00), trackColor = Color.DarkGray) } }, confirmButton = { if (exportProgress >= 1f) { TextButton(onClick = { isExporting = false }) { Text("Готово", color = Color(0xFF00FF00)) } } })
    }

    if (showAiDialog) {
        AiAssistantDialog(
            onDismiss = { showAiDialog = false }, aiMessages = aiMessages, isAiLoading = isAiLoading,
            allowCloud = allowCloud, onCloudChange = { allowCloud = it },
            allowWebSearch = allowWebSearch, onWebSearchChange = { allowWebSearch = it },
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

                            if (recoveryFailed) { aiResponse = AiResponse("Действия ИИ заблокированы."); saveErrorMessage = "Устраните ошибку восстановления."
                            } else if ((isYes || isNo) && journalCheck is JournalCheckResult.Corrupted) { saveErrorMessage = "Журнал поврежден."; aiResponse = AiResponse("Заблокировано.")
                            } else if ((isYes || isNo) && journalCheck is JournalCheckResult.Error) { saveErrorMessage = "Ошибка журнала."; aiResponse = AiResponse("Заблокировано.")
                            } else if ((isYes || isNo) && journalCheck is JournalCheckResult.Found) {
                                val cleared = saveProposal(context, requestProjectId, null)
                                if (cleared) { pendingProposal = null; systemMessage = "[Система]: Уже выполнено."; aiResponse = AiResponse("Уже выполнено.")
                                } else { saveErrorMessage = "Ошибка очистки."; aiResponse = AiResponse("Ошибка.") }
                            } else if (isYes && currentProposal != null) {
                                if (project.id == requestProjectId && currentRequestId == thisRequestId) {
                                    val saveRes = saveCompletedProposal(context, requestProjectId, CompletedProposal(currentProposal.copy(status = ActionStatus.CONFIRMED).id, currentProposal.actionType, ActionStatus.CONFIRMED))
                                    if (saveRes is SaveResult.Success || saveRes is SaveResult.AlreadyCompleted) {
                                        val cleared = saveProposal(context, requestProjectId, null)
                                        if (cleared) { pendingProposal = null; systemMessage = "[Система]: Подтверждено."; aiResponse = AiResponse("ОК.")
                                        } else {
                                            if (setNeedsRecovery(context, requestProjectId, true)) { pendingProposal = null; saveErrorMessage = "Требуется восстановление."; systemMessage = "[Система]: ОК (ошибка синхронизации)."; aiResponse = AiResponse("ОК.")
                                            } else { saveErrorMessage = "Критическая ошибка."; aiResponse = AiResponse("Сбой.") }
                                        }
                                    } else { saveErrorMessage = "Ошибка записи."; aiResponse = AiResponse("Сбой.") }
                                } else { aiResponse = AiResponse("Устарел.") }
                            } else if (isNo && currentProposal != null) {
                                if (project.id == requestProjectId && currentRequestId == thisRequestId) {
                                    val saveRes = saveCompletedProposal(context, requestProjectId, CompletedProposal(currentProposal.copy(status = ActionStatus.CANCELLED).id, currentProposal.actionType, ActionStatus.CANCELLED))
                                    if (saveRes is SaveResult.Success || saveRes is SaveResult.AlreadyCompleted) {
                                        val cleared = saveProposal(context, requestProjectId, null)
                                        if (cleared) { pendingProposal = null; systemMessage = "[Система]: Отменено."; aiResponse = AiResponse("ОК.")
                                        } else {
                                            if (setNeedsRecovery(context, requestProjectId, true)) { pendingProposal = null; saveErrorMessage = "Требуется восстановление."; systemMessage = "[Система]: ОК (ошибка)."; aiResponse = AiResponse("ОК.")
                                            } else { saveErrorMessage = "Критическая ошибка."; aiResponse = AiResponse("Сбой.") }
                                        }
                                    } else { saveErrorMessage = "Ошибка записи."; aiResponse = AiResponse("Сбой.") }
                                } else { aiResponse = AiResponse("Устарел.") }
                            } else if (currentProposal != null && !isYes && !isNo) { aiResponse = AiResponse("Сначала подтвердите ('да') или отмените ('нет').")
                            } else { aiResponse = aiManager.getResponse(text, updatedMessages, allowCloud, allowWebSearch) }

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
                            if (project.id == requestProjectId && currentRequestId == thisRequestId) {
                                updatedMessages = updatedMessages + ChatMessage("[Ошибка]: ${e.message ?: "Неизвестно"}", false); aiMessages = updatedMessages
                            }
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
    allowCloud: Boolean, onCloudChange: (Boolean) -> Unit, allowWebSearch: Boolean, onWebSearchChange: (Boolean) -> Unit,
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
            Column(modifier = Modifier.height(300.dp).fillMaxWidth()) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Облачный ИИ", color = Color.Gray, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    Switch(checked = allowCloud, onCheckedChange = onCloudChange, colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF00FFFF)))
                }
                Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Веб-поиск", color = Color.Gray, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    Switch(checked = allowWebSearch, onCheckedChange = onWebSearchChange, colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFFFF00FF)))
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