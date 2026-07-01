package com.example.sampleapp

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Environment
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File

// --- Custom Theme Colors ---
val SaturatedOrange = Color(0xFFFF6D00)
val DarkBackground = Color(0xFF121212)
val DarkSurface = Color(0xFF1E1E1E)

val OrangeDarkColorScheme = darkColorScheme(
    primary = SaturatedOrange,
    onPrimary = Color.Black,
    background = DarkBackground,
    onBackground = SaturatedOrange,
    surface = DarkSurface,
    onSurface = SaturatedOrange,
    primaryContainer = Color(0xFF2C2C2C),
    onPrimaryContainer = SaturatedOrange,
    errorContainer = Color(0xFFCF6679),
    onErrorContainer = Color.Black
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sharedPreferences = getSharedPreferences("GitAppPrefs", Context.MODE_PRIVATE)
        val defaultDir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)?.absolutePath ?: filesDir.absolutePath
        val defaultRepoPath = "$defaultDir/my_repo"

        setContent {
            MaterialTheme(colorScheme = OrangeDarkColorScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    GitControlScreen(sharedPreferences, defaultRepoPath)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitControlScreen(prefs: SharedPreferences, defaultRepoPath: String) {
    val coroutineScope = rememberCoroutineScope()

    // States
    var localPath by remember { mutableStateOf(prefs.getString("LOCAL_PATH", defaultRepoPath) ?: defaultRepoPath) }
    var remoteUrl by remember { mutableStateOf(prefs.getString("REMOTE_URL", "") ?: "") }
    var username by remember { mutableStateOf(prefs.getString("USERNAME", "") ?: "") }
    var token by remember { mutableStateOf(prefs.getString("TOKEN", "") ?: "") }

    var statusMessage by remember { mutableStateOf("Ready") }
    var isError by remember { mutableStateOf(false) }

    // UI Toggles (Declared exactly once)
    var isConfigExpanded by remember { mutableStateOf(true) }
    var showDirPicker by remember { mutableStateOf(false) }

    val currentRepoDir = File(localPath).absoluteFile
    val isExistingRepo = File(currentRepoDir, ".git/config").exists() || File(currentRepoDir, ".git/HEAD").exists()

    // Helper Functions (Must be inside GitControlScreen)
    fun updatePref(key: String, value: String, updater: (String) -> Unit) {
        updater(value)
        prefs.edit().putString(key, value).apply()
    }

    fun runGitOp(operationName: String, block: () -> Unit) {
        coroutineScope.launch {
            isError = false
            statusMessage = "Running: $operationName..."
            try {
                withContext(Dispatchers.IO) { block() }
                statusMessage = "Success: $operationName"
            } catch (e: Exception) {
                isError = true
                statusMessage = "Error: ${e.localizedMessage}"
                Log.e("GitApp", "Git Error", e)
            }
        }
    }

    LaunchedEffect(localPath) {
        if (isExistingRepo) {
            withContext(Dispatchers.IO) {
                try {
                    // 1. Point JGit explicitly to the .git folder if it exists
                    val gitFolder = File(currentRepoDir, ".git")
                    val repoToOpen = if (gitFolder.exists()) gitFolder else currentRepoDir
                    val git = Git.open(currentRepoDir)

                    // 2. Dynamically check for available remotes instead of assuming "origin"
                    val remotes = git.repository.remoteNames
                    var foundUrl: String? = null

                    if (remotes.contains("origin")) {
                        foundUrl = git.repository.config.getString("remote", "origin", "url")
                    } else if (remotes.isNotEmpty()) {
                        foundUrl = git.repository.config.getString("remote", remotes.first(), "url")
                    }

                    git.close()

                    // 3. Force the UI and SharedPreferences update onto the Main thread
                    withContext(Dispatchers.Main) {
                        if (!foundUrl.isNullOrBlank()) {
                            updatePref("REMOTE_URL", foundUrl) { v -> remoteUrl = v }
                            statusMessage = "Auto-loaded remote from config"
                        } else {
                            // It's a valid local repo, but it hasn't been linked to a remote yet
                            updatePref("REMOTE_URL", "") { v -> remoteUrl = v }
                            statusMessage = "Local repo found, but no remote configured."
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Log.e("GitApp", "Could not read remote URL", e)
                        // Silently fail so we don't spam the user with errors while they type manually
                    }
                }
            }
        }
    }

    val textFieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = SaturatedOrange,
        unfocusedTextColor = SaturatedOrange,
        focusedBorderColor = SaturatedOrange,
        unfocusedBorderColor = Color.Gray,
        focusedLabelColor = SaturatedOrange,
        unfocusedLabelColor = Color.Gray,
        cursorColor = SaturatedOrange
    )
    val context = androidx.compose.ui.platform.LocalContext.current
    var hasStoragePermission by remember {
        mutableStateOf(
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                true
            }
        )
    }

    // A launcher to check if permission was granted when they return from settings
    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            hasStoragePermission = Environment.isExternalStorageManager()
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Git Controller") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .padding(16.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // --- Status Indicator ---
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surface
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Status: $statusMessage",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface
                )
            }

            // --- Configuration Card ---
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { isConfigExpanded = !isConfigExpanded }
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Configuration", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = if (isConfigExpanded) "▲" else "▼",
                            color = SaturatedOrange
                        )
                    }

                    AnimatedVisibility(visible = isConfigExpanded) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {

                            // Local Path Input with Browse Button
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = localPath,
                                    onValueChange = { updatePref("LOCAL_PATH", it) { v -> localPath = v } },
                                    label = { Text("Local Folder Path") },
                                    colors = textFieldColors,
                                    modifier = Modifier.weight(1f),
                                    supportingText = {
                                        if (isExistingRepo) {
                                            Text("✅ Valid .git repository found", color = Color(0xFF4CAF50))
                                        } else {
                                            Text("No valid repo found. Ready to clone/init.", color = Color.Gray)
                                        }
                                    }
                                )

                                Button(
                                    onClick = { showDirPicker = true },
                                    modifier = Modifier.padding(top = 8.dp)
                                ) {
                                    Text("Browse")
                                }
                            }

                            if (showDirPicker) {
                                DirectoryPickerDialog(
                                    initialDir = File(localPath).let { if (it.exists()) it else File(defaultRepoPath).parentFile ?: File("/") },
                                    onDismiss = { showDirPicker = false },
                                    onDirSelected = { selectedFile ->
                                        updatePref("LOCAL_PATH", selectedFile.absolutePath) { v -> localPath = v }
                                        showDirPicker = false
                                    }
                                )
                            }

                            OutlinedTextField(
                                value = remoteUrl,
                                onValueChange = { updatePref("REMOTE_URL", it) { v -> remoteUrl = v } },
                                label = { Text("Remote URL") },
                                colors = textFieldColors,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = username,
                                onValueChange = { updatePref("USERNAME", it) { v -> username = v } },
                                label = { Text("Username") },
                                colors = textFieldColors,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = token,
                                onValueChange = { updatePref("TOKEN", it) { v -> token = v } },
                                label = { Text("Personal Access Token") },
                                visualTransformation = PasswordVisualTransformation(),
                                colors = textFieldColors,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            // --- Actions Card ---
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Actions", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(4.dp))

                    Button(
                        onClick = {
                            runGitOp("Update Remote") {
                                if (!currentRepoDir.exists()) currentRepoDir.mkdirs()
                                val git = if (isExistingRepo) Git.open(currentRepoDir) else Git.init().setDirectory(currentRepoDir).call()
                                val config = git.repository.config
                                config.setString("remote", "origin", "url", remoteUrl)
                                config.save()
                                git.close()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Set / Edit Remote (Init if needed)")
                    }

                    Button(
                        onClick = {
                            runGitOp("Clone") {
                                if (currentRepoDir.exists()) currentRepoDir.deleteRecursively()
                                Git.cloneRepository()
                                    .setURI(remoteUrl)
                                    .setDirectory(currentRepoDir)
                                    .setCredentialsProvider(UsernamePasswordCredentialsProvider(username, token))
                                    .call()
                                    .close()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Clone Repository")
                    }

                    Button(
                        onClick = {
                            runGitOp("Commit & Push") {
                                val git = Git.open(currentRepoDir)
                                git.add().addFilepattern(".").call()
                                git.commit().setMessage("Auto-commit from Android app").call()
                                git.push()
                                    .setCredentialsProvider(UsernamePasswordCredentialsProvider(username, token))
                                    .call()
                                git.close()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = isExistingRepo
                    ) {
                        Text("Commit & Push")
                    }

                    Button(
                        onClick = {
                            runGitOp("Pull") {
                                val git = Git.open(currentRepoDir)
                                git.pull()
                                    .setCredentialsProvider(UsernamePasswordCredentialsProvider(username, token))
                                    .call()
                                git.close()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = isExistingRepo
                    ) {
                        Text("Pull")
                    }
                }
            }
        }
    }
}

@Composable
fun DirectoryPickerDialog(
    initialDir: File,
    onDismiss: () -> Unit,
    onDirSelected: (File) -> Unit
) {
    var currentDir by remember { mutableStateOf(initialDir) }

    val folders = currentDir.listFiles { file -> file.isDirectory && !file.isHidden }
        ?.sortedBy { it.name } ?: emptyList()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("Select Folder", style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = currentDir.absolutePath,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (currentDir.parentFile != null) {
                    TextButton(
                        onClick = { currentDir = currentDir.parentFile!! },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("📁 .. (Go Up)")
                    }
                }

                Divider()

                Box(modifier = Modifier.heightIn(max = 300.dp)) {
                    if (folders.isEmpty()) {
                        Text(
                            "No subfolders",
                            modifier = Modifier.padding(16.dp),
                            color = Color.Gray
                        )
                    } else {
                        androidx.compose.foundation.lazy.LazyColumn {
                            items(folders.size) { index ->
                                val folder = folders[index]
                                TextButton(
                                    onClick = { currentDir = folder },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("📁 ${folder.name}")
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onDirSelected(currentDir) }) {
                Text("Select This Folder")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
