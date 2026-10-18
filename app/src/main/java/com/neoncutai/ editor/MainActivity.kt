package com.neoncutai.editor

import android.net.Uri
import android.os.Bundle
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class Project(val id: String, val name: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Color(0xFF090909),
                    surface = Color(0xFF1A1A1A),
                    primary = Color(0xFF00FFFF)
                )
            ) {
                var selectedProject by remember { mutableStateOf<Project?>(null) }
                
                if (selectedProject == null) {
                    ProjectScreen(onProjectClick = { selectedProject = it })
                } else {
                    EditorScreen(
                        project = selectedProject!!, 
                        onBackClick = { selectedProject = null }
                    )
                }
            }
        }
    }
}

// ЭКРАН 1: СПИСОК ПРОЕКТОВ (без изменений)
@Composable
fun ProjectScreen(onProjectClick: (Project) -> Unit) {
    var projects by remember { mutableStateOf(listOf<Project>()) }
    var showDialog by remember { mutableStateOf(false) }
    var newProjectName by remember { mutableStateOf("") }

    Scaffold(
        containerColor = Color(0xFF090909),
        topBar = {
            Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                Text("NEON CUT AI", color = Color(0xFF00FFFF), fontSize = 28.sp, fontWeight = FontWeight.Bold)
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showDialog = true }, containerColor = Color(0xFFFF00FF)) {
                Text("+", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
        }
    ) { paddingValues ->
        Column(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
            if (projects.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Нет проектов\nНажмите +", color = Color.Gray, fontSize = 18.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(projects) { project ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clickable { onProjectClick(project) },
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A))
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(project.name, color = Color.White, fontSize = 18.sp, modifier = Modifier.weight(1f))
                                TextButton(onClick = { projects = projects.filter { it.id != project.id } }) {
                                    Text("Удалить", color = Color(0xFFFF00FF))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Новый проект", color = Color.White) },
            text = {
                OutlinedTextField(
                    value = newProjectName,
                    onValueChange = { newProjectName = it },
                    label = { Text("Название") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newProjectName.isNotBlank()) {
                        projects = projects + Project(java.util.UUID.randomUUID().toString(), newProjectName)
                        newProjectName = ""
                        showDialog = false
                    }
                }) {
                    Text("Создать", color = Color(0xFF00FFFF))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) { Text("Отмена", color = Color.Gray) }
            }
        )
    }
}

// ЭКРАН 2: РЕДАКТОР С ВИДЕО
@Composable
fun EditorScreen(project: Project, onBackClick: () -> Unit) {
    var selectedVideoUri by remember { mutableStateOf<Uri?>(null) }
    val context = LocalContext.current

    // Лончер для выбора видео из памяти телефона
    val videoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        selectedVideoUri = uri
    }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF090909))) {
        // Верхняя панель
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBackClick) {
                Text("< Назад", color = Color(0xFF00FFFF), fontSize = 16.sp)
            }
            Spacer(modifier = Modifier.width(16.dp))
            Text(project.name, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            
            // Кнопка импорта
            Button(
                onClick = { videoPicker.launch("video/*") },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00FFFF))
            ) {
                Text("Импорт", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }

        // Окно предпросмотра
        Box(
            modifier = Modifier.fillMaxWidth().weight(1f).padding(16.dp).background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            if (selectedVideoUri == null) {
                Text("Окно предпросмотра\n(Нажмите 'Импорт')", color = Color.DarkGray, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            } else {
                // Встроенный видеоплеер Android
                AndroidView(
                    factory = { ctx ->
                        VideoView(ctx).apply {
                            setVideoURI(selectedVideoUri)
                            val mediaController = MediaController(ctx)
                            mediaController.setAnchorView(this)
                            setMediaController(mediaController)
                            start()
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // Тайм-линия
        Box(
            modifier = Modifier.fillMaxWidth().height(120.dp).background(Color(0xFF1A1A1A)).padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("Здесь будет тайм-линия", color = Color.Gray)
        }
    }
}
package com.neoncutai.editor

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.widget.MediaController
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
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

data class Project(
    val id: String,
    val name: String,
    val videoUri: String? = null,
    val audioUri: String? = null,
    val texts: List<String> = emptyList(),
    val startTrim: Int = 0,
    val endTrim: Int = 0
)

fun saveProjects(context: Context, projects: List<Project>) {
    val prefs = context.getSharedPreferences("neon_cut_prefs", Context.MODE_PRIVATE)
    val jsonArray = JSONArray()
    for (p in projects) {
        val jsonObj = JSONObject()
        jsonObj.put("id", p.id)
        jsonObj.put("name", p.name)
        jsonObj.put("videoUri", p.videoUri ?: "")
        jsonObj.put("audioUri", p.audioUri ?: "")
        jsonObj.put("startTrim", p.startTrim)
        jsonObj.put("endTrim", p.endTrim)
        val textsArray = JSONArray()
        for (t in p.texts) textsArray.put(t)
        jsonObj.put("texts", textsArray)
        jsonArray.put(jsonObj)
    }
    prefs.edit().putString("projects_data", jsonArray.toString()).apply()
}

fun loadProjects(context: Context): List<Project> {
    val prefs = context.getSharedPreferences("neon_cut_prefs", Context.MODE_PRIVATE)
    val jsonStr = prefs.getString("projects_data", "[]") ?: "[]"
    val list = mutableListOf<Project>()
    try {
        val jsonArray = JSONArray(jsonStr)
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            val textsArray = obj.optJSONArray("texts") ?: JSONArray()
            val textsList = mutableListOf<String>()
            for (j in 0 until textsArray.length()) {
                textsList.add(textsArray.getString(j))
            }
            list.add(Project(
                id = obj.getString("id"),
                name = obj.getString("name"),
                videoUri = obj.optString("videoUri").takeIf { it.isNotEmpty() },
                audioUri = obj.optString("audioUri").takeIf { it.isNotEmpty() },
                startTrim = obj.optInt("startTrim", 0),
                endTrim = obj.optInt("endTrim", 0),
                texts = textsList
            ))
        }
    } catch (e: Exception) { e.printStackTrace() }
    return list
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Color(0xFF090909),
                    surface = Color(0xFF1A1A1A),
                    primary = Color(0xFF00FFFF)
                )
            ) {
                val context = LocalContext.current
                var projects by remember { mutableStateOf(loadProjects(context)) }
                var selectedProject by remember { mutableStateOf<Project?>(null) }
                
                LaunchedEffect(projects) {
                    saveProjects(context, projects)
                }

                if (selectedProject == null) {
                    ProjectScreen(
                        projects = projects,
                        onProjectClick = { selectedProject = it },
                        onAddProject = { newProj -> projects = projects + newProj },
                        onDeleteProject = { projToDelete -> projects = projects.filter { it.id != projToDelete.id } }
                    )
                } else {
                    EditorScreen(
                        project = selectedProject!!, 
                        onBackClick = { updatedProject ->
                            projects = projects.map { if (it.id == updatedProject.id) updatedProject else it }
                            selectedProject = null
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun ProjectScreen(
    projects: List<Project>,
    onProjectClick: (Project) -> Unit,
    onAddProject: (Project) -> Unit,
    onDeleteProject: (Project) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }
    var newProjectName by remember { mutableStateOf("") }

    Scaffold(
        containerColor = Color(0xFF090909),
        topBar = {
            Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                Text("NEON CUT AI", color = Color(0xFF00FFFF), fontSize = 28.sp, fontWeight = FontWeight.Bold)
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showDialog = true }, containerColor = Color(0xFFFF00FF)) {
                Text("+", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
        }
    ) { paddingValues ->
        Column(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
            if (projects.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Нет проектов\nНажмите +", color = Color.Gray, fontSize = 18.sp, textAlign = TextAlign.Center)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(projects) { project ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clickable { onProjectClick(project) },
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A))
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(project.name, color = Color.White, fontSize = 18.sp, modifier = Modifier.weight(1f))
                                TextButton(onClick = { onDeleteProject(project) }) {
                                    Text("Удалить", color = Color(0xFFFF00FF))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Новый проект", color = Color.White) },
            text = {
                OutlinedTextField(
                    value = newProjectName,
                    onValueChange = { newProjectName = it },
                    label = { Text("Название") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newProjectName.isNotBlank()) {
                        onAddProject(Project(java.util.UUID.randomUUID().toString(), newProjectName))
                        newProjectName = ""
                        showDialog = false
                    }
                }) { Text("Создать", color = Color(0xFF00FFFF)) }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) { Text("Отмена", color = Color.Gray) }
            }
        )
   }
@Composable







fun EditorScreen(initialProject: Project, onBackClick: (Project) -> Unit) {
var project by remember { mutableStateOf(initialProject) }
val facts = listOf(
    "Это приложение пишется прямо на телефоне через GitHub!",
    "NEON CUT AI использует нативные технологии Android.",
    "Скоро здесь появится настоящий ИИ для монтажа.",
    "Первый видеоредактор появился еще в 1971 году!",
    "В Geometry Dash идеальный тайминг решает всё!",
    "Рендеринг видео — сложный процесс, требующий терпения.",
    "Твой телефон TECNO отлично справляется с задачей!"
)

LaunchedEffect(isExporting) {
    if (isExporting) {
        exportProgress = 0f
        var factIndex = 0
        exportFact = facts[factIndex]
        while (exportProgress < 1f) {
            delay(150)
            exportProgress += 0.02f
            if (exportProgress % 0.1f < 0.02f) {
                factIndex = (factIndex + 1) % facts.size
                exportFact = facts[factIndex]
            }
        }
        delay(300)
        isExporting = false
    }
}
 { mutableStateOf(initialProject) }
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(false) }
    var videoDuration by remember { mutableStateOf(0) }
    var videoPosition by remember { mutableStateOf(0) }
    var videoViewInstance by remember { mutableStateOf<VideoView?>(null) }
    var startTrim by remember { mutableStateOf(project.startTrim) }
    var endTrim by remember { mutableStateOf(project.endTrim) }
    var showTextDialog by remember { mutableStateOf(false) }
    var newText by remember { mutableStateOf("") }
    var isExporting by remember { mutableStateOf(false) }
    var exportProgress by remember { mutableStateOf(0f) }

    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? -> 
        if (uri != null) {
            project = project.copy(videoUri = uri.toString())
            videoViewInstance?.setVideoURI(uri)
            videoViewInstance?.start()
        }
    }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? -> 
        if (uri != null) project = project.copy(audioUri = uri.toString())
    }

    LaunchedEffect(isPlaying, videoViewInstance, endTrim) {
        while (isPlaying && videoViewInstance != null) {
            videoPosition = videoViewInstance!!.currentPosition
            if (videoPosition >= endTrim && endTrim > 0) {
                videoViewInstance?.pause()
                isPlaying = false
                videoViewInstance?.seekTo(endTrim)
            }
            delay(50)
        }
    }

    LaunchedEffect(isExporting) {
        if (isExporting) {
            exportProgress = 0f
            while (exportProgress < 1f) {
                delay(100)
                exportProgress += 0.05f
            }
            delay(300)
            isExporting = false
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF090909))) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = { onBackClick(project.copy(startTrim = startTrim, endTrim = endTrim)) }) { 
                Text("< Назад", color = Color(0xFF00FFFF), fontSize = 14.sp) 
            }
            Row {
                Button(onClick = { videoPicker.launch("video/*") }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00FFFF)), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp), modifier = Modifier.height(36.dp)) { Text("Видео", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                Spacer(modifier = Modifier.width(4.dp))
                Button(onClick = { audioPicker.launch("audio/*") }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF00FF)), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp), modifier = Modifier.height(36.dp)) { Text("Музыка", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                Spacer(modifier = Modifier.width(4.dp))
                Button(onClick = { showTextDialog = true }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A1A1A)), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp), modifier = Modifier.height(36.dp).border(1.dp, Color(0xFF00FFFF), RoundedCornerShape(20.dp))) { Text("Текст", color = Color(0xFF00FFFF), fontSize = 12.sp) }
                Spacer(modifier = Modifier.width(4.dp))
                Button(onClick = { isExporting = true }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00FF00)), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp), modifier = Modifier.height(36.dp)) { Text("Экспорт", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            }
        }

        Box(
            modifier = Modifier.fillMaxWidth().weight(1f).padding(16.dp).background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            if (project.videoUri == null) {
                Text("Окно предпросмотра\n(Импортируйте видео)", color = Color.DarkGray, textAlign = TextAlign.Center)
            } else {
                AndroidView(
                    factory = { ctx ->
                        VideoView(ctx).apply {
                            setVideoURI(Uri.parse(project.videoUri))
                            setOnPreparedListener { mp ->
                                videoDuration = mp.duration
                                if (endTrim == 0) endTrim = mp.duration
                                videoViewInstance = this
                                seekTo(startTrim)
                                start()
                                isPlaying = true
                            }
                            setOnCompletionListener { isPlaying = false }
                            start()
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
                
                project.texts.forEach { text ->
                    Text(text = text, color = Color(0xFF00FFFF), fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 50.dp))
                }
            }
        }

        if (project.videoUri != null) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    Button(
                        onClick = { 
                            videoViewInstance?.let { 
                                if (it.isPlaying) { it.pause(); isPlaying = false } 
                                else { it.start(); isPlaying = true } 
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A1A1A)),
                        modifier = Modifier.border(1.dp, Color(0xFF00FFFF), RoundedCornerShape(20.dp))
                    ) { Text(if (isPlaying) "⏸ Пауза" else "▶ Играть", color = Color(0xFF00FFFF)) }
                    
                    Spacer(modifier = Modifier.width(16.dp))
                    
                    if (project.texts.isNotEmpty()) {
                        TextButton(onClick = { project = project.copy(texts = emptyList()) }) {
                            Text("Очистить текст", color = Color(0xFFFF00FF), fontSize = 12.sp)
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(formatTime(videoPosition), color = Color.Gray, fontSize = 12.sp)
                    Slider(
                        value = videoPosition.toFloat(),
                        onValueChange = { videoPosition = it.toInt(); videoViewInstance?.seekTo(it.toInt()) },
                        valueRange = 0f..(if (videoDuration > 0) videoDuration.toFloat() else 1f),
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                    )
                    Text(formatTime(videoDuration), color = Color.Gray, fontSize = 12.sp)
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Нач: ${formatTime(startTrim)}", color = Color(0xFF00FFFF), fontSize = 12.sp)
                    Slider(
                        value = startTrim.toFloat(),
                        onValueChange = { if (it < endTrim) { startTrim = it.toInt(); videoViewInstance?.seekTo(startTrim) } },
                        valueRange = 0f..(if (videoDuration > 0) videoDuration.toFloat() else 1f),
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Кон: ${formatTime(endTrim)}", color = Color(0xFFFF00FF), fontSize = 12.sp)
                    Slider(
                        value = endTrim.toFloat(),
                        onValueChange = { if (it > startTrim) { endTrim = it.toInt() } },
                        valueRange = 0f..(if (videoDuration > 0) videoDuration.toFloat() else 1f),
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                    )
                }
            }
        }

        Column(modifier = Modifier.fillMaxWidth().height(140.dp).background(Color(0xFF121212)).padding(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth().height(55.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("V", color = Color.Gray, fontSize = 10.sp, modifier = Modifier.width(20.dp))
                Box(
                    modifier = Modifier.fillMaxHeight().width(180.dp)
                        .background(Color(0xFF00FFFF).copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                        .border(1.dp, Color(0xFF00FFFF), RoundedCornerShape(4.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (project.videoUri != null) Text("Видео: ${formatTime(endTrim - startTrim)}", color = Color.White, fontSize = 10.sp)
                    else Text("Пусто", color = Color.DarkGray, fontSize = 10.sp)
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth().height(55.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("A", color = Color.Gray, fontSize = 10.sp, modifier = Modifier.width(20.dp))
                Box(
                    modifier = Modifier.fillMaxHeight().width(220.dp)
                        .background(Color(0xFFFF00FF).copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                        .border(1.dp, Color(0xFFFF00FF), RoundedCornerShape(4.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (project.audioUri != null) Text("Аудио трек", color = Color.White, fontSize = 10.sp)
                    else Text("Нет музыки", color = Color.DarkGray, fontSize = 10.sp)
                }
            }
        }
    }

    if (showTextDialog) {
        AlertDialog(
            onDismissRequest = { showTextDialog = false },
            title = { Text("Добавить текст", color = Color.White) },
            text = { OutlinedTextField(value = newText, onValueChange = { newText = it }, label = { Text("Текст") }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    if (newText.isNotBlank()) {
                        project = project.copy(texts = project.texts + newText)
                        newText = ""
                        showTextDialog = false
                    }
                }) { Text("Добавить", color = Color(0xFF00FFFF)) }
            },
            dismissButton = { TextButton(onClick = { showTextDialog = false }) { Text("Отмена", color = Color.Gray) } }
        )
    }

    if (isExporting) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Экспорт видео", color = Color.White) },
            text = {
                Column {
  
            }
        }
        delay(300)
        isExporting = false
    }
}
                        progress = exportProgress,
                        modifier = Modifier.fillMaxWidth(),
                        color = Color(0xFF00FF00),
                        trackColor = Color.DarkGray
                    )
                }
            },
            confirmButton = {
                if (exportProgress >= 1f) {
                    TextButton(onClick = { isExporting = false }) { Text("Готово", color = Color(0xFF00FF00)) }
                }
            }
        )
    }
}

fun formatTime(ms: Int): String {
    val seconds = (ms / 1000) % 60
    val minutes = (ms / (1000 * 60)) % 60
    return String.format("%02d:%02d", minutes, seconds)
}
var exportFact by remember { mutableStateOf("Подготовка...") }
