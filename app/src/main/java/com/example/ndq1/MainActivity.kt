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

                    // Check content length to avoid memory issues
                    val contentLength = connection.contentLength
                    if (contentLength > 50 * 1024 * 1024) { // 50MB limit
                        progressMessage = "EPG file too large (${contentLength / 1024 / 1024}MB). Skipping download."
                        epgTimestamp = "EPG: File too large (${contentLength / 1024 / 1024}MB)"
                        return@withContext
                    }

                    // Read with progress tracking - write directly to file to avoid memory issues
                    var bytesRead = 0L
                    val buffer = ByteArray(8192)
                    var bytes: Int

                    epgFile.outputStream().use { output ->
                        inputStream.use { input ->
                            while (input.read(buffer).also { bytes = it } != -1) {
                                output.write(buffer, 0, bytes)
                                bytesRead += bytes
                                progressMessage = "Downloading EPG... ${bytesRead / 1024} KB received"
                            }
                        }
                    }

                    progressMessage = "Downloaded EPG file (${bytesRead / 1024} KB). File saved."
                } else {
                    // Check file size before attempting to read
                    val fileSize = epgFile.length()
                    if (fileSize > 50 * 1024 * 1024) { // 50MB limit
                        progressMessage = "EPG file too large (${fileSize / 1024 / 1024}MB). Skipping load."
                        epgTimestamp = "EPG: File too large (${fileSize / 1024 / 1024}MB)"
                        return@withContext
                    }

                    progressMessage = "Loaded EPG from cache (${fileSize / 1024} KB)."
                }

                // Format and store the EPG file timestamp
                val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm:ss", Locale.getDefault())
                val fileSize = epgFile.length()
                epgTimestamp = "EPG file: ${dateFormat.format(Date(epgFile.lastModified()))} (${fileSize / 1024} KB)"

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
                // Header
                Row(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
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

            // Timestamps at bottom
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp)
            ) {
                if (epgTimestamp != null) {
                    Text(
                        text = epgTimestamp!!,
                        modifier = Modifier
                            .padding(bottom = 4.dp)
                            .clickable { refreshEPG() }
                    )
                }
                if (fileTimestamp != null) {
                    Text(
                        text = fileTimestamp!!,
                        modifier = Modifier.clickable { refreshM3U() }
                    )
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