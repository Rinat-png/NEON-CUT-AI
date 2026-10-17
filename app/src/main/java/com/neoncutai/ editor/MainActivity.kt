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