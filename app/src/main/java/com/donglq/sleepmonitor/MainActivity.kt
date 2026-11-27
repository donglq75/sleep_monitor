package com.donglq.sleepmonitor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private enum class Screen { HOME, SOUNDS, MONITOR, REPORT }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SleepMonitorApp() }
    }
}

@Composable
private fun SleepMonitorApp() {
    var screen by remember { mutableStateOf(Screen.HOME) }
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when (screen) {
                Screen.HOME -> HomeScreen(
                    onSounds = { screen = Screen.SOUNDS },
                    onMonitor = { screen = Screen.MONITOR },
                    onReport = { screen = Screen.REPORT }
                )
                Screen.SOUNDS -> SoundsScreen(onBack = { screen = Screen.HOME })
                Screen.MONITOR -> MonitorScreen(onBack = { screen = Screen.HOME })
                Screen.REPORT -> ReportScreen(onBack = { screen = Screen.HOME })
            }
        }
    }
}

@Composable
private fun HomeScreen(onSounds: () -> Unit, onMonitor: () -> Unit, onReport: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Sleep Monitor", style = MaterialTheme.typography.headlineLarge)
        Text("Ngủ ngon hơn · Hiểu giấc ngủ của bạn", style = MaterialTheme.typography.bodyLarge)
        HomeCard("1. Âm thanh dễ ngủ",
            "Chọn âm thanh, đặt thời gian phát và tự động chuyển sang giám sát.",
            "Thiết lập âm thanh", onSounds)
        HomeCard("2. Giám sát ban đêm",
            "Phát hiện thử nghiệm tiếng ngáy và tiếng nói. Chỉ lưu đoạn có sự kiện.",
            "Bắt đầu giám sát", onMonitor)
        HomeCard("3. Báo cáo giấc ngủ",
            "Xem dòng thời gian sự kiện và nghe lại các đoạn ghi âm.",
            "Xem báo cáo", onReport)
        Spacer(Modifier.weight(1f))
        Text("Thử nghiệm 1.0.1 · Dữ liệu lưu trên thiết bị", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun HomeCard(title: String, description: String, action: String, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(description)
            Spacer(Modifier.height(10.dp))
            Button(onClick = onClick) { Text(action) }
        }
    }
}

@Composable
private fun PageHeader(title: String, onBack: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        OutlinedButton(onClick = onBack) { Text("Quay lại") }
    }
}

@Composable
private fun SoundsScreen(onBack: () -> Unit) {
    var selected by remember { mutableStateOf("Tiếng mưa") }
    var minutes by remember { mutableFloatStateOf(30f) }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageHeader("Âm thanh dễ ngủ", onBack)
        Text("Chọn âm thanh")
        listOf("Tiếng mưa", "Tiếng suối", "Tiếng quạt", "Tiếng sóng biển", "Nhạc thư giãn").forEach { item ->
            OutlinedButton(onClick = { selected = item }, modifier = Modifier.fillMaxWidth()) {
                Text(if (selected == item) "✓  $item" else item)
            }
        }
        Text("Thời lượng: ${minutes.toInt()} phút")
        Slider(value = minutes, onValueChange = { minutes = it }, valueRange = 5f..180f)
        Text("Đã chọn: $selected")
        Text("Bước giao diện: danh sách và hẹn giờ đã có. Bộ phát âm thanh sẽ được nối ở bản kế tiếp; hiện chưa phát tiếng.")
    }
}

@Composable
private fun MonitorScreen(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageHeader("Giám sát ban đêm", onBack)
        Text("Trạng thái: Chưa bắt đầu", style = MaterialTheme.typography.titleMedium)
        Text("Ứng dụng sẽ cần quyền microphone và dịch vụ chạy nền để tiếp tục giám sát khi màn hình tắt.")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Nguyên tắc riêng tư", style = MaterialTheme.typography.titleMedium)
                Text("Chỉ lưu các đoạn âm thanh quanh sự kiện được phát hiện. Không ghi và lưu toàn bộ đêm.")
                Text("Âm thanh được xử lý trên điện thoại; không tự động tải lên đám mây.")
            }
        }
        Text("Bước giao diện: màn hình trạng thái đã có. Chưa bật ghi âm cho đến khi dịch vụ giám sát và cơ chế lưu clip được hoàn thiện.")
    }
}

@Composable
private fun ReportScreen(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageHeader("Báo cáo giấc ngủ", onBack)
        Text("Chưa có dữ liệu đêm ngủ", style = MaterialTheme.typography.titleMedium)
        Text("Sau khi hoàn tất một phiên giám sát, các sự kiện sẽ xuất hiện tại đây.")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Dự kiến hiển thị")
                Text("• Mốc thời gian bắt đầu/kết thúc")
                Text("• Số lần phát hiện tiếng ngáy")
                Text("• Số lần phát hiện tiếng nói")
                Text("• Nghe lại từng đoạn âm thanh sự kiện")
            }
        }
    }
}
