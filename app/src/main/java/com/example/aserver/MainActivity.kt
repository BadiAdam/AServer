package com.example.aserver

import android.app.ActivityManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.*
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// --- SİBER KOKPİT RENK PALETİ ---
val NeonCyan = Color(0xFF00E5FF)
val OledBlack = Color(0xFF000000)
val GlassSurface = Color(0xFF0A0A0A)
val GlassBorder = Color(0xFF1A1A1A)

// --- DİL YARDIMCI FONKSİYONLARI ---
fun setLocale(context: Context, languageCode: String) {
    val locale = Locale(languageCode)
    Locale.setDefault(locale)
    val resources = context.resources
    val config = resources.configuration
    config.setLocale(locale)
    resources.updateConfiguration(config, resources.displayMetrics)
}

enum class ServerState {
    STOPPED, STARTING, READY
}

data class PluginInfo(val name: String, val desc: String, val url: String, val fileName: String)
data class SpigetPlugin(val id: Int, val name: String, val tag: String)
data class ModrinthMod(val id: String, val title: String, val description: String)

// YENİ EKLENEN: Akıllı Çökme Dedektifi Veri Yapısı
data class CrashReport(val title: String, val diagnosis: String, val solution: String)

data class ScheduledTask(
    val id: String = UUID.randomUUID().toString(),
    val type: String,
    val payload: String,
    val intervalMinutes: Int,
    val isRunning: Boolean = true
)

// YENİ EKLENEN: Arayüzü kasmamak için arka planda toplanan sunucu verileri
data class ServerDisplayInfo(
    val folder: File,
    val name: String,
    val javaVer: String,
    val hasPlayit: Boolean
)

fun buildRconPacket(id: Int, type: Int, body: ByteArray): ByteArray {
    val length = 10 + body.size
    val buffer = java.nio.ByteBuffer.allocate(length + 4)
    buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)
    buffer.putInt(length)
    buffer.putInt(id)
    buffer.putInt(type)
    buffer.put(body)
    buffer.put(0.toByte())
    buffer.put(0.toByte())
    return buffer.array()
}

class MainActivity : ComponentActivity() {
    private val appLogs = mutableStateListOf<String>()
    private var serverState = mutableStateOf(ServerState.STOPPED)
    private var termuxCommand = ""
    private var activeServerProfile = mutableStateOf("")
    private var isServerActuallyOnline = mutableStateOf(false)

    // YENİ EKLENEN: Dedektifin yakaladığı son rapor burada duracak
    private var latestCrashReport = mutableStateOf<CrashReport?>(null)

    private val scheduledTasks = mutableStateListOf<ScheduledTask>()
    private val taskJobs = mutableMapOf<String, Job>()

    private lateinit var sharedPref: android.content.SharedPreferences

    private val versionMap = mapOf(
        "26.1" to "26.1",
        "1.21.11" to "1.21.11",
        "1.21.4" to "1.21.4",
        "1.21.1" to "1.21.1",
        "1.20.4" to "1.20.4",
        "1.20.1" to "1.20.1",
        "1.19.4" to "1.19.4",
        "1.19.2" to "1.19.2",
        "1.17.1" to "1.17.1",
        "1.16.5" to "1.16.5",
        "1.12.2" to "1.12.2",
        "1.8.8" to "1.8.8"
    )

    private fun getRconDetails(serverName: String): Pair<Int, String> {
        var port = 25575
        var password = "aserver123"
        try {
            val propsFile = File(Environment.getExternalStorageDirectory(), "AServer/servers/$serverName/server.properties")
            if (propsFile.exists()) {
                propsFile.readLines().forEach { line ->
                    if (line.startsWith("rcon.port=")) port = line.substringAfter("=").trim().toIntOrNull() ?: 25575
                    if (line.startsWith("rcon.password=")) password = line.substringAfter("=").trim()
                }
            }
        } catch (e: Exception) {}
        return Pair(port, password)
    }

    // YENİ EKLENEN: Kara Kutu (Blackbox) Destekli Akıllı Çökme Analiz Motoru
    private fun analyzeCrash(serverName: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val basePath = Environment.getExternalStorageDirectory().absolutePath
                val logFile = File(basePath, "AServer/servers/$serverName/logs/latest.log")
                val karaKutuFile = File(basePath, "AServer/servers/$serverName/termux_karakutu.log") // İŞTE KARA KUTU!

                var logText = ""

                // Hem ana kayıtları hem de kara kutuyu birleştirip okuyoruz
                if (logFile.exists()) logText += readLastLines(logFile, 20000)
                if (karaKutuFile.exists()) logText += "\n" + readLastLines(karaKutuFile, 20000)

                if (logText.isBlank()) return@launch

                var report: CrashReport? = null

                if (logText.contains("UnsupportedClassVersionError")) {
                    report = CrashReport("Java Sürüm Uyuşmazlığı", "Yüklediğiniz bir eklenti veya mod, sunucunun mevcut Java sürümünden daha yenisini istiyor.", "Ayarlardan veya sunucu özelliklerinden Java 21'i seçerek sistemi yeniden kurun.")
                } else if (logText.contains("FAILED TO BIND TO PORT") || logText.contains("Address already in use")) {
                    report = CrashReport("Port İşgali (25565)", "Sunucunun kullanmak istediği port başka bir uygulama veya arka planda gizlice açık kalan Termux tarafından rehin alınmış.", "Cihazın bildirim çubuğundan açık Termux sekmelerini kapatın veya sunucu portunu (Örn: 25566) değiştirin.")
                } else if (logText.contains("OutOfMemoryError") || logText.contains("heap space") || logText.contains("Killed")) {
                    report = CrashReport("Kritik RAM Yetersizliği", "Cihazın donanımsal belleği (RAM) veya sunucuya atadığınız miktar, bu haritayı yüklemeye yetmedi.", "Sunucu ayarlarına girerek ayrılan RAM miktarını artırın veya telefondaki diğer tüm arka plan uygulamalarını kapatın.")
                } else if (logText.contains("Could not load 'plugins") || logText.contains("UnknownDependencyException") || logText.contains("which is missing!")) {
                    report = CrashReport("Eksik Mod/Eklenti (Dependency)", "Yüklediğiniz bir mod veya eklenti, çalışmak için başka bir kök dosyaya (Örn: Fabric-API, XaeroLib, Vault vb.) ihtiyaç duyuyor.", "Ekranda gördüğünüz konsol uyarısını okuyarak eksik olan temel dosyayı bulun ve mağazadan indirin.")
                } else if (logText.contains("eula.txt") && logText.contains("agree")) {
                    report = CrashReport("EULA Sözleşmesi", "Minecraft'ın son kullanıcı sözleşmesini (EULA) kabul etmeniz gerekiyor.", "Dosya yöneticisinden eula.txt dosyasını bulun ve içindeki 'eula=false' değerini 'eula=true' olarak değiştirin.")
                }

                if (report != null) {
                    withContext(Dispatchers.Main) {
                        latestCrashReport.value = report
                    }
                }
            } catch (e: Exception) {}
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sharedPref = getSharedPreferences("AServerSettings", Context.MODE_PRIVATE)
        val savedLang = sharedPref.getString("language", "tr") ?: "tr"
        setLocale(this, savedLang)

        activeServerProfile.value = sharedPref.getString("activeProfile", "") ?: ""

        requestStoragePermission()
        createServerFolders()

        val permissionsToRequest = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (checkSelfPermission("com.termux.permission.RUN_COMMAND") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add("com.termux.permission.RUN_COMMAND")
        }

        if (permissionsToRequest.isNotEmpty()) {
            try {
                requestPermissions(permissionsToRequest.toTypedArray(), 101)
            } catch (e: Exception) {}
        }

        setContent {
            val serversBaseDir = File(Environment.getExternalStorageDirectory(), "AServer/servers")
            var showSplash by remember { mutableStateOf(true) }

            val initialOnboardingState = getPreferences(Context.MODE_PRIVATE).getBoolean("isFirstTime", true)
            var showOnboarding by remember { mutableStateOf(initialOnboardingState) }

            var currentLanguage by remember { mutableStateOf(savedLang) }

            LaunchedEffect(activeServerProfile.value) {
                val currentServer = activeServerProfile.value
                if (currentServer.isNotEmpty()) {
                    var failCount = 0
                    var waitCount = 0

                    val (rconPort, _) = getRconDetails(currentServer)

                    while (activeServerProfile.value.isNotEmpty()) {
                        val isOpen = withContext(Dispatchers.IO) {
                            try {
                                java.net.Socket().use { s ->
                                    s.connect(java.net.InetSocketAddress("127.0.0.1", rconPort), 1500)
                                    true
                                }
                            } catch (e: Exception) { false }
                        }

                        if (isOpen) {
                            if (!isServerActuallyOnline.value) {
                                isServerActuallyOnline.value = true
                                serverState.value = ServerState.READY
                                logToApp(">>> [SİSTEM] Başarılı! Sunucu Çevrimiçi ve komutlara hazır.")
                            }
                            failCount = 0
                            waitCount = 0
                        } else {
                            if (isServerActuallyOnline.value) {
                                failCount++
                                if (failCount >= 3) {
                                    isServerActuallyOnline.value = false
                                    logToApp(">>> [SİSTEM] DİKKAT: Sunucu bağlantısı kesildi veya Termux kapandı!")
                                    analyzeCrash(currentServer) // DEDEKTİF DEVREDE!
                                    serverState.value = ServerState.STOPPED
                                    activeServerProfile.value = ""
                                    sharedPref.edit().putString("activeProfile", "").apply()
                                    try { stopService(Intent(this@MainActivity, ServerKeepAliveService::class.java)) } catch (e: Exception) {}
                                }
                            } else {
                                waitCount++
                                if (waitCount >= 300) {
                                    logToApp(">>> [SİSTEM HATASI] Sunucu 15 dakika içerisinde yanıt vermedi. İşlem iptal ediliyor. Eğer ilk kurulumsa ve internetiniz yavaşsa işlem Termux'ta devam ediyor olabilir!")
                                    isServerActuallyOnline.value = false
                                    analyzeCrash(currentServer) // DEDEKTİF DEVREDE!
                                    serverState.value = ServerState.STOPPED
                                    activeServerProfile.value = ""
                                    sharedPref.edit().putString("activeProfile", "").apply()
                                    try { stopService(Intent(this@MainActivity, ServerKeepAliveService::class.java)) } catch (e: Exception) {}
                                }
                            }
                        }
                        delay(3000)
                    }
                } else {
                    isServerActuallyOnline.value = false
                    serverState.value = ServerState.STOPPED
                }
            }

            MaterialTheme(colorScheme = darkColorScheme(
                primary = NeonCyan,
                surface = GlassSurface,
                background = OledBlack,
                onSurface = Color.White
            )) {
                // YENİ EKLENEN: Akıllı Çökme Dedektifi Arayüzü (Her şeyin üstünde çıkar)
                val crash = latestCrashReport.value
                if (crash != null) {
                    AlertDialog(
                        onDismissRequest = { latestCrashReport.value = null },
                        modifier = Modifier.border(1.dp, Color(0xFFE53935), RoundedCornerShape(28.dp)), // Kenarlık modifier içine alındı
                        containerColor = GlassSurface,
                        title = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.WarningAmber, contentDescription = "Crash", tint = Color(0xFFE53935))
                                Spacer(Modifier.width(8.dp))
                                Text("SİSTEM ÇÖKME RAPORU", color = Color(0xFFE53935), fontWeight = FontWeight.Black)
                            }
                        },
                        text = {
                            Column {
                                Text("HATA: ${crash.title}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                Spacer(Modifier.height(12.dp))
                                Text("TEŞHİS:", color = NeonCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Text(crash.diagnosis, color = Color.LightGray, fontSize = 14.sp)
                                Spacer(Modifier.height(12.dp))
                                Text("ÇÖZÜM:", color = Color(0xFF00E676), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Text(crash.solution, color = Color.White, fontSize = 14.sp)
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = { latestCrashReport.value = null },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935))
                            ) {
                                Text("ANLADIM", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                    )
                }

                if (showSplash) {
                    SplashScreen { showSplash = false }
                } else if (showOnboarding) {
                    OnboardingGuideScreen(onFinish = {
                        getPreferences(Context.MODE_PRIVATE).edit().putBoolean("isFirstTime", false).apply()
                        showOnboarding = false
                    })
                } else {
                    var selectedTab by remember { mutableIntStateOf(if (activeServerProfile.value.isNotEmpty()) 3 else 1) }

                    Scaffold(
                        containerColor = OledBlack,
                        bottomBar = {
                            Box(modifier = Modifier
                                .fillMaxWidth()
                                .background(Color.Transparent)
                                .padding(horizontal = 16.dp, vertical = 16.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(32.dp))
                                        .background(Color(0xFF101010).copy(alpha = 0.95f))
                                        .border(1.dp, Color(0xFF222222), RoundedCornerShape(32.dp))
                                        .padding(horizontal = 8.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceAround,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val navItems = listOf(
                                        Triple(0, Icons.Rounded.AddBox, stringResource(R.string.tab_new)),
                                        Triple(1, Icons.Rounded.Storage, stringResource(R.string.tab_servers)),
                                        Triple(2, Icons.Rounded.Folder, stringResource(R.string.tab_files)),
                                        Triple(4, Icons.Rounded.People, stringResource(R.string.tab_players)),
                                        Triple(3, Icons.Rounded.Terminal, stringResource(R.string.tab_console)),
                                        Triple(5, Icons.Rounded.Settings, stringResource(R.string.tab_settings))
                                    )
                                    navItems.forEach { (index, icon, label) ->
                                        val isSelected = selectedTab == index
                                        val tintColor = if (isSelected) NeonCyan else Color.Gray

                                        IconButton(
                                            onClick = { selectedTab = index },
                                            modifier = Modifier
                                                .background(if (isSelected) NeonCyan.copy(alpha = 0.1f) else Color.Transparent, CircleShape)
                                        ) {
                                            Icon(
                                                imageVector = icon,
                                                contentDescription = label,
                                                tint = tintColor,
                                                modifier = Modifier.size(if(isSelected) 28.dp else 24.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    ) { padding ->
                        Box(modifier = Modifier.padding(bottom = 80.dp).fillMaxSize().background(OledBlack)) {
                            key(currentLanguage) {
                                Crossfade(
                                    targetState = selectedTab,
                                    animationSpec = tween(durationMillis = 400),
                                    label = "Tab Transition"
                                ) { targetTab ->
                                    when (targetTab) {
                                        0 -> AdvancedServerSetupScreen(
                                            serverState = serverState.value, versionMap = versionMap,
                                            onStart = { settings -> startServerProcess(settings) },
                                            onOpenTermux = { openTermux() }
                                        )
                                        1 -> MyServersScreen(
                                            baseDir = serversBaseDir,
                                            activeServerName = activeServerProfile.value,
                                            onPlay = { folderName, javaVer ->
                                                activeServerProfile.value = folderName
                                                sharedPref.edit().putString("activeProfile", folderName).apply()
                                                launchExistingServer(folderName, javaVer)
                                            },
                                            onGoToConsole = { selectedTab = 3 }
                                        )
                                        2 -> FileManagerScreen(serversBaseDir)
                                        3 -> RealConsoleScreen(
                                            activeServerName = activeServerProfile.value,
                                            appLogs = appLogs,
                                            isOnline = isServerActuallyOnline.value,
                                            scheduledTasks = scheduledTasks,
                                            onAddTask = { type, payload, interval -> addTask(type, payload, interval) },
                                            onToggleTask = { id, state -> toggleTaskState(id, state) },
                                            onRemoveTask = { id -> removeTask(id) },
                                            onSendCommand = { cmd ->
                                                if (cmd == "FORCE_CANCEL") {
                                                    logToApp(">>> [SİSTEM] Kullanıcı işlemi manuel olarak iptal etti.")
                                                    serverState.value = ServerState.STOPPED
                                                    activeServerProfile.value = ""
                                                    isServerActuallyOnline.value = false
                                                    sharedPref.edit().putString("activeProfile", "").apply()
                                                    try { stopService(Intent(this@MainActivity, ServerKeepAliveService::class.java)) } catch (e: Exception) {}
                                                } else {
                                                    val isStopCmd = cmd.trim().lowercase() == "stop"
                                                    sendRconCommand(cmd, isStopCmd)
                                                }
                                            }
                                        )
                                        4 -> PlayersScreen(
                                            activeServerName = activeServerProfile.value,
                                            isOnline = isServerActuallyOnline.value,
                                            onSendCommand = { cmd -> sendRconCommand(cmd, false) },
                                            fetchPlayers = { fetchOnlinePlayers() }
                                        )
                                        5 -> SettingsScreen(
                                            currentLanguage = currentLanguage,
                                            onLanguageChange = { newLang ->
                                                sharedPref.edit().putString("language", newLang).apply()
                                                setLocale(this@MainActivity, newLang)
                                                currentLanguage = newLang
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun addTask(type: String, payload: String, interval: Int) {
        val task = ScheduledTask(type = type, payload = payload, intervalMinutes = interval)
        scheduledTasks.add(task)
        startTaskJob(task)
    }

    private fun toggleTaskState(taskId: String, isRunning: Boolean) {
        val index = scheduledTasks.indexOfFirst { it.id == taskId }
        if (index != -1) {
            val updatedTask = scheduledTasks[index].copy(isRunning = isRunning)
            scheduledTasks[index] = updatedTask
            if (isRunning) {
                startTaskJob(updatedTask)
            } else {
                taskJobs[taskId]?.cancel()
                taskJobs.remove(taskId)
            }
        }
    }

    private fun removeTask(taskId: String) {
        taskJobs[taskId]?.cancel()
        taskJobs.remove(taskId)
        scheduledTasks.removeAll { it.id == taskId }
    }

    private fun startTaskJob(task: ScheduledTask) {
        taskJobs[task.id]?.cancel()
        taskJobs[task.id] = CoroutineScope(Dispatchers.IO).launch {
            while (true) {
                delay(task.intervalMinutes * 60 * 1000L)
                if (isServerActuallyOnline.value) {
                    val cmd = if (task.type == "Duyuru") "say [Duyuru] ${task.payload}" else task.payload
                    sendRconCommand(cmd, false)
                    launch(Dispatchers.Main) {
                        logToApp(">>> [OTOMASYON] Görev Çalıştı: $cmd")
                    }
                }
            }
        }
    }

    private fun readRconResponse(input: InputStream): String {
        try {
            val lengthBytes = ByteArray(4)
            if (input.read(lengthBytes) < 4) return ""
            val buffer = java.nio.ByteBuffer.wrap(lengthBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val length = buffer.int
            if (length < 10 || length > 4096) return ""

            val payload = ByteArray(length)
            var bytesRead = 0
            while (bytesRead < length) {
                val result = input.read(payload, bytesRead, length - bytesRead)
                if (result == -1) break
                bytesRead += result
            }

            val bodyBytes = payload.copyOfRange(8, payload.size - 2)
            return String(bodyBytes, Charsets.UTF_8).trim()
        } catch (e: Exception) {
            return ""
        }
    }

    private suspend fun fetchOnlinePlayers(): List<String> {
        val currentServer = activeServerProfile.value
        if (currentServer.isEmpty()) return emptyList()

        val (rconPort, rconPass) = getRconDetails(currentServer)

        return withContext(Dispatchers.IO) {
            try {
                java.net.Socket("127.0.0.1", rconPort).use { socket ->
                    socket.soTimeout = 3000
                    val out = socket.getOutputStream()
                    val input = socket.getInputStream()

                    out.write(buildRconPacket(1, 3, rconPass.toByteArray()))
                    out.flush()
                    readRconResponse(input)

                    out.write(buildRconPacket(2, 2, "list".toByteArray()))
                    out.flush()

                    val response = readRconResponse(input)
                    if (response.contains("players online:")) {
                        val namesPart = response.substringAfter("players online:").trim()
                        if (namesPart.isNotEmpty()) {
                            return@withContext namesPart.split(",").map { it.trim() }
                        }
                    }
                    return@withContext emptyList()
                }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    private fun sendRconCommand(command: String, isStop: Boolean = false) {
        if (command.isBlank()) return
        val currentServer = activeServerProfile.value
        if (currentServer.isEmpty()) return

        val (rconPort, rconPass) = getRconDetails(currentServer)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                java.net.Socket("127.0.0.1", rconPort).use { socket ->
                    socket.soTimeout = 3000
                    val out = socket.getOutputStream()
                    val input = socket.getInputStream()

                    out.write(buildRconPacket(1, 3, rconPass.toByteArray()))
                    out.flush()
                    readRconResponse(input)

                    out.write(buildRconPacket(2, 2, command.toByteArray()))
                    out.flush()

                    val response = readRconResponse(input)
                    if (response.isNotEmpty() && !isStop) {
                        logToApp(">>> [SUNUCU]: $response")
                    } else if (!isStop) {
                        logToApp(">>> [RCON] Komut başarıyla gönderildi: /$command")
                    }
                }

                if (isStop) {
                    logToApp(">>> Sunucu güvenli bir şekilde kapatılıyor. Harita kaydediliyor...")
                    try { stopService(Intent(this@MainActivity, ServerKeepAliveService::class.java)) } catch (e: Exception) {}
                }
            } catch (e: Exception) {
                if (!isStop) logToApp(">>> [HATA] Komut gönderilemedi! Sunucu henüz açılmamış veya RCON aktif değil.")
            }
        }
    }

    private fun startServerProcess(settings: Map<String, String>) {
        serverState.value = ServerState.STARTING
        appLogs.clear()

        val profileName = settings["profileName"]?.replace(" ", "_") ?: "YeniSunucu"
        val softwareType = settings["software"] ?: "PaperMC"
        activeServerProfile.value = profileName
        sharedPref.edit().putString("activeProfile", profileName).apply()

        try {
            val serviceIntent = Intent(this@MainActivity, ServerKeepAliveService::class.java)
            serviceIntent.putExtra("SERVER_NAME", profileName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            logToApp(">>> [SİSTEM UYARISI] Arka plan servisi başlatılamadı.")
        }

        logToApp(">>> Sistem Hazırlanıyor ($softwareType), lütfen bekleyin...")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val basePath = Environment.getExternalStorageDirectory().absolutePath
                val serverDir = File(basePath, "AServer/servers/$profileName")
                if (!serverDir.exists()) serverDir.mkdirs()

                val mcVersion = settings["versionNumber"] ?: "1.21.11"

                File(serverDir, "server_type.txt").writeText(softwareType)
                File(serverDir, "mc_version.txt").writeText(mcVersion)

                if (softwareType == "PaperMC (Eklentiler İçin)") {
                    val jarFile = File(serverDir, "server.jar")
                    if (!jarFile.exists() || jarFile.length() == 0L) {
                        logToApp(">>> PaperMC sunucularına bağlanılıyor...")
                        val apiUrl = "https://api.papermc.io/v2/projects/paper/versions/$mcVersion"
                        val apiConnection = URL(apiUrl).openConnection() as HttpURLConnection
                        apiConnection.requestMethod = "GET"

                        if (apiConnection.responseCode != HttpURLConnection.HTTP_OK) {
                            logToApp(">>> API Hatası: Sürüm bulunamadı (Kod: ${apiConnection.responseCode})")
                            serverState.value = ServerState.STOPPED
                            return@launch
                        }

                        val response = apiConnection.inputStream.bufferedReader().readText()
                        val jsonObject = JSONObject(response)
                        val buildsArray = jsonObject.getJSONArray("builds")
                        val latestBuild = buildsArray.getInt(buildsArray.length() - 1)

                        val finalDownloadUrl = "https://api.papermc.io/v2/projects/paper/versions/$mcVersion/builds/$latestBuild/downloads/paper-$mcVersion-$latestBuild.jar"

                        logToApp(">>> En güncel dosya bulundu (Build: $latestBuild) İndiriliyor...")

                        val url = URL(finalDownloadUrl)
                        val connection = url.openConnection() as HttpURLConnection
                        connection.requestMethod = "GET"
                        connection.setRequestProperty("User-Agent", "Mozilla/5.0")
                        connection.connectTimeout = 15000
                        connection.readTimeout = 60000
                        connection.connect()

                        if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                            connection.inputStream.use { input -> jarFile.outputStream().use { output -> input.copyTo(output) } }
                            logToApp(">>> Sürüm başarıyla indirildi!")
                        } else {
                            logToApp(">>> İndirme Hatası (Kod: ${connection.responseCode})")
                            serverState.value = ServerState.STOPPED
                            return@launch
                        }
                    }
                }
                else {
                    val installerFile = File(serverDir, "fabric-installer.jar")
                    if (!installerFile.exists() || installerFile.length() == 0L) {
                        logToApp(">>> Fabric Meta API'sine bağlanılıyor...")
                        val metaUrl = "https://meta.fabricmc.net/v2/versions/installer"
                        val metaConnection = URL(metaUrl).openConnection() as HttpURLConnection
                        metaConnection.requestMethod = "GET"

                        if (metaConnection.responseCode != HttpURLConnection.HTTP_OK) {
                            logToApp(">>> Fabric API Hatası (Kod: ${metaConnection.responseCode})")
                            serverState.value = ServerState.STOPPED
                            return@launch
                        }

                        val response = metaConnection.inputStream.bufferedReader().readText()
                        val jsonArray = org.json.JSONArray(response)
                        val latestInstaller = jsonArray.getJSONObject(0).getString("version")

                        val finalDownloadUrl = "https://maven.fabricmc.net/net/fabricmc/fabric-installer/$latestInstaller/fabric-installer-$latestInstaller.jar"

                        logToApp(">>> Fabric Yükleyici (v$latestInstaller) indiriliyor...")
                        val url = URL(finalDownloadUrl)
                        val connection = url.openConnection() as HttpURLConnection
                        connection.requestMethod = "GET"
                        connection.setRequestProperty("User-Agent", "Mozilla/5.0")
                        connection.connectTimeout = 15000
                        connection.readTimeout = 60000
                        connection.connect()

                        if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                            connection.inputStream.use { input -> installerFile.outputStream().use { output -> input.copyTo(output) } }
                            logToApp(">>> Yükleyici başarıyla indirildi!")
                        } else {
                            logToApp(">>> İndirme Hatası (Kod: ${connection.responseCode})")
                            serverState.value = ServerState.STOPPED
                            return@launch
                        }
                    }
                }

                File(serverDir, "eula.txt").writeText("eula=true")
                val javaVersion = when {
                    mcVersion == "26.1" || mcVersion.startsWith("1.22") -> "25"
                    mcVersion.startsWith("1.21") || mcVersion == "1.20.6" || mcVersion == "1.20.5" -> "21"
                    mcVersion.startsWith("1.20") || mcVersion.startsWith("1.19") || mcVersion.startsWith("1.18") || mcVersion.startsWith("1.17") -> "17"
                    mcVersion.contains("1.16.5") -> "11"
                    mcVersion.contains("1.12.2") || mcVersion.contains("1.8.8") -> "8"
                    else -> "21"
                }
                File(serverDir, "java_version.txt").writeText(javaVersion)
                File(serverDir, "ram_amount.txt").writeText(settings["ram"] ?: "2")

                val playitEnabled = settings["playitMode"] == "true"
                File(serverDir, "playit_enabled.txt").writeText(playitEnabled.toString())

                val props = """
                    server-port=${settings["port"]}
                    motd=${settings["motd"]}
                    difficulty=${settings["difficulty"]}
                    gamemode=${settings["gamemode"]}
                    max-players=${settings["maxPlayers"]}
                    view-distance=${settings["viewDistance"]}
                    online-mode=${settings["onlineMode"]}
                    hardcore=${settings["hardcore"]}
                    level-seed=${settings["seed"]}
                    enable-rcon=true
                    rcon.port=25575
                    rcon.password=aserver123
                """.trimIndent()
                File(serverDir, "server.properties").writeText(props)

                val opName = settings["opName"] ?: ""
                if (opName.isNotBlank()) {
                    val uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:$opName").toByteArray()).toString()
                    val opsJson = """
                        [
                          {
                            "uuid": "$uuid",
                            "name": "$opName",
                            "level": 4,
                            "bypassesPlayerLimit": false
                          }
                        ]
                    """.trimIndent()
                    File(serverDir, "ops.json").writeText(opsJson)
                }

                logToApp(">>> Profil Oluşturuldu: $profileName (Java $javaVersion)")

                val playitCmd = if (playitEnabled) {
                    "if [ ! -f ~/playit ]; then wget -qO ~/playit https://github.com/playit-cloud/playit-agent/releases/latest/download/playit-linux-aarch64 && chmod +x ~/playit; fi; rm -f $basePath/AServer/servers/$profileName/playit_log.txt; ~/playit > $basePath/AServer/servers/$profileName/playit_log.txt 2>&1 & "
                } else ""
                val javaInstallCommand = if (javaVersion == "25") {
                    "(apt-get install -y openjdk-$javaVersion-jre-headless wget || apt-get install -y openjdk-21-jre-headless wget)"
                } else {
                    "apt-get install -y openjdk-$javaVersion-jre-headless wget"
                }

                // KARA KUTU DİNLEME CİHAZI EKLENDİ
                if (softwareType == "PaperMC (Eklentiler İçin)") {
                    termuxCommand = "pkg install proot-distro -y && (proot-distro install ubuntu || true) && proot-distro login ubuntu -- bash -c \"apt-get update -y && apt-get install openjdk-$javaVersion-jre-headless wget -y && $playitCmd cd $basePath/AServer/servers/$profileName && java -Xmx${settings["ram"]}G -jar server.jar nogui 2>&1 | tee termux_karakutu.log\""
                } else {
                    termuxCommand = "pkg install proot-distro -y && (proot-distro install ubuntu || true) && proot-distro login ubuntu -- bash -c \"apt-get update -y && apt-get install openjdk-$javaVersion-jre-headless wget -y && $playitCmd cd $basePath/AServer/servers/$profileName && if [ ! -f fabric-server-launch.jar ]; then java -jar fabric-installer.jar server -mcversion $mcVersion -downloadMinecraft; fi && java -Xmx${settings["ram"]}G -jar fabric-server-launch.jar nogui 2>&1 | tee termux_karakutu.log\""
                }

                logToApp(">>> HER ŞEY HAZIR! Konsol sekmesine geçebilirsiniz.")
                serverState.value = ServerState.READY

            } catch (e: Exception) {
                logToApp(">>> [KRİTİK HATA] İşlem durduruldu! Detay: ${e.localizedMessage ?: e.javaClass.simpleName}")
                serverState.value = ServerState.STOPPED
            }
        }
    }

    private fun launchExistingServer(folderName: String, javaVer: String) {
        try {
            val serviceIntent = Intent(this@MainActivity, ServerKeepAliveService::class.java)
            serviceIntent.putExtra("SERVER_NAME", folderName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent) else startService(serviceIntent)
        } catch (e: Exception) {}

        try {
            val basePath = Environment.getExternalStorageDirectory().absolutePath
            val serverDir = File(basePath, "AServer/servers/$folderName")
            val ramFile = File(serverDir, "ram_amount.txt")
            val ram = if (ramFile.exists()) ramFile.readText().trim() else "2"

            val playitFile = File(serverDir, "playit_enabled.txt")
            val playitEnabled = if (playitFile.exists()) playitFile.readText().trim() == "true" else false

            val typeFile = File(serverDir, "server_type.txt")
            val isFabric = if (typeFile.exists()) typeFile.readText().contains("Fabric") else false

            val mcVersionFile = File(serverDir, "mc_version.txt")
            val mcVer = if (mcVersionFile.exists()) mcVersionFile.readText().trim() else "1.21.11"

            val playitCmd = if (playitEnabled) {
                "if [ ! -f ~/playit ]; then wget -qO ~/playit https://github.com/playit-cloud/playit-agent/releases/latest/download/playit-linux-aarch64 && chmod +x ~/playit; fi; rm -f $basePath/AServer/servers/$folderName/playit_log.txt; ~/playit > $basePath/AServer/servers/$folderName/playit_log.txt 2>&1 & "
            } else ""

            // KARA KUTU DİNLEME CİHAZI EKLENDİ
            if (isFabric) {
                termuxCommand = "proot-distro login ubuntu -- bash -c \"$playitCmd cd $basePath/AServer/servers/$folderName && if [ ! -f fabric-server-launch.jar ]; then java -jar fabric-installer.jar server -mcversion $mcVer -downloadMinecraft; fi && java -Xmx${ram}G -jar fabric-server-launch.jar nogui 2>&1 | tee termux_karakutu.log\""
            } else {
                termuxCommand = "proot-distro login ubuntu -- bash -c \"$playitCmd cd $basePath/AServer/servers/$folderName && java -Xmx${ram}G -jar server.jar nogui 2>&1 | tee termux_karakutu.log\""
            }

            serverState.value = ServerState.READY
            appLogs.clear()
            logToApp(">>> Mevcut sunucu başlatılıyor...")
            openTermux()
        } catch (e: Exception) {
            logToApp(">>> [HATA] Başlatma başarısız oldu: ${e.localizedMessage}")
        }
    }

    private fun openTermux() {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Termux Komutu", termuxCommand)
            clipboard.setPrimaryClip(clip)
            logToApp(">>> Komut kopyalandı! Termux açılıyor, sunucunun çevrimiçi olması biraz sürebilir...")

            val intent = packageManager.getLaunchIntentForPackage("com.termux")
            if (intent != null) {
                startActivity(intent)
            } else {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.termux")))
            }
        } catch (e: Exception) {
            logToApp(">>> HATA: Uygulama açılırken bir sorun oluştu.")
        }
    }

    private fun logToApp(message: String) {
        CoroutineScope(Dispatchers.Main).launch {
            appLogs.add(message)
            if (appLogs.size > 500) {
                appLogs.removeAt(0)
            }
        }
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            intent.data = Uri.parse("package:${packageName}")
            startActivity(intent)
        }
    }

    private fun createServerFolders() {
        try {
            val root = File(Environment.getExternalStorageDirectory(), "AServer/servers")
            if (!root.exists()) root.mkdirs()
            val backupsRoot = File(Environment.getExternalStorageDirectory(), "AServer/backups")
            if (!backupsRoot.exists()) backupsRoot.mkdirs()
        } catch (e: Exception) {}
    }
}

// ------------------- AYARLAR EKRANI -------------------
@Composable
fun SettingsScreen(currentLanguage: String, onLanguageChange: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxSize().background(OledBlack).padding(16.dp)) {
        Text(stringResource(R.string.set_title), fontSize = 28.sp, fontWeight = FontWeight.Black, color = Color.White)
        Spacer(Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = GlassSurface),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, GlassBorder)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Language, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.set_lang), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(16.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(
                        onClick = { onLanguageChange("tr") },
                        modifier = Modifier.weight(1f).height(50.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = if (currentLanguage == "tr") NeonCyan else Color(0xFF1A1A1A)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Türkçe", color = if (currentLanguage == "tr") Color.Black else Color.White, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = { onLanguageChange("en") },
                        modifier = Modifier.weight(1f).height(50.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = if (currentLanguage == "en") NeonCyan else Color(0xFF1A1A1A)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("English", color = if (currentLanguage == "en") Color.Black else Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun OnboardingGuideScreen(onFinish: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().background(OledBlack), contentAlignment = Alignment.Center) {
        Card(
            modifier = Modifier.fillMaxWidth(0.9f),
            colors = CardDefaults.cardColors(containerColor = GlassSurface),
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.RocketLaunch, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(64.dp))
                Spacer(Modifier.height(16.dp))
                Text("AServer'a Hoş Geldin!", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black)
                Text("Kendi sunucunu kurmak sandığından çok daha kolay. Sadece 3 adımda başlıyoruz:", color = Color.Gray, fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(vertical = 8.dp))

                Divider(color = GlassBorder, modifier = Modifier.padding(vertical = 8.dp))

                GuideStep(icon = Icons.Rounded.AddBox, title = "1. Profil Oluştur", desc = "'Yeni' sekmesinden sunucu özelliklerini seç ve oluştur.")
                GuideStep(icon = Icons.Rounded.Terminal, title = "2. Termux'u Aç", desc = "Ekrana gelen butona tıkla, açılan siyah ekrana kodu yapıştır ve Enter'a bas.")
                GuideStep(icon = Icons.Rounded.SportsEsports, title = "3. Yönetmeye Başla", desc = "Siyah ekran arkada çalışırken buraya dön ve 'Konsol' sekmesinden sunucunu yönet!")

                Spacer(Modifier.height(24.dp))
                Button(onClick = onFinish, modifier = Modifier.fillMaxWidth().height(50.dp), colors = ButtonDefaults.buttonColors(containerColor = NeonCyan), shape = RoundedCornerShape(12.dp)) {
                    Text("ANLADIM, BAŞLA!", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
        }
    }
}

@Composable
fun GuideStep(icon: ImageVector, title: String, desc: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(32.dp))
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(desc, color = Color.LightGray, fontSize = 12.sp)
        }
    }
}

@Composable
fun SplashScreen(onTimeout: () -> Unit) {
    LaunchedEffect(Unit) {
        delay(1800)
        onTimeout()
    }
    Box(modifier = Modifier.fillMaxSize().background(OledBlack), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(imageVector = Icons.Rounded.Storage, contentDescription = "Logo", tint = NeonCyan, modifier = Modifier.size(100.dp))
            Spacer(modifier = Modifier.height(16.dp))
            Text("A-SERVER", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp)
            Text("Cyber-Node Terminal", color = NeonCyan.copy(alpha = 0.7f), fontSize = 14.sp)
            Spacer(modifier = Modifier.height(40.dp))
            CircularProgressIndicator(color = NeonCyan, strokeWidth = 3.dp)
        }
    }
}

fun readLastLines(file: File, bytesToRead: Long = 8192): String {
    if (!file.exists() || file.length() == 0L) return ""
    return try {
        java.io.RandomAccessFile(file, "r").use { raf ->
            val len = raf.length()
            val startPtr = if (len > bytesToRead) len - bytesToRead else 0L
            raf.seek(startPtr)
            val bytes = ByteArray((len - startPtr).toInt())
            raf.readFully(bytes)
            String(bytes, Charsets.UTF_8).trim()
        }
    } catch (e: Exception) { "Log okuma hatası!" }
}

fun zipFolder(sourceFile: File, zipFile: File) {
    ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
        sourceFile.walkTopDown().forEach { file ->
            val zipFileName = file.absolutePath.removePrefix(sourceFile.absolutePath).removePrefix("/")
            if (zipFileName.isNotEmpty()) {
                val entry = ZipEntry(zipFileName + (if (file.isDirectory) "/" else ""))
                zos.putNextEntry(entry)
                if (file.isFile) { file.inputStream().use { it.copyTo(zos) } }
            }
        }
    }
}

fun unzipFolder(zipFile: File, targetDir: File) {
    java.util.zip.ZipInputStream(java.io.BufferedInputStream(java.io.FileInputStream(zipFile))).use { zis ->
        var entry = zis.nextEntry
        while (entry != null) {
            val newFile = File(targetDir, entry.name)
            if (entry.isDirectory) {
                newFile.mkdirs()
            } else {
                newFile.parentFile?.mkdirs()
                java.io.FileOutputStream(newFile).use { fos ->
                    zis.copyTo(fos)
                }
            }
            zis.closeEntry()
            entry = zis.nextEntry
        }
    }
}

// ------------------- DOSYA YÖNETİCİSİ VE AKILLI MAĞAZALAR -------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileManagerScreen(baseDir: File) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var currentDir by remember { mutableStateOf(baseDir) }
    var files by remember { mutableStateOf(currentDir.listFiles()?.toList()?.sortedWith(compareBy({ !it.isDirectory }, { it.name })) ?: emptyList()) }
    var fileToEdit by remember { mutableStateOf<File?>(null) }
    var fileContent by remember { mutableStateOf("") }
    var isUploading by remember { mutableStateOf(false) }
    var isModpackInstalling by remember { mutableStateOf(false) }
    var modpackProgress by remember { mutableStateOf(0f) }
    var modpackStatusText by remember { mutableStateOf("") }
    var showModpackDialog by remember { mutableStateOf(false) }

    var showPluginStore by remember { mutableStateOf(false) }
    var showModStore by remember { mutableStateOf(false) }
    var downloadingItem by remember { mutableStateOf<String?>(null) }

    var showNewFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }

    fun refreshFiles() { files = currentDir.listFiles()?.toList()?.sortedWith(compareBy({ !it.isDirectory }, { it.name })) ?: emptyList() }

    val filePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            isUploading = true
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    var fileName = "indirilen_dosya_${System.currentTimeMillis()}.jar"
                    try {
                        if (uri.scheme == "content") {
                            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                                if (cursor.moveToFirst()) {
                                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                                    if (index != -1) {
                                        val name = cursor.getString(index)
                                        if (!name.isNullOrBlank()) fileName = name
                                    }
                                }
                            }
                        } else if (uri.scheme == "file") {
                            val name = File(uri.path ?: "").name
                            if (name.isNotBlank()) fileName = name
                        }
                    } catch (e: Exception) { }
                    val destFile = File(currentDir, fileName)
                    val inputStream = context.contentResolver.openInputStream(uri)
                    if (inputStream != null) {
                        inputStream.use { input -> destFile.outputStream().use { output -> input.copyTo(output) } }
                        launch(Dispatchers.Main) {
                            refreshFiles()
                            isUploading = false
                            Toast.makeText(context, "$fileName eklendi!", Toast.LENGTH_SHORT).show()
                        }
                    } else throw Exception("Dosya verisi okunamadı")
                } catch (e: Exception) {
                    launch(Dispatchers.Main) {
                        isUploading = false
                        Toast.makeText(context, "Yükleme hatası: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    val modpackPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            val targetServerDir = currentDir.parentFile ?: currentDir
            isModpackInstalling = true
            modpackProgress = 0f
            modpackStatusText = "Modpack hazırlanıyor..."
            showModpackDialog = true

            scope.launch {
                try {
                    com.example.aserver.utils.ModpackEngine.processMrPack(
                        context = context,
                        fileUri = uri,
                        targetServerDir = targetServerDir
                    ) { progress, status ->
                        modpackProgress = progress
                        modpackStatusText = status
                    }

                    modpackProgress = 1f
                    modpackStatusText = "Modpack kurulumu tamamlandı."
                } catch (e: Exception) {
                    modpackStatusText = "Hata: ${e.localizedMessage ?: "Modpack kurulamadı."}"
                } finally {
                    isModpackInstalling = false
                    refreshFiles()
                }
            }
        }
    }

    if (showNewFolderDialog) {
        AlertDialog(
            onDismissRequest = { showNewFolderDialog = false },
            containerColor = GlassSurface,
            title = { Text(stringResource(R.string.file_new_folder), color = Color.White, fontWeight = FontWeight.Bold) },
            text = { OutlinedTextField(value = newFolderName, onValueChange = { newFolderName = it }, label = { Text(stringResource(R.string.file_folder_name)) }, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = NeonCyan)) },
            confirmButton = {
                Button(onClick = {
                    if (newFolderName.isNotBlank()) { File(currentDir, newFolderName).mkdirs(); refreshFiles(); showNewFolderDialog = false; newFolderName = "" }
                }, colors = ButtonDefaults.buttonColors(containerColor = NeonCyan)) { Text(stringResource(R.string.file_btn_create), color = Color.Black, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { showNewFolderDialog = false }) { Text(stringResource(R.string.file_btn_cancel), color = Color.Gray) } }
        )
    }

    if (showModpackDialog) {
        AlertDialog(
            onDismissRequest = { if (!isModpackInstalling) showModpackDialog = false },
            containerColor = GlassSurface,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Archive, contentDescription = null, tint = NeonCyan)
                    Spacer(Modifier.width(8.dp))
                    Text("Modpack Kurulumu", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column {
                    LinearProgressIndicator(
                        progress = { modpackProgress },
                        modifier = Modifier.fillMaxWidth(),
                        color = NeonCyan,
                        trackColor = GlassBorder
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = modpackStatusText.ifBlank { "Kurulum bekleniyor..." },
                        color = Color.White,
                        fontSize = 14.sp
                    )
                }
            },
            confirmButton = {
                if (!isModpackInstalling) {
                    TextButton(onClick = { showModpackDialog = false }) {
                        Text("Tamam", color = NeonCyan, fontWeight = FontWeight.Bold)
                    }
                }
            }
        )
    }

    // SPIGET
    if (showPluginStore) {
        var searchQuery by remember { mutableStateOf("") }
        var searchResults by remember { mutableStateOf<List<SpigetPlugin>>(emptyList()) }
        var isSearching by remember { mutableStateOf(false) }

        fun searchSpiget() {
            if (searchQuery.isBlank()) return
            isSearching = true
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val encodedQuery = java.net.URLEncoder.encode(searchQuery, "UTF-8")
                    val url = URL("https://api.spiget.org/v2/search/resources/$encodedQuery?field=name&size=20")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.setRequestProperty("User-Agent", "AServerApp")

                    if (conn.responseCode == 200) {
                        val response = conn.inputStream.bufferedReader().readText()
                        val jsonArray = org.json.JSONArray(response)
                        val results = mutableListOf<SpigetPlugin>()
                        for (i in 0 until jsonArray.length()) {
                            val obj = jsonArray.getJSONObject(i)
                            results.add(
                                SpigetPlugin(
                                    id = obj.getInt("id"),
                                    name = obj.getString("name"),
                                    tag = obj.optString("tag", "Açıklama bulunmuyor")
                                )
                            )
                        }
                        withContext(Dispatchers.Main) {
                            searchResults = results
                            isSearching = false
                        }
                    } else {
                        withContext(Dispatchers.Main) { isSearching = false }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { isSearching = false }
                }
            }
        }

        AlertDialog(
            onDismissRequest = { if (downloadingItem == null) showPluginStore = false },
            containerColor = GlassSurface,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Storefront, contentDescription = null, tint = NeonCyan)
                    Spacer(Modifier.width(8.dp))
                    Text("Spiget " + stringResource(R.string.file_store_title), color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Eklenti ara...", color = Color.Gray, fontSize = 14.sp) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = NeonCyan, unfocusedBorderColor = Color.DarkGray),
                            textStyle = androidx.compose.ui.text.TextStyle(color = Color.White)
                        )
                        Spacer(Modifier.width(8.dp))
                        IconButton(
                            onClick = { searchSpiget() },
                            modifier = Modifier.background(NeonCyan, RoundedCornerShape(8.dp)).size(50.dp)
                        ) {
                            Icon(Icons.Rounded.Search, contentDescription = "Ara", tint = Color.Black)
                        }
                    }
                    Spacer(Modifier.height(12.dp))

                    if (isSearching) {
                        Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = NeonCyan)
                        }
                    } else if (searchResults.isEmpty()) {
                        Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                            Text("Arama yapın veya farklı bir kelime deneyin.", color = Color.Gray, fontSize = 12.sp)
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 350.dp)) {
                            items(searchResults) { plugin ->
                                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)), border = BorderStroke(1.dp, GlassBorder)) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text(plugin.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                        Text(plugin.tag, color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp))
                                        Button(
                                            onClick = {
                                                downloadingItem = plugin.name
                                                CoroutineScope(Dispatchers.IO).launch {
                                                    try {
                                                        val downloadUrl = "https://api.spiget.org/v2/resources/${plugin.id}/download"
                                                        var connection = URL(downloadUrl).openConnection() as HttpURLConnection
                                                        connection.requestMethod = "GET"
                                                        connection.setRequestProperty("User-Agent", "AServerApp")
                                                        connection.instanceFollowRedirects = false
                                                        connection.connect()

                                                        var status = connection.responseCode
                                                        var redirects = 0
                                                        var currentUrl = downloadUrl

                                                        while (status in 300..308 && redirects < 5) {
                                                            currentUrl = connection.getHeaderField("Location")
                                                            connection = URL(currentUrl).openConnection() as HttpURLConnection
                                                            connection.requestMethod = "GET"
                                                            connection.setRequestProperty("User-Agent", "AServerApp")
                                                            connection.instanceFollowRedirects = false
                                                            connection.connect()
                                                            status = connection.responseCode
                                                            redirects++
                                                        }

                                                        if (status == HttpURLConnection.HTTP_OK || status == 201) {
                                                            val fileName = "${plugin.name.replace(Regex("[^a-zA-Z0-9.-]"), "_")}.jar"
                                                            val destFile = File(currentDir, fileName)
                                                            connection.inputStream.use { inp -> destFile.outputStream().use { out -> inp.copyTo(out) } }
                                                            launch(Dispatchers.Main) {
                                                                refreshFiles()
                                                                downloadingItem = null
                                                                Toast.makeText(context, "${plugin.name} indirildi!", Toast.LENGTH_SHORT).show()
                                                            }
                                                        } else {
                                                            throw Exception("Harici link: Bu eklenti doğrudan indirilemiyor.")
                                                        }
                                                    } catch (e: Exception) {
                                                        launch(Dispatchers.Main) {
                                                            downloadingItem = null
                                                            Toast.makeText(context, "Hata: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                                                        }
                                                    }
                                                }
                                            },
                                            enabled = downloadingItem == null,
                                            modifier = Modifier.align(Alignment.End).padding(top = 4.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = NeonCyan)
                                        ) {
                                            if (downloadingItem == plugin.name) {
                                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.Black, strokeWidth = 2.dp)
                                            } else {
                                                Icon(Icons.Rounded.Download, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                                                Spacer(Modifier.width(4.dp))
                                                Text(stringResource(R.string.file_btn_download), color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showPluginStore = false }, enabled = downloadingItem == null) { Text(stringResource(R.string.file_btn_close), color = Color.Gray) } }
        )
    }

    // MODRINTH
    if (showModStore) {
        var searchModQuery by remember { mutableStateOf("") }
        var searchModResults by remember { mutableStateOf<List<ModrinthMod>>(emptyList()) }
        var isModSearching by remember { mutableStateOf(false) }

        fun searchModrinth() {
            if (searchModQuery.isBlank()) return
            isModSearching = true
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    var mcVersion = "1.21.11"
                    try {
                        val versionFile = File(currentDir.parentFile, "mc_version.txt")
                        if (versionFile.exists()) {
                            mcVersion = versionFile.readText().trim()
                        }
                    } catch (e: Exception) {}

                    val encodedQuery = java.net.URLEncoder.encode(searchModQuery, "UTF-8")
                    val facetString = "%5B%5B%22categories%3Afabric%22%5D%2C%5B%22project_type%3Amod%22%5D%2C%5B%22versions%3A$mcVersion%22%5D%5D"
                    val url = URL("https://api.modrinth.com/v2/search?query=$encodedQuery&facets=$facetString")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.setRequestProperty("User-Agent", "AServerApp")

                    if (conn.responseCode == 200) {
                        val response = conn.inputStream.bufferedReader().readText()
                        val jsonObject = org.json.JSONObject(response)
                        val hitsArray = jsonObject.getJSONArray("hits")
                        val results = mutableListOf<ModrinthMod>()
                        for (i in 0 until hitsArray.length()) {
                            val obj = hitsArray.getJSONObject(i)
                            results.add(
                                ModrinthMod(
                                    id = obj.getString("project_id"),
                                    title = obj.getString("title"),
                                    description = obj.getString("description")
                                )
                            )
                        }
                        withContext(Dispatchers.Main) {
                            searchModResults = results
                            isModSearching = false
                        }
                    } else {
                        withContext(Dispatchers.Main) { isModSearching = false }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { isModSearching = false }
                }
            }
        }

        AlertDialog(
            onDismissRequest = { if (downloadingItem == null) showModStore = false },
            containerColor = GlassSurface,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Extension, contentDescription = null, tint = NeonCyan)
                    Spacer(Modifier.width(8.dp))
                    Text("Modrinth Mod Mağazası", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = searchModQuery,
                            onValueChange = { searchModQuery = it },
                            placeholder = { Text("Mod ara...", color = Color.Gray, fontSize = 14.sp) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = NeonCyan, unfocusedBorderColor = Color.DarkGray),
                            textStyle = androidx.compose.ui.text.TextStyle(color = Color.White)
                        )
                        Spacer(Modifier.width(8.dp))
                        IconButton(
                            onClick = { searchModrinth() },
                            modifier = Modifier.background(NeonCyan, RoundedCornerShape(8.dp)).size(50.dp)
                        ) {
                            Icon(Icons.Rounded.Search, contentDescription = "Ara", tint = Color.Black)
                        }
                    }
                    Spacer(Modifier.height(12.dp))

                    if (isModSearching) {
                        Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = NeonCyan)
                        }
                    } else if (searchModResults.isEmpty()) {
                        Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                            Text("Arama yapın veya farklı bir kelime deneyin.", color = Color.Gray, fontSize = 12.sp)
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 350.dp)) {
                            items(searchModResults) { mod ->
                                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)), border = BorderStroke(1.dp, GlassBorder)) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text(mod.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                        Text(mod.description, color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp))
                                        Button(
                                            onClick = {
                                                downloadingItem = mod.title
                                                CoroutineScope(Dispatchers.IO).launch {
                                                    try {
                                                        var mcVersion = "1.21.11"
                                                        try {
                                                            val versionFile = File(currentDir.parentFile, "mc_version.txt")
                                                            if (versionFile.exists()) {
                                                                mcVersion = versionFile.readText().trim()
                                                            }
                                                        } catch (e: Exception) {}

                                                        // YENİ SİSTEM: Akıllı Motoru Ateşliyoruz
                                                        val downloadedSet = mutableSetOf<String>()
                                                        downloadModWithDependencies(mod.id, mcVersion, currentDir, downloadedSet)

                                                        withContext(Dispatchers.Main) {
                                                            refreshFiles()
                                                            downloadingItem = null
                                                            // Başarı mesajını zincirleme operasyona uygun güncelledik
                                                            Toast.makeText(context, "${mod.title} ve gerekli tüm alt modları kuruldu!", Toast.LENGTH_LONG).show()
                                                        }
                                                    } catch (e: Exception) {
                                                        withContext(Dispatchers.Main) {
                                                            downloadingItem = null
                                                            Toast.makeText(context, "Hata: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                                                        }
                                                    }
                                                }
                                            },
                                            enabled = downloadingItem == null,
                                            modifier = Modifier.align(Alignment.End).padding(top = 4.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = NeonCyan)
                                        ) {
                                            if (downloadingItem == mod.title) {
                                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.Black, strokeWidth = 2.dp)
                                            } else {
                                                Icon(Icons.Rounded.Download, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                                                Spacer(Modifier.width(4.dp))
                                                Text("MODU İNDİR", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showModStore = false }, enabled = downloadingItem == null) { Text(stringResource(R.string.file_btn_close), color = Color.Gray) } }
        )
    }

    if (fileToEdit != null) {
        Column(modifier = Modifier.fillMaxSize().background(OledBlack).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { fileToEdit = null }) { Icon(Icons.Rounded.ArrowBack, contentDescription = "Geri", tint = Color.White) }
                    Text(fileToEdit!!.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
                Button(onClick = { fileToEdit!!.writeText(fileContent); fileToEdit = null }, colors = ButtonDefaults.buttonColors(containerColor = NeonCyan)) {
                    Text(stringResource(R.string.file_btn_save), color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = fileContent, onValueChange = { fileContent = it }, modifier = Modifier.fillMaxSize(),
                textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = GlassSurface, focusedContainerColor = GlassSurface, unfocusedBorderColor = Color.Transparent, focusedBorderColor = NeonCyan)
            )
        }
    } else {
        Column(modifier = Modifier.fillMaxSize().background(OledBlack).padding(16.dp)) {
            Text(stringResource(R.string.file_title), fontSize = 28.sp, fontWeight = FontWeight.Black, color = Color.White)
            Spacer(Modifier.height(16.dp))
            Card(colors = CardDefaults.cardColors(containerColor = GlassSurface), shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, GlassBorder), modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (currentDir.absolutePath != baseDir.absolutePath) {
                            IconButton(onClick = { currentDir = currentDir.parentFile ?: baseDir; refreshFiles() }) { Icon(Icons.Rounded.ArrowBack, contentDescription = "Geri", tint = Color.White) }
                        }
                        Text(currentDir.name, color = NeonCyan, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp))
                    }
                    Row {
                        IconButton(onClick = { showNewFolderDialog = true }) { Icon(Icons.Rounded.CreateNewFolder, contentDescription = "Yeni Klasör", tint = NeonCyan) }

                        if (currentDir.name.equals("plugins", ignoreCase = true)) {
                            IconButton(onClick = { showPluginStore = true }) { Icon(Icons.Rounded.Storefront, contentDescription = "Eklenti Mağazası", tint = NeonCyan) }
                        } else if (currentDir.name.equals("mods", ignoreCase = true)) {
                            IconButton(onClick = { showModStore = true }) { Icon(Icons.Rounded.Extension, contentDescription = "Mod Mağazası", tint = NeonCyan) }
                        }

                        if (currentDir.name.equals("mods", ignoreCase = true)) {
                            IconButton(onClick = { modpackPickerLauncher.launch("*/*") }) { Icon(Icons.Rounded.Archive, contentDescription = "Modpack Kur", tint = NeonCyan) }
                        }

                        if (isUploading) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp).padding(end = 8.dp), color = NeonCyan, strokeWidth = 2.dp)
                        } else {
                            IconButton(onClick = { filePickerLauncher.launch("*/*") }) { Icon(Icons.Rounded.CloudUpload, contentDescription = "Dosya Yükle", tint = NeonCyan) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(files) { file ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
                            if (file.isDirectory) { currentDir = file; refreshFiles() }
                            else if (file.name.endsWith(".properties") || file.name.endsWith(".json") || file.name.endsWith(".txt") || file.name.endsWith(".yml") || file.name.endsWith(".log")) {
                                fileContent = file.readText(); fileToEdit = file
                            }
                        }.padding(vertical = 12.dp, horizontal = 12.dp)
                    ) {
                        Icon(imageVector = if (file.isDirectory) Icons.Rounded.Folder else Icons.Rounded.InsertDriveFile, contentDescription = null, tint = if (file.isDirectory) NeonCyan else Color.LightGray, modifier = Modifier.size(32.dp))
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(file.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                            if (!file.isDirectory) Text("${file.length() / 1024} KB", color = Color.Gray, fontSize = 12.sp)
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = {
                            if(file.isDirectory) file.deleteRecursively() else file.delete()
                            refreshFiles()
                        }) {
                            Icon(Icons.Rounded.Delete, contentDescription = "Sil", tint = Color(0xFFD32F2F))
                        }
                    }
                    Divider(color = GlassBorder, thickness = 1.dp)
                }
            }
        }
    }
}

// ------------------- SUNUCULARIM (SİBER KABİN MİMARİSİ) -------------------
@Composable
fun MyServersScreen(baseDir: File, activeServerName: String, onPlay: (String, String) -> Unit, onGoToConsole: () -> Unit) {
    val context = LocalContext.current
    var serverInfos by remember { mutableStateOf<List<ServerDisplayInfo>>(emptyList()) }
    var refreshTrigger by remember { mutableIntStateOf(0) }

    var backingUpFolder by remember { mutableStateOf<String?>(null) }
    var editingServerProps by remember { mutableStateOf<String?>(null) }
    var restoringServer by remember { mutableStateOf<String?>(null) }
    var restoringFolderProgress by remember { mutableStateOf<String?>(null) }

    fun refreshList() { refreshTrigger++ }

    LaunchedEffect(baseDir, refreshTrigger) {
        withContext(Dispatchers.IO) {
            val folders = baseDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
            val infos = folders.map { folder ->
                val javaFile = File(folder, "java_version.txt")
                val javaVer = if (javaFile.exists()) javaFile.readText().trim() else "17"
                val playitFile = File(folder, "playit_enabled.txt")
                val hasPlayit = if (playitFile.exists()) playitFile.readText().trim() == "true" else false
                ServerDisplayInfo(folder, folder.name, javaVer, hasPlayit)
            }
            serverInfos = infos
        }
    }

    if (editingServerProps != null) {
        ServerPropertiesEditorDialog(
            serverName = editingServerProps!!,
            onDismiss = { editingServerProps = null }
        )
    }

    if (restoringServer != null) {
        val targetServerName = restoringServer!!
        RestoreBackupDialog(
            serverName = targetServerName,
            onDismiss = { restoringServer = null },
            onRestore = { zipFile ->
                restoringFolderProgress = targetServerName
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val serverDir = File(baseDir, targetServerName)
                        serverDir.deleteRecursively()
                        serverDir.mkdirs()
                        unzipFolder(zipFile, serverDir)
                        withContext(Dispatchers.Main) {
                            restoringFolderProgress = null
                            Toast.makeText(context, "Yedek başarıyla geri yüklendi!", Toast.LENGTH_SHORT).show()
                            refreshList()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            restoringFolderProgress = null
                            Toast.makeText(context, "Hata: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(OledBlack).padding(16.dp)) {
        Text(stringResource(R.string.srv_title), fontSize = 28.sp, fontWeight = FontWeight.Black, color = Color.White)
        Text("VERİ MERKEZİ (SERVER RACK)", color = NeonCyan.copy(alpha=0.7f), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        Spacer(Modifier.height(16.dp))

        if (serverInfos.isEmpty()) {
            Text(stringResource(R.string.srv_empty), color = Color.Gray)
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(serverInfos) { info ->
                    val isThisServerActive = activeServerName == info.name
                    val isAnotherServerActive = activeServerName.isNotEmpty() && activeServerName != info.name

                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        colors = CardDefaults.cardColors(containerColor = GlassSurface),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, if(isThisServerActive) NeonCyan else GlassBorder)
                    ) {
                        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                            Box(modifier = Modifier.fillMaxHeight().width(6.dp).background(if(isThisServerActive) NeonCyan else Color.DarkGray))

                            Column(modifier = Modifier.padding(16.dp).weight(1f)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(info.name, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Rounded.Code, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(14.dp))
                                            Text(" Java ${info.javaVer}  ", color = Color.Gray, fontSize = 12.sp)
                                            Icon(Icons.Rounded.Public, contentDescription = null, tint = if(info.hasPlayit) NeonCyan else Color.Gray, modifier = Modifier.size(14.dp))
                                            Text(if (info.hasPlayit) " ${stringResource(R.string.srv_net_open)}" else " ${stringResource(R.string.srv_net_closed)}", color = Color.Gray, fontSize = 12.sp)
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(12.dp))
                                Divider(color = GlassBorder, thickness = 1.dp)
                                Spacer(modifier = Modifier.height(12.dp))

                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(onClick = { editingServerProps = info.name }, modifier = Modifier.size(36.dp)) {
                                            Icon(Icons.Rounded.Settings, null, tint = Color.LightGray, modifier = Modifier.size(22.dp))
                                        }
                                        Spacer(Modifier.width(4.dp))

                                        if (restoringFolderProgress == info.name) {
                                            CircularProgressIndicator(modifier = Modifier.size(20.dp).padding(2.dp), color = NeonCyan, strokeWidth = 2.dp)
                                        } else {
                                            IconButton(onClick = {
                                                if (activeServerName.isNotEmpty()) {
                                                    Toast.makeText(context, "Lütfen önce açık olan sunucuyu durdurun!", Toast.LENGTH_SHORT).show()
                                                } else {
                                                    restoringServer = info.name
                                                }
                                            }, modifier = Modifier.size(36.dp)) {
                                                Icon(Icons.Rounded.Restore, contentDescription = "Geri Yükle", tint = NeonCyan, modifier = Modifier.size(22.dp))
                                            }
                                        }
                                        Spacer(Modifier.width(4.dp))

                                        if (backingUpFolder == info.name) {
                                            CircularProgressIndicator(modifier = Modifier.size(20.dp).padding(2.dp), color = NeonCyan, strokeWidth = 2.dp)
                                        } else {
                                            IconButton(onClick = {
                                                if (activeServerName.isNotEmpty()) {
                                                    Toast.makeText(context, "Yedek almadan önce sunucuyu durdurun!", Toast.LENGTH_SHORT).show()
                                                } else {
                                                    backingUpFolder = info.name
                                                    CoroutineScope(Dispatchers.IO).launch {
                                                        try {
                                                            val backupsDir = File(Environment.getExternalStorageDirectory(), "AServer/backups")
                                                            val zipFile = File(backupsDir, "${info.name}_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.zip")
                                                            zipFolder(info.folder, zipFile)
                                                            launch(Dispatchers.Main) { Toast.makeText(context, "Yedek Alındı! Konum: AServer/backups", Toast.LENGTH_LONG).show(); backingUpFolder = null }
                                                        } catch (e: Exception) { launch(Dispatchers.Main) { Toast.makeText(context, "Yedekleme Hatası: ${e.localizedMessage}", Toast.LENGTH_LONG).show(); backingUpFolder = null } }
                                                    }
                                                }
                                            }, modifier = Modifier.size(36.dp)) { Icon(Icons.Rounded.SaveAs, contentDescription = "Yedekle", tint = NeonCyan, modifier = Modifier.size(22.dp)) }
                                        }
                                        Spacer(Modifier.width(4.dp))
                                        IconButton(onClick = { info.folder.deleteRecursively(); refreshList() }, modifier = Modifier.size(36.dp)) {
                                            Icon(Icons.Rounded.Delete, contentDescription = "Sil", tint = Color(0xFFE53935), modifier = Modifier.size(22.dp))
                                        }
                                    }

                                    Button(
                                        onClick = {
                                            if (isThisServerActive) {
                                                onGoToConsole()
                                            } else {
                                                onPlay(info.name, info.javaVer)
                                            }
                                        },
                                        enabled = !isAnotherServerActive,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (isThisServerActive) Color.Transparent else NeonCyan,
                                            disabledContainerColor = Color(0xFF1A1A1A)
                                        ),
                                        shape = RoundedCornerShape(12.dp),
                                        border = if(isThisServerActive) BorderStroke(1.dp, NeonCyan) else null,
                                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                                    ) {
                                        if (isThisServerActive) {
                                            Icon(Icons.Rounded.Terminal, contentDescription = null, tint = NeonCyan)
                                            Spacer(Modifier.width(4.dp))
                                            Text("KONSOL", color = NeonCyan, fontWeight = FontWeight.Bold)
                                        } else {
                                            Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = if (isAnotherServerActive) Color.DarkGray else Color.Black)
                                            Spacer(Modifier.width(4.dp))
                                            Text(stringResource(R.string.srv_btn_start), color = if (isAnotherServerActive) Color.DarkGray else Color.Black, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ------------------- KONSOL VE ZAMANLAYICI EKRANI -------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RealConsoleScreen(
    activeServerName: String,
    appLogs: List<String>,
    isOnline: Boolean,
    scheduledTasks: List<ScheduledTask>,
    onAddTask: (String, String, Int) -> Unit,
    onToggleTask: (String, Boolean) -> Unit,
    onRemoveTask: (String) -> Unit,
    onSendCommand: (String) -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    var realLogs by remember { mutableStateOf("...") }
    var playitLink by remember { mutableStateOf<String?>(null) }
    var playitIp by remember { mutableStateOf<String?>(null) }
    var commandInput by remember { mutableStateOf("") }

    var showTaskDialog by remember { mutableStateOf(false) }

    val macroCommands = listOf(
        "Sabah Yap" to "time set day",
        "Gece Yap" to "time set night",
        "Hava Açık" to "weather clear",
        "Yaratıcı Mod" to "gamemode creative @a",
        "Hayatta Kalma" to "gamemode survival @a",
        "Mobları Sil" to "kill @e[type=!player]"
    )

    LaunchedEffect(activeServerName) {
        while (true) {
            if (activeServerName.isNotEmpty()) {
                val serverDir = File(Environment.getExternalStorageDirectory(), "AServer/servers/$activeServerName")
                val playitLogFile = File(serverDir, "playit_log.txt")
                if (playitLogFile.exists()) {
                    val rawText = playitLogFile.readText()
                    val cleanText = rawText.replace(Regex("\u001B\\[[;\\d]*[a-zA-Z]"), "")
                    val ipRegex = Regex("([a-zA-Z0-9.-]+(?:joinmc\\.link|playit\\.gg))")
                    val matches = ipRegex.findAll(cleanText)
                    for (m in matches) {
                        val found = m.value
                        if (found != "playit.gg" && found != "api.playit.gg" && !found.contains("playit.gg/claim")) { playitIp = found; break }
                    }
                    if (playitIp == null) {
                        val match = Regex("https://playit\\.gg/claim/[a-zA-Z0-9]+").find(cleanText)
                        if (match != null && playitLink != match.value) playitLink = match.value
                    }
                }
                val logFile = File(serverDir, "logs/latest.log")
                if (logFile.exists()) {
                    val text = logFile.readText()
                    if (text.isNotEmpty() && text != realLogs) realLogs = text
                }
            }
            delay(1500)
        }
    }

    LaunchedEffect(realLogs) { scrollState.animateScrollTo(scrollState.maxValue) }

    if (showTaskDialog) {
        var taskType by remember { mutableStateOf("Duyuru") }
        var taskPayload by remember { mutableStateOf("") }
        var taskInterval by remember { mutableFloatStateOf(10f) }

        AlertDialog(
            onDismissRequest = { showTaskDialog = false },
            containerColor = GlassSurface,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Timer, contentDescription = null, tint = NeonCyan)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.cons_tasks), color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column {
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)), modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(stringResource(R.string.cons_new_task), color = NeonCyan, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Spacer(Modifier.height(8.dp))

                            CustomDropdownMenu(stringResource(R.string.cons_task_type), listOf("Duyuru", "Komut"), taskType, { taskType = it })
                            Spacer(Modifier.height(8.dp))

                            OutlinedTextField(
                                value = taskPayload,
                                onValueChange = { taskPayload = it },
                                label = { Text(if (taskType == "Duyuru") stringResource(R.string.cons_task_msg) else stringResource(R.string.cons_task_cmd)) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = NeonCyan, unfocusedBorderColor = Color.Gray),
                                textStyle = androidx.compose.ui.text.TextStyle(color = Color.White)
                            )
                            Spacer(Modifier.height(8.dp))

                            Text(stringResource(R.string.cons_task_interval, taskInterval.toInt()), color = Color.White, fontSize = 12.sp)
                            Slider(value = taskInterval, onValueChange = { taskInterval = it }, valueRange = 1f..60f, colors = SliderDefaults.colors(activeTrackColor = NeonCyan, thumbColor = Color.White))

                            Button(
                                onClick = {
                                    if (taskPayload.isNotBlank()) {
                                        onAddTask(taskType, taskPayload, taskInterval.toInt())
                                        taskPayload = ""
                                        Toast.makeText(context, "Eklendi!", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier.align(Alignment.End),
                                colors = ButtonDefaults.buttonColors(containerColor = NeonCyan)
                            ) { Text(stringResource(R.string.cons_btn_add), color = Color.Black, fontWeight = FontWeight.Bold) }
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                        items(scheduledTasks) { task ->
                            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A))) {
                                Row(modifier = Modifier.padding(8.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(task.type, color = NeonCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        Text(task.payload, color = Color.White, fontSize = 14.sp, maxLines = 1)
                                        Text(stringResource(R.string.cons_every_min, task.intervalMinutes), color = Color.Gray, fontSize = 10.sp)
                                    }
                                    Switch(checked = task.isRunning, onCheckedChange = { onToggleTask(task.id, it) }, colors = SwitchDefaults.colors(checkedThumbColor = NeonCyan, checkedTrackColor = NeonCyan.copy(alpha = 0.5f)))
                                    IconButton(onClick = { onRemoveTask(task.id) }) { Icon(Icons.Rounded.Delete, contentDescription = "Sil", tint = Color(0xFFE53935)) }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showTaskDialog = false }) { Text("Kapat", color = Color.Gray) } }
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(OledBlack).padding(12.dp)) {

        if (activeServerName.isNotEmpty()) {
            SystemStatusMonitor(isRunning = isOnline, activeServerName = activeServerName)
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (playitIp != null) {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1976D2)), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).clickable {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Sunucu IP", playitIp))
                Toast.makeText(context, "IP Adresi Kopyalandı!", Toast.LENGTH_SHORT).show()
            }, shape = RoundedCornerShape(12.dp)) {
                Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = null, tint = Color.White)
                    Spacer(Modifier.width(8.dp))
                    Text("IP: $playitIp", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
        }
        else if (playitLink != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = NeonCyan),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).clickable {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(playitLink))
                    context.startActivity(intent)
                },
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    Icon(Icons.Rounded.Link, contentDescription = null, tint = Color.Black)
                    Spacer(Modifier.width(8.dp))
                    Text("Playit Ağını Bağla (Tıkla)", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
        }

        if (activeServerName.isNotEmpty()) {
            Button(
                onClick = { if (isOnline) onSendCommand("stop") else onSendCommand("FORCE_CANCEL") },
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(if (isOnline) Icons.Rounded.PowerSettingsNew else Icons.Rounded.Cancel, contentDescription = null, tint = Color.White)
                Spacer(Modifier.width(8.dp))
                Text(if (isOnline) stringResource(R.string.cons_btn_stop) else "İPTAL ET / SIFIRLA", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Terminal, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (activeServerName.isEmpty()) stringResource(R.string.cons_sys_msg) else stringResource(R.string.cons_title, activeServerName), color = NeonCyan, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.weight(1f))

            if (activeServerName.isNotEmpty()) {
                IconButton(onClick = { showTaskDialog = true }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Rounded.Timer, contentDescription = "Otomasyon", tint = NeonCyan)
                }
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Rounded.Circle, contentDescription = null, tint = if (isOnline) Color(0xFF00E676) else Color.Red, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (isOnline) stringResource(R.string.cons_online) else stringResource(R.string.cons_waiting), color = if (isOnline) Color(0xFF00E676) else Color.Red, fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(8.dp))

        Box(modifier = Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF050505)).border(1.dp, GlassBorder, RoundedCornerShape(12.dp)).padding(8.dp).verticalScroll(scrollState)) {
            Column {
                if (activeServerName.isEmpty() || realLogs.lines().size < 3) {
                    appLogs.forEach { log ->
                        Text(text = parseAnsiToAnnotatedString(log), fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.padding(vertical = 2.dp))
                    }
                } else {
                    Text(text = parseAnsiToAnnotatedString(realLogs), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            }
        }

        if (activeServerName.isNotEmpty()) {

            if (isOnline) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(macroCommands) { macro ->
                        Button(
                            onClick = { onSendCommand(macro.second) },
                            colors = ButtonDefaults.buttonColors(containerColor = GlassSurface),
                            border = BorderStroke(1.dp, GlassBorder),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text(macro.first, color = Color.LightGray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = commandInput, onValueChange = { commandInput = it }, modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.cons_input_hint), color = Color.DarkGray, fontSize = 14.sp) },
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = NeonCyan, unfocusedBorderColor = GlassBorder, focusedContainerColor = GlassSurface, unfocusedContainerColor = GlassSurface),
                    textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontFamily = FontFamily.Monospace), singleLine = true, shape = RoundedCornerShape(12.dp), enabled = isOnline
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = { if (commandInput.isNotBlank()) { onSendCommand(commandInput); commandInput = "" } },
                    modifier = Modifier.background(if (isOnline) NeonCyan else Color.DarkGray, RoundedCornerShape(12.dp)).size(56.dp), enabled = isOnline
                ) { Icon(Icons.Rounded.Send, contentDescription = "Gönder", tint = Color.Black) }
            }
        }
    }
}

// ------------------- OYUNCULAR EKRANI -------------------
@Composable
fun PlayersScreen(activeServerName: String, isOnline: Boolean, onSendCommand: (String) -> Unit, fetchPlayers: suspend () -> List<String>) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }

    val tabActive = stringResource(R.string.plr_tab_active)
    val tabBanned = stringResource(R.string.plr_tab_banned)
    val tabWhitelist = stringResource(R.string.plr_tab_wl)
    val tabs = listOf(tabActive, tabBanned, tabWhitelist)

    var players by remember { mutableStateOf<List<String>>(emptyList()) }
    var bannedPlayers by remember { mutableStateOf<List<String>>(emptyList()) }
    var whitelistedPlayers by remember { mutableStateOf<List<String>>(emptyList()) }
    var whitelistEnabled by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var newWlPlayer by remember { mutableStateOf("") }

    LaunchedEffect(isOnline) {
        while (isOnline) {
            isLoading = true
            players = fetchPlayers()
            isLoading = false
            delay(5000)
        }
        if (!isOnline) players = emptyList()
    }

    LaunchedEffect(activeServerName, selectedTab) {
        if (activeServerName.isNotEmpty() && selectedTab != 0) {
            while (true) {
                withContext(Dispatchers.IO) {
                    val serverDir = File(Environment.getExternalStorageDirectory(), "AServer/servers/$activeServerName")
                    if (selectedTab == 1) {
                        val banFile = File(serverDir, "banned-players.json")
                        if (banFile.exists()) {
                            try {
                                val arr = org.json.JSONArray(banFile.readText())
                                val list = mutableListOf<String>()
                                for (i in 0 until arr.length()) list.add(arr.getJSONObject(i).getString("name"))
                                bannedPlayers = list
                            } catch (e: Exception) { }
                        } else bannedPlayers = emptyList()
                    }
                    if (selectedTab == 2) {
                        val wlFile = File(serverDir, "whitelist.json")
                        if (wlFile.exists()) {
                            try {
                                val arr = org.json.JSONArray(wlFile.readText())
                                val list = mutableListOf<String>()
                                for (i in 0 until arr.length()) list.add(arr.getJSONObject(i).getString("name"))
                                whitelistedPlayers = list
                            } catch (e: Exception) { }
                        } else whitelistedPlayers = emptyList()

                        val propsFile = File(serverDir, "server.properties")
                        if (propsFile.exists()) {
                            whitelistEnabled = propsFile.readText().contains("white-list=true")
                        }
                    }
                }
                delay(3000)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(OledBlack).padding(16.dp)) {
        Text(stringResource(R.string.plr_title), fontSize = 28.sp, fontWeight = FontWeight.Black, color = Color.White)
        Spacer(Modifier.height(8.dp))

        if (activeServerName.isEmpty()) {
            Card(modifier = Modifier.fillMaxWidth().padding(top = 16.dp), colors = CardDefaults.cardColors(containerColor = GlassSurface), border = BorderStroke(1.dp, GlassBorder), shape = RoundedCornerShape(12.dp)) {
                Column(modifier = Modifier.padding(24.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.CloudOff, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.plr_no_server), color = Color.White, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.plr_no_server_desc), color = Color.Gray, fontSize = 12.sp)
                }
            }
        } else {
            TabRow(selectedTabIndex = selectedTab, containerColor = Color.Transparent, contentColor = NeonCyan) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title, fontWeight = FontWeight.Bold, color = if (selectedTab == index) NeonCyan else Color.Gray) }
                    )
                }
            }
            Spacer(Modifier.height(16.dp))

            when (selectedTab) {
                0 -> {
                    if (!isOnline) {
                        Text(stringResource(R.string.plr_offline_msg), color = Color.Gray, fontSize = 12.sp)
                    } else if (players.isEmpty() && !isLoading) {
                        Text(stringResource(R.string.plr_nobody), color = Color.Gray, fontSize = 12.sp)
                    } else {
                        Text(stringResource(R.string.plr_online_count, players.size), color = NeonCyan, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Spacer(Modifier.height(8.dp))
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(players) { playerName ->
                                PlayerCard(playerName, Icons.Rounded.Person, NeonCyan) {
                                    IconButton(onClick = { onSendCommand("op $playerName") }, modifier = Modifier.background(Color(0xFF388E3C), RoundedCornerShape(8.dp)).size(36.dp)) {
                                        Icon(Icons.Rounded.Star, contentDescription = "OP", tint = Color.White, modifier = Modifier.size(18.dp))
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    IconButton(onClick = { onSendCommand("kick $playerName AServer panelinden atıldınız.") }, modifier = Modifier.background(Color(0xFFF57C00), RoundedCornerShape(8.dp)).size(36.dp)) {
                                        Icon(Icons.Rounded.ExitToApp, contentDescription = "Kick", tint = Color.White, modifier = Modifier.size(18.dp))
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    IconButton(onClick = { onSendCommand("ban $playerName Kuralları ihlal ettiniz.") }, modifier = Modifier.background(Color(0xFFD32F2F), RoundedCornerShape(8.dp)).size(36.dp)) {
                                        Icon(Icons.Rounded.Block, contentDescription = "Ban", tint = Color.White, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }
                }
                1 -> {
                    Text(stringResource(R.string.plr_banned_count, bannedPlayers.size), color = Color(0xFFD32F2F), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    if (bannedPlayers.isEmpty()) {
                        Text(stringResource(R.string.plr_ban_empty), color = Color.Gray, fontSize = 12.sp)
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(bannedPlayers) { playerName ->
                                PlayerCard(playerName, Icons.Rounded.Block, Color(0xFFD32F2F)) {
                                    Button(onClick = {
                                        if (isOnline) { onSendCommand("pardon $playerName") }
                                    }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2)), shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                                        Text(stringResource(R.string.plr_btn_pardon), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
                2 -> {
                    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text(stringResource(R.string.plr_wl_shield), color = Color.White, fontWeight = FontWeight.Bold)
                            Text(stringResource(R.string.plr_wl_desc), color = Color.Gray, fontSize = 10.sp)
                        }
                        Switch(checked = whitelistEnabled, onCheckedChange = {
                            whitelistEnabled = it
                            if (isOnline) onSendCommand(if(it) "whitelist on" else "whitelist off")
                            CoroutineScope(Dispatchers.IO).launch {
                                val pf = File(Environment.getExternalStorageDirectory(), "AServer/servers/$activeServerName/server.properties")
                                if (pf.exists()) {
                                    val c = pf.readText()
                                    if (c.contains("white-list=")) pf.writeText(c.replace(Regex("white-list=(true|false)"), "white-list=$it"))
                                    else pf.appendText("\nwhite-list=$it")
                                }
                            }
                        }, colors = SwitchDefaults.colors(checkedThumbColor = NeonCyan))
                    }

                    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(value = newWlPlayer, onValueChange = { newWlPlayer = it }, modifier = Modifier.weight(1f), placeholder = { Text(stringResource(R.string.plr_wl_hint), color = Color.Gray, fontSize = 14.sp) }, singleLine = true, shape = RoundedCornerShape(12.dp), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = NeonCyan), textStyle = androidx.compose.ui.text.TextStyle(color = Color.White))
                        Spacer(Modifier.width(8.dp))
                        IconButton(onClick = {
                            if (newWlPlayer.isNotBlank() && isOnline) { onSendCommand("whitelist add $newWlPlayer"); newWlPlayer = "" }
                        }, modifier = Modifier.background(NeonCyan, RoundedCornerShape(12.dp)).size(56.dp)) { Icon(Icons.Rounded.Add, null, tint = Color.Black) }
                    }

                    Text(stringResource(R.string.plr_wl_count, whitelistedPlayers.size), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    if (whitelistedPlayers.isEmpty()) {
                        Text(stringResource(R.string.plr_wl_empty), color = Color.Gray, fontSize = 12.sp)
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(whitelistedPlayers) { playerName ->
                                PlayerCard(playerName, Icons.Rounded.VerifiedUser, NeonCyan) {
                                    IconButton(onClick = {
                                        if (isOnline) { onSendCommand("whitelist remove $playerName") }
                                    }, modifier = Modifier.background(Color(0xFFD32F2F), RoundedCornerShape(8.dp)).size(36.dp)) {
                                        Icon(Icons.Rounded.Delete, contentDescription = "Kaldır", tint = Color.White, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PlayerCard(playerName: String, icon: androidx.compose.ui.graphics.vector.ImageVector, iconTint: Color, actions: @Composable RowScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = GlassSurface), border = BorderStroke(1.dp, GlassBorder), shape = RoundedCornerShape(12.dp)) {
        Row(modifier = Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Text(playerName, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            Row(content = actions)
        }
    }
}

fun getLocalIpAddress(): String {
    try {
        val interfaces = NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            val addresses = networkInterface.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (!address.isLoopbackAddress && address is Inet4Address) return address.hostAddress ?: "Bilinmiyor"
            }
        }
    } catch (e: Exception) { e.printStackTrace() }
    return "Bağlantı Yok"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomDropdownMenu(label: String, options: List<String>, selectedOption: String, onOptionSelected: (String) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }, modifier = modifier) {
        OutlinedTextField(
            value = selectedOption, onValueChange = {}, readOnly = true, label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = NeonCyan, focusedLabelColor = NeonCyan, unfocusedContainerColor = GlassSurface, focusedContainerColor = GlassSurface),
            modifier = Modifier.menuAnchor().fillMaxWidth(), shape = RoundedCornerShape(8.dp)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { selectionOption -> DropdownMenuItem(text = { Text(selectionOption) }, onClick = { onOptionSelected(selectionOption); expanded = false }) }
        }
    }
}

@Composable
fun SystemStatusMonitor(isRunning: Boolean, activeServerName: String) {
    val context = LocalContext.current
    var usedRamStr by remember { mutableStateOf("...") }
    var localIp by remember { mutableStateOf("...") }
    var allocatedRam by remember { mutableStateOf("-") }

    LaunchedEffect(activeServerName) {
        if (activeServerName.isNotEmpty()) {
            val serverDir = File(Environment.getExternalStorageDirectory(), "AServer/servers/$activeServerName")
            val ramFile = File(serverDir, "ram_amount.txt")
            if (ramFile.exists()) {
                allocatedRam = "${ramFile.readText().trim()} GB"
            } else {
                allocatedRam = "-"
            }
        } else {
            allocatedRam = "-"
        }
    }

    LaunchedEffect(isRunning) {
        localIp = getLocalIpAddress()
        while (true) {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memoryInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)

            val totalRamMB = memoryInfo.totalMem / (1024 * 1024)
            val availRamMB = memoryInfo.availMem / (1024 * 1024)
            val usedRamMB = totalRamMB - availRamMB

            usedRamStr = "${usedRamMB}MB / ${totalRamMB}MB"
            delay(2000)
        }
    }

    ServerCard(title = stringResource(R.string.mon_title), icon = Icons.Rounded.Memory) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(stringResource(R.string.mon_ip), color = Color.Gray, fontSize = 12.sp)
                Text(localIp, color = NeonCyan, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(stringResource(R.string.mon_ram_alloc), color = Color.Gray, fontSize = 12.sp)
                Text(if (isRunning || activeServerName.isNotEmpty()) allocatedRam else "-", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(stringResource(R.string.mon_ram_usage), color = Color.Gray, fontSize = 12.sp)
                Text(usedRamStr, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedServerSetupScreen(serverState: ServerState, versionMap: Map<String, String>, onStart: (Map<String, String>) -> Unit, onOpenTermux: () -> Unit) {
    val softwareOptions = listOf("PaperMC (Eklentiler İçin)", "Fabric (Modlar İçin)")
    var software by remember { mutableStateOf(softwareOptions[0]) }

    val versionOptions = versionMap.keys.toList()
    var version by remember { mutableStateOf(versionOptions[0]) }
    val gamemodeOptions = listOf("survival", "creative", "adventure", "spectator")
    var gamemode by remember { mutableStateOf(gamemodeOptions[0]) }
    val difficultyOptions = listOf("peaceful", "easy", "normal", "hard")
    var difficulty by remember { mutableStateOf(difficultyOptions[1]) }
    var profileName by remember { mutableStateOf("") }
    var motd by remember { mutableStateOf("AServer") }
    var seed by remember { mutableStateOf("") }
    var opName by remember { mutableStateOf("") }
    var viewDistance by remember { mutableFloatStateOf(8f) }
    var simDistance by remember { mutableFloatStateOf(8f) }
    var maxPlayers by remember { mutableFloatStateOf(5f) }
    var hardcore by remember { mutableStateOf(false) }
    var onlineMode by remember { mutableStateOf(false) }
    var playitMode by remember { mutableStateOf(true) }
    var port by remember { mutableStateOf("25565") }
    var ram by remember { mutableStateOf("2") }

    Column(modifier = Modifier.fillMaxSize().background(OledBlack).padding(16.dp).verticalScroll(rememberScrollState())) {
        Text(stringResource(R.string.setup_title), fontSize = 28.sp, fontWeight = FontWeight.Black, color = Color.White)
        Spacer(Modifier.height(16.dp))

        ServerCard(title = stringResource(R.string.setup_card_profile), icon = Icons.Rounded.SettingsApplications) {
            OutlinedTextField(value = profileName, onValueChange = { profileName = it.replace(" ", "") }, label = { Text(stringResource(R.string.setup_profile_name)) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = NeonCyan))
            Spacer(Modifier.height(8.dp))

            CustomDropdownMenu(label = stringResource(R.string.setup_software), options = softwareOptions, selectedOption = software, onOptionSelected = { software = it })

            Spacer(Modifier.height(8.dp))
            CustomDropdownMenu(label = stringResource(R.string.setup_version), options = versionOptions, selectedOption = version, onOptionSelected = { version = it })
        }

        ServerCard(title = stringResource(R.string.setup_card_general), icon = Icons.Rounded.ListAlt) {
            OutlinedTextField(value = motd, onValueChange = { motd = it }, label = { Text(stringResource(R.string.setup_motd)) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = NeonCyan))
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = opName, onValueChange = { opName = it }, label = { Text(stringResource(R.string.setup_op_name)) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = NeonCyan))
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = seed, onValueChange = { seed = it }, label = { Text(stringResource(R.string.setup_seed)) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = NeonCyan))
        }

        ServerCard(title = stringResource(R.string.setup_card_rules), icon = Icons.Rounded.SportsEsports) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                CustomDropdownMenu(label = stringResource(R.string.setup_gamemode), options = gamemodeOptions, selectedOption = gamemode, onOptionSelected = { gamemode = it }, modifier = Modifier.weight(1f))
                CustomDropdownMenu(label = stringResource(R.string.setup_difficulty), options = difficultyOptions, selectedOption = difficulty, onOptionSelected = { difficulty = it }, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            SliderItem(stringResource(R.string.setup_view_dist), viewDistance, 2f, 32f, "chunks") { viewDistance = it }
            SliderItem(stringResource(R.string.setup_sim_dist), simDistance, 2f, 32f, "chunks") { simDistance = it }
            SliderItem(stringResource(R.string.setup_max_players), maxPlayers, 1f, 100f, "players") { maxPlayers = it }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.setup_hardcore), color = Color.White)
                Switch(checked = hardcore, onCheckedChange = { hardcore = it }, colors = SwitchDefaults.colors(checkedThumbColor = NeonCyan, checkedTrackColor = NeonCyan.copy(alpha = 0.5f)))
            }
        }

        ServerCard(title = stringResource(R.string.setup_card_network), icon = Icons.Rounded.WifiTethering) {
            OutlinedTextField(value = port, onValueChange = { port = it }, label = { Text(stringResource(R.string.setup_port)) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = NeonCyan))
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = ram, onValueChange = { ram = it }, label = { Text(stringResource(R.string.setup_ram)) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = NeonCyan))
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.setup_playit), color = Color.White)
                    Text(stringResource(R.string.setup_playit_desc), color = Color.Gray, fontSize = 10.sp)
                }
                Switch(checked = playitMode, onCheckedChange = { playitMode = it }, colors = SwitchDefaults.colors(checkedThumbColor = NeonCyan, checkedTrackColor = NeonCyan.copy(alpha = 0.5f)))
            }
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.setup_online_mode), color = Color.White)
                Switch(checked = onlineMode, onCheckedChange = { onlineMode = it }, colors = SwitchDefaults.colors(checkedThumbColor = NeonCyan, checkedTrackColor = NeonCyan.copy(alpha = 0.5f)))
            }
        }

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = {
                if (serverState == ServerState.STOPPED || serverState == ServerState.READY) {
                    onStart(mapOf(
                        "profileName" to profileName.ifBlank { "YeniSunucu" }, "software" to software, "version" to version, "versionNumber" to (versionMap[version] ?: "1.21.11"),
                        "motd" to motd, "seed" to seed, "opName" to opName, "gamemode" to gamemode, "difficulty" to difficulty,
                        "viewDistance" to viewDistance.toInt().toString(), "maxPlayers" to maxPlayers.toInt().toString(), "hardcore" to hardcore.toString(), "onlineMode" to onlineMode.toString(),
                        "playitMode" to playitMode.toString(), "port" to port, "ram" to ram
                    ))
                }
            },
            enabled = serverState != ServerState.STARTING,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = if (serverState == ServerState.STARTING) Color(0xFFF57C00) else NeonCyan, disabledContainerColor = Color(0xFFF57C00)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(text = if (serverState == ServerState.STARTING) stringResource(R.string.setup_btn_creating) else stringResource(R.string.setup_btn_create), fontWeight = FontWeight.Black, color = if (serverState == ServerState.STARTING) Color.White else Color.Black, fontSize = 16.sp)
        }

        if (serverState == ServerState.READY) {
            Spacer(Modifier.height(12.dp))
            Button(onClick = onOpenTermux, modifier = Modifier.fillMaxWidth().height(56.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2)), shape = RoundedCornerShape(16.dp)) {
                Text(stringResource(R.string.setup_btn_termux), fontWeight = FontWeight.Black, color = Color.White, fontSize = 16.sp)
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
fun ServerCard(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(title, color = Color.LightGray, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        }
        Card(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            colors = CardDefaults.cardColors(containerColor = GlassSurface),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, GlassBorder),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) { content() }
        }
    }
}

@Composable
fun SliderItem(label: String, value: Float, min: Float, max: Float, unit: String, onValueChange: (Float) -> Unit) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Color.White, fontSize = 14.sp)
            Text("${value.toInt()} $unit", color = NeonCyan, fontWeight = FontWeight.Bold)
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = min..max, colors = SliderDefaults.colors(activeTrackColor = NeonCyan, thumbColor = Color.White, inactiveTrackColor = Color.DarkGray))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerPropertiesEditorDialog(serverName: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val propsFile = File(Environment.getExternalStorageDirectory(), "AServer/servers/$serverName/server.properties")

    var propsMap by remember { mutableStateOf(mutableMapOf<String, String>()) }
    var isLoaded by remember { mutableStateOf(false) }

    LaunchedEffect(serverName) {
        withContext(Dispatchers.IO) {
            if (propsFile.exists()) {
                val map = mutableMapOf<String, String>()
                propsFile.readLines().forEach { line ->
                    if (!line.startsWith("#") && line.contains("=")) {
                        val parts = line.split("=", limit = 2)
                        map[parts[0].trim()] = parts[1].trim()
                    }
                }
                propsMap = map
                isLoaded = true
            }
        }
    }

    if (isLoaded) {
        var motd by remember { mutableStateOf(propsMap["motd"] ?: "AServer") }
        var maxPlayers by remember { mutableStateOf(propsMap["max-players"] ?: "20") }
        var pvp by remember { mutableStateOf(propsMap["pvp"] == "true") }
        var onlineMode by remember { mutableStateOf(propsMap["online-mode"] == "true") }
        var hardcore by remember { mutableStateOf(propsMap["hardcore"] == "true") }
        var allowNether by remember { mutableStateOf(propsMap["allow-nether"] != "false") }
        var allowFlight by remember { mutableStateOf(propsMap["allow-flight"] == "true") }
        var difficulty by remember { mutableStateOf(propsMap["difficulty"] ?: "easy") }
        var viewDistance by remember { mutableFloatStateOf((propsMap["view-distance"] ?: "8").toFloatOrNull() ?: 8f) }

        val diffOptions = listOf("peaceful", "easy", "normal", "hard")

        AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = GlassSurface,
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.8f),
            title = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Settings, null, tint = NeonCyan)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.set_title), color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            },
            text = {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    item {
                        OutlinedTextField(value = motd, onValueChange = { motd = it }, label = { Text("MOTD") }, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = NeonCyan))
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(value = maxPlayers, onValueChange = { maxPlayers = it }, label = { Text("Max Players") }, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = NeonCyan))
                        Spacer(Modifier.height(16.dp))

                        CustomDropdownMenu(
                            label = "Difficulty",
                            options = diffOptions,
                            selectedOption = difficulty,
                            onOptionSelected = { difficulty = it }
                        )
                        Spacer(Modifier.height(16.dp))

                        Text("View Distance: ${viewDistance.toInt()}", color = Color.White, fontSize = 12.sp)
                        Slider(value = viewDistance, onValueChange = { viewDistance = it }, valueRange = 2f..32f, colors = SliderDefaults.colors(activeTrackColor = NeonCyan, thumbColor = Color.White))
                        Spacer(Modifier.height(8.dp))

                        val switchColors = SwitchDefaults.colors(checkedThumbColor = NeonCyan, checkedTrackColor = NeonCyan.copy(alpha = 0.5f))

                        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                            Text("PvP", color = Color.White, fontSize = 14.sp); Switch(checked = pvp, onCheckedChange = { pvp = it }, colors = switchColors)
                        }
                        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                            Text("Online Mode", color = Color.White, fontSize = 14.sp); Switch(checked = onlineMode, onCheckedChange = { onlineMode = it }, colors = switchColors)
                        }
                        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                            Text("Hardcore", color = Color.White, fontSize = 14.sp); Switch(checked = hardcore, onCheckedChange = { hardcore = it }, colors = switchColors)
                        }
                        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                            Text("Allow Nether", color = Color.White, fontSize = 14.sp); Switch(checked = allowNether, onCheckedChange = { allowNether = it }, colors = switchColors)
                        }
                        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                            Text("Allow Flight", color = Color.White, fontSize = 14.sp); Switch(checked = allowFlight, onCheckedChange = { allowFlight = it }, colors = switchColors)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        CoroutineScope(Dispatchers.IO).launch {
                            try {
                                propsMap["motd"] = motd
                                propsMap["max-players"] = maxPlayers
                                propsMap["pvp"] = pvp.toString()
                                propsMap["online-mode"] = onlineMode.toString()
                                propsMap["hardcore"] = hardcore.toString()
                                propsMap["allow-nether"] = allowNether.toString()
                                propsMap["allow-flight"] = allowFlight.toString()
                                propsMap["difficulty"] = difficulty
                                propsMap["view-distance"] = viewDistance.toInt().toString()

                                val builder = StringBuilder()
                                builder.append("# Minecraft server properties (Generated by AServer)\n")
                                propsMap.forEach { (key, value) ->
                                    builder.append("$key=$value\n")
                                }
                                propsFile.writeText(builder.toString())

                                withContext(Dispatchers.Main) {
                                    Toast.makeText(context, "Kaydedildi!", Toast.LENGTH_SHORT).show()
                                    onDismiss()
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) { Toast.makeText(context, "Hata!", Toast.LENGTH_SHORT).show() }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NeonCyan)
                ) { Text(stringResource(R.string.file_btn_save), color = Color.Black, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.file_btn_cancel), color = Color.Gray) }
            }
        )
    }
}

@Composable
fun RestoreBackupDialog(serverName: String, onDismiss: () -> Unit, onRestore: (File) -> Unit) {
    val backupsDir = File(Environment.getExternalStorageDirectory(), "AServer/backups")
    val allBackups = backupsDir.listFiles { file -> file.extension == "zip" }?.sortedByDescending { it.lastModified() } ?: emptyList()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GlassSurface,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Restore, contentDescription = null, tint = NeonCyan)
                Spacer(Modifier.width(8.dp))
                Text("Restore Backup", color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            if (allBackups.isEmpty()) {
                Text("Yedek bulunamadı.", color = Color.Gray, fontSize = 14.sp)
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                    items(allBackups) { backupFile ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable {
                                onRestore(backupFile)
                                onDismiss()
                            },
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)),
                            border = BorderStroke(1.dp, GlassBorder)
                        ) {
                            Row(modifier = Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.FolderZip, contentDescription = null, tint = Color.LightGray, modifier = Modifier.size(24.dp))
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(backupFile.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                    val date = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(backupFile.lastModified()))
                                    Text("${backupFile.length() / (1024 * 1024)} MB • $date", color = Color.Gray, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Kapat", color = Color.Gray) }
        }
    )
}

class ServerKeepAliveService : android.app.Service() {
    override fun onBind(intent: android.content.Intent?): android.os.IBinder? = null

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        val serverName = intent?.getStringExtra("SERVER_NAME") ?: "Bilinmiyor"

        if (intent?.action == "STOP_SERVER") {
            CoroutineScope(Dispatchers.IO).launch {
                var rPort = 25575
                var rPass = "aserver123"
                try {
                    val pFile = File(Environment.getExternalStorageDirectory(), "AServer/servers/$serverName/server.properties")
                    if (pFile.exists()) {
                        pFile.readLines().forEach {
                            if (it.startsWith("rcon.port=")) rPort = it.substringAfter("=").trim().toIntOrNull() ?: 25575
                            if (it.startsWith("rcon.password=")) rPass = it.substringAfter("=").trim()
                        }
                    }
                    java.net.Socket("127.0.0.1", rPort).use { socket ->
                        socket.soTimeout = 3000
                        val out = socket.getOutputStream()
                        out.write(buildRconPacket(1, 3, rPass.toByteArray()))
                        out.flush()
                        delay(200)
                        out.write(buildRconPacket(2, 2, "stop".toByteArray()))
                        out.flush()
                    }
                } catch (e: Exception) {
                    // Port kapalıysa bir şey yapma
                }
                stopSelf()
            }
            return START_NOT_STICKY
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                "ASERVER_CHANNEL",
                "AServer Arka Plan Zırhı",
                android.app.NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(android.app.NotificationManager::class.java)?.createNotificationChannel(channel)
        }

        val stopIntent = Intent(this, ServerKeepAliveService::class.java).apply {
            action = "STOP_SERVER"
            putExtra("SERVER_NAME", serverName)
        }

        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = androidx.core.app.NotificationCompat.Builder(this, "ASERVER_CHANNEL")
            .setContentTitle("AServer - Sunucu Çalışıyor")
            .setContentText("Aktif Profil: $serverName")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_delete, "DURDUR (KAYDET VE KAPAT)", stopPendingIntent)
            .build()

        startForeground(1, notification)
        return START_STICKY
    }
}

fun parseAnsiToAnnotatedString(text: String): AnnotatedString {
    return buildAnnotatedString {
        val lines = text.split("\n")

        for ((index, line) in lines.withIndex()) {
            var defaultColor = Color.LightGray
            val upperLine = line.uppercase()

            if (upperLine.contains("[ERROR]") || upperLine.contains("[HATA]") || upperLine.contains("EXCEPTION") || upperLine.contains("DİKKAT") || upperLine.contains("FAILED")) {
                defaultColor = Color(0xFFE53935)
            } else if (upperLine.contains("[WARN]") || upperLine.contains("WARNING")) {
                defaultColor = Color(0xFFFFB300)
            } else if (upperLine.contains("[INFO]")) {
                defaultColor = Color(0xFF26C6DA)
            } else if (line.contains(">>>") || upperLine.contains("[SİSTEM]") || upperLine.contains("[RCON]") || upperLine.contains("[SUNUCU]")) {
                defaultColor = NeonCyan
            }

            val ansiRegex = Regex("\u001B\\[([0-9;]*)m")
            var currentIndex = 0
            var currentColor = defaultColor

            val matches = ansiRegex.findAll(line)
            for (match in matches) {
                if (match.range.first > currentIndex) {
                    withStyle(SpanStyle(color = currentColor)) {
                        append(line.substring(currentIndex, match.range.first))
                    }
                }

                val codes = match.groupValues[1].split(";")
                for (code in codes) {
                    currentColor = when (code) {
                        "0", "" -> defaultColor
                        "30" -> Color.DarkGray
                        "31" -> Color(0xFFE53935)
                        "32" -> NeonCyan
                        "33" -> Color(0xFFFFB300)
                        "34" -> Color(0xFF1E88E5)
                        "35" -> Color(0xFF8E24AA)
                        "36" -> Color(0xFF00ACC1)
                        "37" -> Color.White
                        "90" -> Color.Gray
                        "91" -> Color(0xFFEF5350)
                        "92" -> Color(0xFF66BB6A)
                        "93" -> Color(0xFFFFCA28)
                        "94" -> Color(0xFF42A5F5)
                        "95" -> Color(0xFFAB47BC)
                        "96" -> Color(0xFF26C6DA)
                        "97" -> Color.White
                        else -> currentColor
                    }
                }
                currentIndex = match.range.last + 1
            }

            if (currentIndex < line.length) {
                withStyle(SpanStyle(color = currentColor)) {
                    append(line.substring(currentIndex))
                }
            }

            if (index < lines.size - 1) {
                append("\n")
            }
        }
    }
}
// YENİ EKLENEN: Akıllı Paket Yöneticisi (Zincirleme İndirme Motoru)
suspend fun downloadModWithDependencies(
    projectId: String,
    mcVersion: String,
    targetDir: File,
    downloadedProjects: MutableSet<String>
) {
    // Sonsuz döngüye girmemek için bu mod daha önce indirildiyse atla
    if (downloadedProjects.contains(projectId)) return
    downloadedProjects.add(projectId)

    try {
        val versionUrlStr = "https://api.modrinth.com/v2/project/$projectId/version?loaders=%5B%22fabric%22%5D&game_versions=%5B%22$mcVersion%22%5D"
        val vConn = URL(versionUrlStr).openConnection() as HttpURLConnection
        vConn.requestMethod = "GET"
        vConn.setRequestProperty("User-Agent", "AServerApp")

        if (vConn.responseCode == 200) {
            val vResponse = vConn.inputStream.bufferedReader().readText()
            val vArray = org.json.JSONArray(vResponse)

            if (vArray.length() > 0) {
                val latestCompatibleVersion = vArray.getJSONObject(0)
                val filesArray = latestCompatibleVersion.getJSONArray("files")

                if (filesArray.length() > 0) {
                    val primaryFile = filesArray.getJSONObject(0)
                    val downloadUrl = primaryFile.getString("url")
                    val fileName = primaryFile.getString("filename")

                    // 1. Ana Dosyayı İndir
                    val destFile = File(targetDir, fileName)
                    if (!destFile.exists()) {
                        val dConn = URL(downloadUrl).openConnection() as HttpURLConnection
                        dConn.requestMethod = "GET"
                        dConn.setRequestProperty("User-Agent", "AServerApp")
                        dConn.inputStream.use { inp -> destFile.outputStream().use { out -> inp.copyTo(out) } }
                    }

                    // 2. Zeka Devrede: Yan modları (Dependencies) tarıyoruz
                    if (latestCompatibleVersion.has("dependencies")) {
                        val depsArray = latestCompatibleVersion.getJSONArray("dependencies")
                        for (i in 0 until depsArray.length()) {
                            val dep = depsArray.getJSONObject(i)
                            val depType = dep.optString("dependency_type")
                            val depProjectId = dep.optString("project_id", "")

                            // Eğer bu yan mod zorunluysa, motoru onun için tekrar ateşle!
                            if (depType == "required" && depProjectId.isNotEmpty() && depProjectId != "null") {
                                downloadModWithDependencies(depProjectId, mcVersion, targetDir, downloadedProjects)
                            }
                        }
                    }
                }
            }
        }
    } catch (e: Exception) {
        // Arka planda sessizce hata yakalanır, zinciri kırmadan devam eder
    }
}
