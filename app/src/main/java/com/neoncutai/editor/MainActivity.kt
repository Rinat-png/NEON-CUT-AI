package com.neoncutai.editor

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { NeonCutEditor() } }
    }
}

@Composable
private fun NeonCutEditor() {
    var videoUri by remember { mutableStateOf<Uri?>(null) }
    var audioUri by remember { mutableStateOf<Uri?>(null) }
    var playhead by rememberSaveable { mutableStateOf(0.15f) }
    var speed by rememberSaveable { mutableStateOf(1f) }
    var textLayerCount by rememberSaveable { mutableStateOf(0) }
    var notice by rememberSaveable { mutableStateOf("Добавь видео, чтобы начать монтаж") }
    val splitPoints = remember { mutableStateListOf<Float>() }

    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) { videoUri = uri; notice = "Видео добавлено в проект" }
    }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) { audioUri = uri; notice = "Аудио добавлено в проект" }
    }

    Surface(Modifier.fillMaxSize(), color = Color(0xFF090B12)) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("NEON CUT AI", color = Color(0xFF65F4D0), fontSize = 23.sp, fontWeight = FontWeight.Black)
                    Text("Мобильная монтажная", color = Color(0xFF9AA3B8), fontSize = 12.sp)
                }
                Text("9:16", color = Color(0xFFB6C2D9), fontWeight = FontWeight.Bold)
            }

            Card(Modifier.fillMaxWidth().height(210.dp), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF171D2A))) {
                Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("▶", color = Color(0xFF65F4D0), fontSize = 42.sp)
                        Spacer(Modifier.height(8.dp))
                        Text(videoUri?.lastPathSegment ?: "Предпросмотр видео", color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text(if (videoUri == null) "Импортируй ролик с устройства" else "Файл добавлен · видеоплеер подключим следующим этапом", color = Color(0xFF9AA3B8), fontSize = 11.sp)
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { videoPicker.launch("video/*") }, modifier = Modifier.weight(1f)) { Text("＋ Видео") }
                OutlinedButton(onClick = { audioPicker.launch("audio/*") }, modifier = Modifier.weight(1f)) { Text("♫ Аудио") }
            }

            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF141A26))) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("ТАЙМЛАЙН", color = Color.White, fontWeight = FontWeight.Bold)
                        Text("00:${(playhead * 60).toInt().toString().padStart(2, '0')}", color = Color(0xFF65F4D0))
                    }
                    TimelineTrack("V1", videoUri?.lastPathSegment ?: "Видео", Color(0xFF237D75), splitPoints.size)
                    TimelineTrack("A1", audioUri?.lastPathSegment ?: "Аудио", Color(0xFF7652A8), 0)
                    TimelineTrack("T1", "Текст (${textLayerCount})", Color(0xFF9B7130), 0)
                    Text("Позиция", color = Color(0xFFB6C2D9), fontSize = 12.sp)
                    Slider(value = playhead, onValueChange = { playhead = it }, valueRange = 0f..1f)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                if (videoUri == null) notice = "Сначала добавь видео"
                                else if (splitPoints.none { kotlin.math.abs(it - playhead) < 0.015f }) {
                                    splitPoints.add(playhead); splitPoints.sort(); notice = "Метка разреза добавлена на таймлайн"
                                } else notice = "В этой точке уже есть метка"
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("✂ Разрезать") }
                        OutlinedButton(
                            onClick = {
                                if (splitPoints.isNotEmpty()) { splitPoints.removeAt(splitPoints.lastIndex); notice = "Последняя метка удалена" }
                                else notice = "Нет меток для отмены"
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("Отменить") }
                    }
                    Text("Метки разреза: ${splitPoints.size}. Пока это отметки на таймлайне, исходный видеофайл не разрезается.", color = Color(0xFF9AA3B8), fontSize = 11.sp)
                }
            }

            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF141A26))) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("ИНСТРУМЕНТЫ", color = Color.White, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { textLayerCount++; notice = "Текстовый слой добавлен на дорожку T1" }, modifier = Modifier.weight(1f)) { Text("＋ Текст") }
                        OutlinedButton(onClick = { textLayerCount = 0; notice = "Текстовые слои очищены" }, modifier = Modifier.weight(1f)) { Text("Очистить текст") }
                    }
                    Text("Скорость: ${"%.1f".format(speed)}×", color = Color(0xFFB6C2D9))
                    Slider(value = speed, onValueChange = { speed = it }, valueRange = 0.25f..3f)
                    Button(onClick = { notice = "Экспорт видео пока не подключён — это следующий этап разработки" }, modifier = Modifier.fillMaxWidth()) {
                        Text("Экспортировать видео")
                    }
                }
            }

            Text(notice, Modifier.fillMaxWidth().background(Color(0xFF172B2B), RoundedCornerShape(10.dp)).padding(12.dp), color = Color(0xFF8EF5D9), fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun TimelineTrack(label: String, clipName: String, tint: Color, splitCount: Int) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, color = Color(0xFF9AA3B8), fontSize = 11.sp, modifier = Modifier.width(22.dp))
        Box(Modifier.weight(1f).height(34.dp).background(Color(0xFF252D3D), RoundedCornerShape(7.dp)).padding(3.dp)) {
            Box(Modifier.fillMaxWidth().height(28.dp).background(tint.copy(alpha = 0.8f), RoundedCornerShape(5.dp)), contentAlignment = Alignment.CenterStart) {
                Text(if (splitCount > 0) "$clipName · $splitCount мет." else clipName, Modifier.padding(horizontal = 8.dp), color = Color.White, fontSize = 10.sp, maxLines = 1)
            }
        }
    }
}
