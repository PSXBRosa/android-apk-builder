package com.example.sampleapp

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Environment
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Use SharedPreferences for data permanence
        val sharedPreferences = getSharedPreferences("GitAppPrefs", Context.MODE_PRIVATE)
        
        // Default safe directory that doesn't require complex SAF permissions for JGit
        val defaultDir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)?.absolutePath ?: filesDir.absolutePath
        val defaultRepoPath = "$defaultDir/my_repo"

        setContent {
            MaterialTheme {
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
    
    // State backed by SharedPreferences
    var localPath by remember { mutableStateOf(prefs.getString("LOCAL_PATH", defaultRepoPath) ?: defaultRepoPath) }
    var remoteUrl by remember { mutableStateOf(prefs.getString("REMOTE_URL", "https://github.com/username/repo.git") ?: "") }
    var username by remember { mutableStateOf(prefs.getString("USERNAME", "") ?: "") }
    var token by remember { mutableStateOf(prefs.getString("TOKEN", "") ?: "") } 
    
    var statusMessage by remember { mutableStateOf("Ready") }
    var isError by remember { mutableStateOf(false) }

    // Dynamically check if the current path points to an existing Git repository
    val currentRepoDir = File(localPath)
    val isExistingRepo = File(currentRepoDir, ".git").exists()

    // Helper to run Git operations on a background thread
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

    // Helper to update state and save to SharedPreferences simultaneously
    fun updatePref(key: String, value: String, updater: (String) -> Unit) {
        updater(value)
        prefs.edit().putString(key, value).apply()
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
                .verticalScroll(rememberScrollState()), // Allows scrolling when keyboard is open
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // --- Status Indicator ---
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Status: $statusMessage",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // --- Configuration Card ---
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Configuration", style = MaterialTheme.typography.titleMedium)
                    
                    OutlinedTextField(
                        value = localPath,
                        onValueChange = { updatePref("LOCAL_PATH", it) { v -> localPath = v } },
                        label = { Text("Local Folder Path") },
                        modifier = Modifier.fillMaxWidth(),
                        supportingText = {
                            if (isExistingRepo) {
                                Text("✅ Existing .git repository found", color = Color(0xFF2E7D32)) // Dark Green
                            } else {
                                Text("No existing repo found. Ready to clone/init.")
                            }
                        }
                    )
                    OutlinedTextField(
                        value = remoteUrl,
                        onValueChange = { updatePref("REMOTE_URL", it) { v -> remoteUrl = v } },
                        label = { Text("Remote URL") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = username,
                        onValueChange = { updatePref("USERNAME", it) { v -> username = v } },
                        label = { Text("Username") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = token,
                        onValueChange = { updatePref("TOKEN", it) { v -> token = v } },
                        label = { Text("Personal Access Token") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // --- Actions Card ---
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Actions", style = MaterialTheme.typography.titleMedium)
                    
                    Button(
                        onClick = {
                            runGitOp("Update Remote") {
                                // Will create the repo directory and init if it doesn't exist
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
                    
                    Button(
                        onClick = {
                            runGitOp("Delete Remote") {
                                val git = Git.open(currentRepoDir)
                                val config = git.repository.config
                                config.unset("remote", "origin", "url")
                                config.save()
                                git.close()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        enabled = isExistingRepo
                    ) {
                        Text("Delete Remote Configuration")
                    }
                }
            }
        }
    }
}