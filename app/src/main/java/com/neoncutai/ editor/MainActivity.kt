package com.neoncutai.editor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Модель нашего проекта
data class Project(val id: String, val name: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // Наша неоновая тема
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Color(0xFF090909),
                    surface = Color(0xFF1A1A1A),
                    primary = Color(0xFF00FFFF)
                )
            ) {
                ProjectScreen()
            }
        }
    }
}

@Composable
fun ProjectScreen() {
    // Состояния для хранения списка проектов и окна создания
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
            // Кнопка создания проекта (+)
            FloatingActionButton(
                onClick = { showDialog = true },
                containerColor = Color(0xFFFF00FF)
            ) {
                Text("+", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
        }
    ) { paddingValues ->
        Column(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
            if (projects.isEmpty()) {
                // Если проектов нет, показываем текст
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Нет проектов\nНажмите кнопку +", color = Color.Gray, fontSize = 18.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            } else {
                // Если есть, показываем список
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(projects) { project ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A))
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(project.name, color = Color.White, fontSize = 18.sp, modifier = Modifier.weight(1f))
                                TextButton(onClick = {
                                    projects = projects.filter { it.id != project.id }
                                }) {
                                    Text("Удалить", color = Color(0xFFFF00FF))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Диалоговое окно для создания проекта
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
                TextButton(onClick = { showDialog = false }) {
                    Text("Отмена", color = Color.Gray)
                }
            }
        )
    }
}