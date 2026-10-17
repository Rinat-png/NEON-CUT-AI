package com.neoncutai.editor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray

private val NeonBlack = Color(0xFF090B14)
private val NeonCard = Color(0xFF171B2B)
private val NeonCyan = Color(0xFF00E5FF)
private val NeonPurple = Color(0xFF9C5CFF)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("neon_cut_ai", MODE_PRIVATE)

        setContent {
            var projects by remember {
                mutableStateOf(
                    runCatching {
                        val json = JSONArray(
                            prefs.getString("projects", "[]")
                        )
                        List(json.length()) { i -> json.getString(i) }
                    }.getOrDefault(emptyList())
                )
            }

            var newName by remember { mutableStateOf("") }
            var showCreate by remember { mutableStateOf(false) }
            var selectedProject by remember {
                mutableStateOf<String?>(null)
            }
            var lightMode by remember { mutableStateOf(false) }

            val bg = if (lightMode) Color(0xFFF2F4FA)
                      else NeonBlack
            val fg = if (lightMode) Color(0xFF17172A)
                      else Color.White

            fun saveProjects(list: List<String>) {
                projects = list
                prefs.edit().putString(
                    "projects",
                    JSONArray(list).toString()
                ).apply()
            }

            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = bg
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                            .navigationBarsPadding()
                            .padding(18.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    "NEON CUT",
                                    color = NeonCyan,
                                    fontSize = 27.sp,
                                    fontWeight = FontWeight.Black
                                )
                                Text(
                                    "AI VIDEO EDITOR",
                                    color = fg.copy(alpha = 0.65f),
                                    fontSize = 12.sp
                                )
                            }
                            TextButton(
                                onClick = { lightMode = !lightMode }
                            ) {
                                Text(
                                    if (lightMode) "☾" else "☀",
                                    fontSize = 24.sp,
                                    color = NeonPurple
                                )
                            }
                        }

                        Spacer(Modifier.height(24.dp))

                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = NeonCard
                            ),
                            shape = RoundedCornerShape(22.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(20.dp)
                            ) {
                                Text(
                                    "Твоя студия монтажа",
                                    color = Color.White,
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "Создавай проекты для Shorts, " +
                                    "игровых видео и неоновых эдитов.",
                                    color = Color.LightGray
                                )
                                Spacer(Modifier.height(18.dp))
                                Button(
                                    onClick = {
                                        newName = ""
                                        showCreate = true
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = NeonPurple
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("＋  Новый проект")
                                }
                            }
                        }

                        Spacer(Modifier.height(24.dp))

                        Text(
                            "МОИ ПРОЕКТЫ",
                            color = fg,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(Modifier.height(12.dp))

                        if (projects.isEmpty()) {
                            Text(
                                "Здесь появятся твои проекты.",
                                color = fg.copy(alpha = 0.6f)
                            )
                        } else {
                            LazyColumn(
                                verticalArrangement =
                                    Arrangement.spacedBy(10.dp)
                            ) {
                                items(projects) { project ->
                                    Card(
                                        colors =
                                            CardDefaults.cardColors(
                                                containerColor = NeonCard
                                            ),
                                        shape =
                                            RoundedCornerShape(14.dp),
                                        modifier =
                                            Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(14.dp),
                                            verticalAlignment =
                                                Alignment.CenterVertically
                                        ) {
                                            Column(
                                                modifier =
                                                    Modifier.weight(1f)
                                            ) {
                                                Text(
                                                    project,
                                                    color = Color.White,
                                                    fontWeight =
                                                        FontWeight.Bold
                                                )
                                                Text(
                                                    "Проект NEON CUT AI",
                                                    color = Color.LightGray,
                                                    fontSize = 12.sp
                                                )
                                            }
                                            TextButton(
                                                onClick = {
                                                    selectedProject =
                                                        project
                                                }
                                            ) {
                                                Text(
                                                    "Открыть",
                                                    color = NeonCyan
                                                )
                                            }
                                            TextButton(
                                                onClick = {
                                                    saveProjects(
                                                        projects.filter {
                                                            it != project
                                                        }
                                                    )
                                                }
                                            ) {
                                                Text(
                                                    "×",
                                                    color = Color.Red
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.weight(1f))

                        Text(
                            "NEON CUT AI • Ранняя версия",
                            color = fg.copy(alpha = 0.45f),
                            fontSize = 11.sp,
                            modifier = Modifier.align(
                                Alignment.CenterHorizontally
                            )
                        )
                    }
                }
            }

            if (showCreate) {
                AlertDialog(
                    onDismissRequest = { showCreate = false },
                    title = { Text("Новый проект") },
                    text = {
                        OutlinedTextField(
                            value = newName,
                            onValueChange = { newName = it },
                            label = { Text("Название проекта") },
                            singleLine = true
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                val name = newName.trim()
                                if (name.isNotEmpty()) {
                                    saveProjects(
                                        projects + name
                                    )
                                    showCreate = false
                                }
                            }
                        ) {
                            Text("Создать")
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = { showCreate = false }
                        ) {
                            Text("Отмена")
                        }
                    }
                )
            }

            selectedProject?.let { project ->
                AlertDialog(
                    onDismissRequest = {
                        selectedProject = null
                    },
                    title = { Text(project) },
                    text = {
                        Text(
                            "Проект сохранён. Инструменты " +
                            "видеомонтажа будут добавляться " +
                            "на следующих этапах."
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                selectedProject = null
                            }
                        ) {
                            Text("Понятно")
                        }
                    }
                )
            }
        }
    }
}
