package com.example.ndq1

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*
import com.example.ndq1.ui.theme.NDQ1Theme
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalTvMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NDQ1Theme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = RectangleShape
                ) {
                    IPTVGroupsScreen()
                }
            }
        }
    }
}

// Data class for debug information
data class DebugInfo(
    val m3uChannels: List<String> = emptyList(),
    val epgPrograms: List<String> = emptyList(),
    val searchTerm: String = ""
)

// Data class for EPG statistics
data class EpgStats(
    val totalChunks: Int = 0,
    val totalSize: Long = 0,
    val favoriteChannelsWithPrograms: Int = 0,
    val totalProgramsForFavorites: Int = 0,
    val lastUpdated: String = "",
    val chunkDetails: List<ChunkInfo> = emptyList()
)

data class ChunkInfo(
    val fileName: String,
    val size: Long,
    val programCount: Int = 0
)

// Function to normalize channel names by removing common prefixes
fun normalizeChannelName(channelName: String): String {
    return channelName
        .trim()
        .removePrefix("CA:")
        .removePrefix("US:")
        .removePrefix("CA-S:")
        .removePrefix("UK:")
        .removePrefix("EU:")
        .removePrefix("ASIA:")
        .removePrefix("AU:")
        .removePrefix("LAT:")
        .trim()
}

// Function to get debug information for channel matching
suspend fun getDebugInfo(context: Context, searchTerm: String = "CTV Toronto"): DebugInfo {
    return withContext(Dispatchers.IO) {
        val m3uChannels = mutableListOf<String>()
        val epgPrograms = mutableListOf<String>()

        // Search M3U channels
        try {
            val m3uFile = File(context.filesDir, "channels.m3u")
            if (m3uFile.exists()) {
                m3uFile.readLines().forEach { line ->
                    if (line.contains(searchTerm, ignoreCase = true)) {
                        m3uChannels.add(line.trim())
                    }
                }
            }
        } catch (e: Exception) {
            m3uChannels.add("Error reading M3U file: ${e.message}")
        }

        // Search EPG programs
        try {
            val epgDir = File(context.filesDir, "epg_chunks")
            if (epgDir.exists()) {
                epgDir.listFiles()?.filter { it.name.endsWith(".xml") }?.forEach { chunkFile ->
                    try {
                        chunkFile.bufferedReader().use { reader ->
                            var line: String?
                            while (reader.readLine().also { line = it } != null) {
                                val lineStr = line ?: ""
                                if (lineStr.contains(searchTerm, ignoreCase = true)) {
                                    epgPrograms.add(lineStr.trim())
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // Skip problematic chunks
                    }
                }
            }
        } catch (e: Exception) {
            epgPrograms.add("Error reading EPG chunks: ${e.message}")
        }

        DebugInfo(
            m3uChannels = m3uChannels,
            epgPrograms = epgPrograms,
            searchTerm = searchTerm
        )
    }
}

// Function to analyze EPG chunks and count programs for favorite channels
suspend fun analyzeEpgChunks(context: Context, favoriteChannels: Set<String>): EpgStats {
    return withContext(Dispatchers.IO) {
        val epgDir = File(context.filesDir, "epg_chunks")
        if (!epgDir.exists()) {
            return@withContext EpgStats()
        }

        val chunkFiles = epgDir.listFiles()?.filter { it.name.endsWith(".xml") }?.sortedBy { it.name } ?: emptyList()
        val totalSize = chunkFiles.sumOf { it.length() }
        val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm:ss", Locale.getDefault())

        val chunkDetails = mutableListOf<ChunkInfo>()
        var totalProgramsForFavorites = 0
        var favoriteChannelsWithPrograms = 0

        // Create normalized versions of favorite channels for better matching
        val normalizedFavorites = favoriteChannels.map { normalizeChannelName(it) }.toSet()

        for (chunkFile in chunkFiles) {
            var programCount = 0
            var favoriteProgramsInChunk = 0

            try {
                chunkFile.bufferedReader().use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val lineStr = line ?: ""
                        if (lineStr.contains("<programme")) {
                            programCount++

                            // Check if this program is for a favorite channel using normalized names
                            for (normalizedFavorite in normalizedFavorites) {
                                if (lineStr.contains("channel=\"$normalizedFavorite\"") ||
                                    lineStr.contains("channel=\"${normalizedFavorite.replace(":", "")}\"")) {
                                    favoriteProgramsInChunk++
                                    break
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // Skip problematic chunks
            }

            chunkDetails.add(ChunkInfo(
                fileName = chunkFile.name,
                size = chunkFile.length(),
                programCount = programCount
            ))

            totalProgramsForFavorites += favoriteProgramsInChunk
        }

        // Count unique favorite channels that have programs using normalized matching
        val channelsWithPrograms = mutableSetOf<String>()
        for (chunkFile in chunkFiles) {
            try {
                chunkFile.bufferedReader().use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val lineStr = line ?: ""
                        if (lineStr.contains("<programme")) {
                            for (normalizedFavorite in normalizedFavorites) {
                                if (lineStr.contains("channel=\"$normalizedFavorite\"") ||
                                    lineStr.contains("channel=\"${normalizedFavorite.replace(":", "")}\"")) {
                                    // Find the original favorite channel name that matches this normalized version
                                    val originalFavorite = favoriteChannels.find { normalizeChannelName(it) == normalizedFavorite }
                                    if (originalFavorite != null) {
                                        channelsWithPrograms.add(originalFavorite)
                                    }
                                    break
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // Skip problematic chunks
            }
        }

        EpgStats(
            totalChunks = chunkFiles.size,
            totalSize = totalSize,
            favoriteChannelsWithPrograms = channelsWithPrograms.size,
            totalProgramsForFavorites = totalProgramsForFavorites,
            lastUpdated = dateFormat.format(Date(epgDir.lastModified())),
            chunkDetails = chunkDetails
        )
    }
}

// Function to download and split EPG file into chunks
suspend fun downloadAndSplitEpgFile(inputStream: java.io.InputStream, context: Context, maxChunkSize: Long) {
    withContext(Dispatchers.IO) {
        val epgDir = File(context.filesDir, "epg_chunks")

        // Clean up old chunks
        if (epgDir.exists()) {
            epgDir.deleteRecursively()
        }
        epgDir.mkdirs()

        val buffer = ByteArray(8192)
        var bytesRead: Int
        var totalBytesRead = 0L
        var chunkIndex = 0
        var currentChunkSize = 0L

        var currentOutputStream: java.io.FileOutputStream? = null
        var currentChunkFile: File? = null

        try {
            inputStream.use { input ->
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    // Create new chunk if needed
                    if (currentOutputStream == null || currentChunkSize >= maxChunkSize) {
                        currentOutputStream?.close()

                        currentChunkFile = File(epgDir, "epg_chunk_${chunkIndex.toString().padStart(3, '0')}.xml")
                        currentOutputStream = java.io.FileOutputStream(currentChunkFile)
                        currentChunkSize = 0L
                        chunkIndex++
                    }

                    // Write to current chunk
                    currentOutputStream?.write(buffer, 0, bytesRead)
                    currentChunkSize += bytesRead
                    totalBytesRead += bytesRead
                }
            }
        } finally {
            currentOutputStream?.close()
        }
    }
}

@Composable
fun IPTVGroupsScreen(modifier: Modifier = Modifier) {
    var groups by remember { mutableStateOf<List<String>>(emptyList()) }
    var groupChannels by remember { mutableStateOf<Map<String, List<Pair<String, String>>>>(emptyMap()) }
    var error by remember { mutableStateOf<String?>(null) }
    var progressMessage by remember { mutableStateOf("Starting to load M3U file...") }
    var selectedGroup by remember { mutableStateOf<String?>(null) }
    var currentView by remember { mutableStateOf("favorites") }
    var favorites by remember { mutableStateOf(setOf<String>()) }
    var fileTimestamp by remember { mutableStateOf<String?>(null) }
    var refreshTrigger by remember { mutableStateOf(0) }
    var epgRefreshTrigger by remember { mutableStateOf(0) }
    var epgTimestamp by remember { mutableStateOf<String?>(null) }
    var epgStats by remember { mutableStateOf<EpgStats?>(null) }
    var debugInfo by remember { mutableStateOf<DebugInfo?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Function to load configuration from assets
    fun loadConfig(): Pair<String?, String?> {
        return try {
            val inputStream = context.assets.open("config.json")
            val jsonString = inputStream.bufferedReader().use { it.readText() }
            val jsonObject = JSONObject(jsonString)
            val m3uUrl: String? = if (jsonObject.has("m3u_url")) jsonObject.getString("m3u_url") else null
            val epgUrl: String? = if (jsonObject.has("epg_url")) jsonObject.getString("epg_url") else null
            Pair(m3uUrl, epgUrl)
        } catch (e: Exception) {
            // Return null if config file doesn't exist or can't be read
            Pair(null, null)
        }
    }

    // Load favorites from SharedPreferences
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("iptv_prefs", Context.MODE_PRIVATE)
        val favSet = prefs.getStringSet("favorites", setOf()) ?: setOf()
        favorites = favSet
    }

    // Function to save favorites
    fun saveFavorites(newFavorites: Set<String>) {
        scope.launch(Dispatchers.IO) {
            val prefs = context.getSharedPreferences("iptv_prefs", Context.MODE_PRIVATE)
            prefs.edit().putStringSet("favorites", newFavorites).apply()
        }
    }

    // Function to toggle favorite
    fun toggleFavorite(channelName: String) {
        val newFavorites = if (favorites.contains(channelName)) {
            favorites - channelName
        } else {
            favorites + channelName
        }
        favorites = newFavorites
        saveFavorites(newFavorites)
    }

    // Function to refresh M3U file
    fun refreshM3U() {
        refreshTrigger++
    }

    // Function to refresh EPG file
    fun refreshEPG() {
        epgRefreshTrigger++
    }

    // Function to refresh EPG stats
    fun refreshEpgStats() {
        scope.launch {
            epgStats = analyzeEpgChunks(context, favorites)
        }
    }

    // Function to refresh debug info
    fun refreshDebugInfo() {
        scope.launch {
            debugInfo = getDebugInfo(context, "CTV Toronto")
        }
    }

    // Load EPG stats when favorites change or on initial load
    LaunchedEffect(favorites, epgRefreshTrigger) {
        if (favorites.isNotEmpty() || epgRefreshTrigger > 0) {
            refreshEpgStats()
        }
    }

    LaunchedEffect(refreshTrigger) {
        withContext(Dispatchers.IO) {
            try {
                val m3uFile = File(context.filesDir, "channels.m3u")
                val shouldRefresh = !m3uFile.exists() || (System.currentTimeMillis() - m3uFile.lastModified()) > 24 * 60 * 60 * 1000L || refreshTrigger > 0

                if (shouldRefresh) {
                    progressMessage = "Fetching M3U file from server..."
                    val (configM3uUrl, _) = loadConfig()
                    val m3uUrlString = configM3uUrl ?: "http://robo.stream:2082/get.php?username=cli2562&password=cWSBCfka&type=m3u&output=mpegts"
                    val url = URL(m3uUrlString)
                    val connection = url.openConnection()
                    val inputStream = connection.getInputStream()
                    val reader = inputStream.bufferedReader()
                    val content = reader.use { it.readText() }

                    // Save to file
                    FileWriter(m3uFile).use { it.write(content) }
                    progressMessage = "Downloaded and saved M3U file."
                } else {
                    progressMessage = "Loading M3U file from cache..."
                }

                // Read from file
                val lines = m3uFile.readLines()

                // Format and store the file timestamp
                val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm:ss", Locale.getDefault())
                fileTimestamp = "M3U file: ${dateFormat.format(Date(m3uFile.lastModified()))}"

                progressMessage = "Downloaded ${lines.size} lines. Parsing..."

                val groupSet = mutableSetOf<String>()
                val channelsMap = mutableMapOf<String, MutableList<Pair<String, String>>>()
                var processedLines = 0
                var currentChannel = ""
                var currentUrl = ""
                for (i in lines.indices) {
                    val line = lines[i]
                    processedLines++
                    if (line.startsWith("#EXTINF:")) {
                        val commaIndex = line.indexOf(',')
                        if (commaIndex != -1) {
                            val channelName = line.substring(commaIndex + 1).trim()
                            val colonIndex = channelName.indexOf(':')
                            if (colonIndex != -1) {
                                val group = channelName.substring(0, colonIndex).trim()
                                if (group.isNotEmpty()) {
                                    groupSet.add(group)
                                    currentChannel = channelName
                                    // Get the next line as URL
                                    if (i + 1 < lines.size) {
                                        currentUrl = lines[i + 1].trim()
                                    }
                                    if (!channelsMap.containsKey(group)) {
                                        channelsMap[group] = mutableListOf()
                                    }
                                    channelsMap[group]!!.add(Pair(currentChannel, currentUrl))
                                }
                            }
                        }
                    }
                    if (processedLines % 100 == 0) {
                        progressMessage = "Parsed $processedLines lines, found ${groupSet.size} groups so far..."
                    }
                }
                progressMessage = "Found ${groupSet.size} unique groups. Filtering for CA and CA-S..."
                groups = groupSet.filter { it == "CA" || it == "CA-S" }.sorted()
                groupChannels = channelsMap.filterKeys { it == "CA" || it == "CA-S" }
                if (groups.isEmpty()) {
                    val sampleLines = lines.take(10).joinToString("\n")
                    progressMessage = "No CA or CA-S groups found. Sample lines from M3U file:\n$sampleLines"
                } else {
                    progressMessage = "Loaded ${groups.size} filtered groups successfully."
                }
            } catch (e: Exception) {
                error = "Error fetching or parsing M3U file: ${e.message}"
                progressMessage = "Error: ${e.message}"
            }
        }
    }

    // Load EPG data
    LaunchedEffect(epgRefreshTrigger) {
        withContext(Dispatchers.IO) {
            try {
                val epgFile = File(context.filesDir, "epg.xml")
                val shouldRefreshEPG = !epgFile.exists() || (System.currentTimeMillis() - epgFile.lastModified()) > 24 * 60 * 60 * 1000L || epgRefreshTrigger > 0

                if (shouldRefreshEPG) {
                    progressMessage = "Fetching EPG file from server..."
                    val (_, configEpgUrl) = loadConfig()
                    val epgUrlString = configEpgUrl ?: "http://robo.stream:2082/epg.php?username=cli2562&password=cWSBCfka"
                    val epgUrl = URL(epgUrlString)
                    val connection = epgUrl.openConnection()
                    val inputStream = connection.getInputStream()

                    // Check content length - allow larger files now that we can split them
                    val contentLength = connection.contentLength
                    val maxChunkSize = 25 * 1024 * 1024L // 25MB chunks

                    if (contentLength > 0) {
                        val numChunks = (contentLength + maxChunkSize - 1) / maxChunkSize
                        progressMessage = "EPG file size: ${contentLength / 1024 / 1024}MB. Will split into $numChunks chunks."
                    }

                    // Download and split the file into chunks
                    downloadAndSplitEpgFile(inputStream, context, maxChunkSize)

                    progressMessage = "EPG file downloaded and split into chunks."
                } else {
                    // Check if we have EPG chunks
                    val epgDir = File(context.filesDir, "epg_chunks")
                    if (epgDir.exists() && epgDir.listFiles()?.isNotEmpty() == true) {
                        val chunkCount = epgDir.listFiles()?.size ?: 0
                        progressMessage = "Loaded EPG from cache ($chunkCount chunks)."
                    } else {
                        progressMessage = "No EPG data available."
                    }
                }

                // Format and store the EPG file timestamp
                val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm:ss", Locale.getDefault())
                val epgDir = File(context.filesDir, "epg_chunks")
                if (epgDir.exists()) {
                    val chunkFiles = epgDir.listFiles() ?: emptyArray()
                    val totalSize = chunkFiles.sumOf { it.length() }
                    epgTimestamp = "EPG chunks: ${dateFormat.format(Date(epgDir.lastModified()))} (${chunkFiles.size} files, ${totalSize / 1024} KB)"
                } else {
                    epgTimestamp = "EPG: No chunks available"
                }

            } catch (e: Exception) {
                // EPG loading failed, but don't block the app
                epgTimestamp = "EPG: Failed to load"
                progressMessage = "EPG loading failed, but M3U loaded successfully."
            }
        }
    }

    if (error != null) {
        Text(text = error!!)
    } else if (groups.isEmpty()) {
        Text(text = progressMessage)
    } else {
        Box(modifier = modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header with stats
                Row(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    // Main navigation tabs
                    Row(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Favorites",
                            modifier = Modifier
                                .weight(1f)
                                .clickable { currentView = "favorites" }
                                .padding(8.dp)
                        )
                        Text(
                            text = "Groups",
                            modifier = Modifier
                                .weight(1f)
                                .clickable { currentView = "groups" }
                                .padding(8.dp)
                        )
                        Text(
                            text = "EPG Info",
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    currentView = "epg_info"
                                    refreshEpgStats()
                                }
                                .padding(8.dp)
                        )
                        Text(
                            text = "Debug",
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    currentView = "debug"
                                    refreshDebugInfo()
                                }
                                .padding(8.dp)
                        )
                    }

                    // Stats in top right corner
                    if (epgTimestamp != null || fileTimestamp != null) {
                        Column(
                            modifier = Modifier.padding(start = 16.dp),
                            horizontalAlignment = Alignment.End
                        ) {
                            if (epgTimestamp != null) {
                                Text(
                                    text = epgTimestamp!!,
                                    style = androidx.tv.material3.MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.clickable { refreshEPG() }
                                )
                            }
                            if (fileTimestamp != null) {
                                Text(
                                    text = fileTimestamp!!,
                                    style = androidx.tv.material3.MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.clickable { refreshM3U() }
                                )
                            }
                        }
                    }
                }
                // Main content
                if (currentView == "groups") {
                    Row(modifier = Modifier.weight(1f)) {
                        // Left side: Groups
                        LazyColumn(modifier = Modifier.weight(1f).fillMaxHeight().padding(16.dp)) {
                            item {
                                Text(text = "Groups:")
                            }
                            items(groups) { group ->
                                Text(
                                    text = group,
                                    modifier = Modifier.clickable {
                                        selectedGroup = group
                                    }.padding(8.dp)
                                )
                            }
                        }
                        // Right side: Channels
                        LazyColumn(modifier = Modifier.weight(1f).fillMaxHeight().padding(16.dp)) {
                            if (selectedGroup != null) {
                                item {
                                    Text(text = "Channels in $selectedGroup:")
                                }
                                items(groupChannels[selectedGroup!!] ?: emptyList()) { channelPair ->
                                    val (channelName, channelUrl) = channelPair
                                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                        Text(
                                            text = channelName,
                                            modifier = Modifier
                                                .weight(1f)
                                                .clickable {
                                                    Toast.makeText(context, "Playing: $channelUrl", Toast.LENGTH_SHORT).show()
                                                    try {
                                                        val intent = Intent(Intent.ACTION_VIEW)
                                                        intent.setData(Uri.parse(channelUrl))
                                                        intent.setPackage("org.videolan.vlc")
                                                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                        context.startActivity(intent)
                                                    } catch (e: Exception) {
                                                        // Fallback without package
                                                        val intent = Intent(Intent.ACTION_VIEW)
                                                        intent.setData(Uri.parse(channelUrl))
                                                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                        context.startActivity(intent)
                                                    }
                                                }
                                        )
                                        Text(
                                            text = if (favorites.contains(channelName)) "★" else "☆",
                                            modifier = Modifier
                                                .clickable { toggleFavorite(channelName) }
                                                .padding(start = 8.dp)
                                        )
                                    }
                                }
                            } else {
                                item {
                                    Text(text = "Select a group to view channels.")
                                }
                            }
                        }
                    }
                } else if (currentView == "epg_info") {
                    // EPG Info view
                    @OptIn(ExperimentalTvMaterial3Api::class)
                    LazyColumn(modifier = Modifier.weight(1f).fillMaxSize().padding(16.dp)) {
                        item {
                            Text(
                                text = "EPG Information",
                                style = androidx.tv.material3.MaterialTheme.typography.headlineMedium
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                        }

                        if (epgStats == null) {
                            item {
                                Text("Loading EPG statistics...")
                                androidx.tv.material3.Button(
                                    onClick = { refreshEpgStats() },
                                    modifier = Modifier.padding(top = 8.dp)
                                ) {
                                    Text("Refresh Stats")
                                }
                            }
                        } else {
                            val stats = epgStats!!
                            item {
                                Text("📊 EPG Overview", style = androidx.tv.material3.MaterialTheme.typography.titleMedium)
                                Spacer(modifier = Modifier.height(8.dp))
                            }

                            item {
                                Text("Total Chunks: ${stats.totalChunks}")
                                Text("Total Size: ${stats.totalSize / 1024} KB")
                                Text("Last Updated: ${stats.lastUpdated}")
                                Spacer(modifier = Modifier.height(16.dp))
                            }

                            item {
                                Text("📺 Favorite Channels", style = androidx.tv.material3.MaterialTheme.typography.titleMedium)
                                Spacer(modifier = Modifier.height(8.dp))
                            }

                            item {
                                Text("Channels with Programs: ${stats.favoriteChannelsWithPrograms}")
                                Text("Total Programs: ${stats.totalProgramsForFavorites}")
                                Spacer(modifier = Modifier.height(16.dp))
                            }

                            item {
                                Text("📁 Chunk Details", style = androidx.tv.material3.MaterialTheme.typography.titleMedium)
                                Spacer(modifier = Modifier.height(8.dp))
                            }

                            items(stats.chunkDetails) { chunk ->
                                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                    Text("📄 ${chunk.fileName}")
                                    Text("   Size: ${chunk.size / 1024} KB, Programs: ${chunk.programCount}",
                                        style = androidx.tv.material3.MaterialTheme.typography.bodySmall)
                                }
                            }

                            item {
                                Spacer(modifier = Modifier.height(16.dp))
                                androidx.tv.material3.Button(onClick = { refreshEpgStats() }) {
                                    Text("Refresh Stats")
                                }
                            }
                        }
                    }
                } else if (currentView == "debug") {
                    // Debug view
                    @OptIn(ExperimentalTvMaterial3Api::class)
                    LazyColumn(modifier = Modifier.weight(1f).fillMaxSize().padding(16.dp)) {
                        item {
                            Text(
                                text = "Debug Information",
                                style = androidx.tv.material3.MaterialTheme.typography.headlineMedium
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                        }

                        if (debugInfo == null) {
                            item {
                                Text("Loading debug information...")
                                androidx.tv.material3.Button(
                                    onClick = { refreshDebugInfo() },
                                    modifier = Modifier.padding(top = 8.dp)
                                ) {
                                    Text("Refresh Debug Info")
                                }
                            }
                        } else {
                            val debug = debugInfo!!
                            item {
                                Text("🔍 Searching for: \"${debug.searchTerm}\"", style = androidx.tv.material3.MaterialTheme.typography.titleMedium)
                                Spacer(modifier = Modifier.height(16.dp))
                            }

                            item {
                                Text("📺 M3U Channels (${debug.m3uChannels.size} found)", style = androidx.tv.material3.MaterialTheme.typography.titleMedium)
                                Spacer(modifier = Modifier.height(8.dp))
                            }

                            if (debug.m3uChannels.isEmpty()) {
                                item {
                                    Text("No M3U channels found containing \"${debug.searchTerm}\"")
                                }
                            } else {
                                items(debug.m3uChannels) { channel ->
                                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                        Text(channel, style = androidx.tv.material3.MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }

                            item {
                                Spacer(modifier = Modifier.height(16.dp))
                                Text("📡 EPG Programs (${debug.epgPrograms.size} found)", style = androidx.tv.material3.MaterialTheme.typography.titleMedium)
                                Spacer(modifier = Modifier.height(8.dp))
                            }

                            if (debug.epgPrograms.isEmpty()) {
                                item {
                                    Text("No EPG programs found containing \"${debug.searchTerm}\"")
                                }
                            } else {
                                items(debug.epgPrograms.take(50)) { program -> // Limit to first 50 for performance
                                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                        Text(program, style = androidx.tv.material3.MaterialTheme.typography.bodySmall)
                                    }
                                }
                                if (debug.epgPrograms.size > 50) {
                                    item {
                                        Text("... and ${debug.epgPrograms.size - 50} more programs", style = androidx.tv.material3.MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }

                            item {
                                Spacer(modifier = Modifier.height(16.dp))
                                androidx.tv.material3.Button(onClick = { refreshDebugInfo() }) {
                                    Text("Refresh Debug Info")
                                }
                            }
                        }
                    }
                } else {
                    // Favorites view
                    val favoriteChannels = groupChannels.values.flatten().filter { it.first in favorites }
                    LazyColumn(modifier = Modifier.weight(1f).fillMaxSize().padding(16.dp)) {
                        item {
                            Text(text = "Favorites:")
                        }
                        if (favoriteChannels.isEmpty()) {
                            item {
                                Text(text = "No favorite channels yet. Click the star icon next to channels to add them.")
                            }
                        } else {
                            items(favoriteChannels) { channelPair ->
                                val (channelName, channelUrl) = channelPair
                                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                    Text(
                                        text = channelName,
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable {
                                                Toast.makeText(context, "Playing: $channelUrl", Toast.LENGTH_SHORT).show()
                                                try {
                                                    val intent = Intent(Intent.ACTION_VIEW)
                                                    intent.setData(Uri.parse(channelUrl))
                                                    intent.setPackage("org.videolan.vlc")
                                                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                    context.startActivity(intent)
                                                } catch (e: Exception) {
                                                    // Fallback without package
                                                    val intent = Intent(Intent.ACTION_VIEW)
                                                    intent.setData(Uri.parse(channelUrl))
                                                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                    context.startActivity(intent)
                                                }
                                            }
                                    )
                                    Text(
                                        text = "★",
                                        modifier = Modifier
                                            .clickable { toggleFavorite(channelName) }
                                            .padding(start = 8.dp)
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

@Preview(showBackground = true)
@Composable
fun IPTVGroupsScreenPreview() {
    NDQ1Theme {
        IPTVGroupsScreen()
    }
}