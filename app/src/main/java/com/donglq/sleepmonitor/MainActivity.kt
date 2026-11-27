package com.donglq.sleepmonitor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SleepMonitorApp() }
    }
}

@Composable
private fun SleepMonitorApp() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.fillMaxSize().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text("Sleep Monitor", style = MaterialTheme.typography.headlineLarge)
                Text("Ngủ ngon hơn · Hiểu giấc ngủ của bạn", style = MaterialTheme.typography.bodyLarge)
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("1. Âm thanh dễ ngủ", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        Text("Chọn âm thanh, đặt thời gian phát và tự động chuyển sang giám sát.")
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = { /* Sleep sound picker will be added next. */ }) {
                            Text("Thiết lập âm thanh")
                        }
                    }
                }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("2. Giám sát ban đêm", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        Text("Phát hiện thử nghiệm tiếng ngáy và tiếng nói. Chỉ lưu đoạn có sự kiện.")
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = { /* Explicit microphone session will be added next. */ }) {
                            Text("Bắt đầu giám sát")
                        }
                    }
                }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("3. Báo cáo giấc ngủ", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        Text("Xem dòng thời gian sự kiện và nghe lại các đoạn ghi âm.")
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = { /* Event review will be added next. */ }) {
                            Text("Xem báo cáo")
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                Text("Thử nghiệm 1.0.0 · Dữ liệu lưu trên thiết bị", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
