package com.example.sampleapp

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
        
        // Define the local directory where the repo will live
        val repoDir = File(filesDir, "my_repo")
        
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    GitControlScreen(repoDir)
                }
            }
        }
    }
}

@Composable
fun GitControlScreen(repoDir: File) {
    val coroutineScope = rememberCoroutineScope()
    
    var remoteUrl by remember { mutableStateOf("https://github.com/username/repo.git") }
    var username by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") } // Use a Personal Access Token, not a password
    var statusMessage by remember { mutableStateOf("Ready") }

    // Helper to run Git operations on a background thread
    fun runGitOp(operationName: String, block: () -> Unit) {
        coroutineScope.launch {
            statusMessage = "Running: $operationName..."
            try {
                withContext(Dispatchers.IO) { block() }
                statusMessage = "Success: $operationName"
            } catch (e: Exception) {
                statusMessage = "Error: ${e.localizedMessage}"
                Log.e("GitApp", "Git Error", e)
            }
        }
    }

    Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
        Text("Simple Git Controller", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(16.dp))

        // --- Configuration Inputs ---
        OutlinedTextField(
            value = remoteUrl,
            onValueChange = { remoteUrl = it },
            label = { Text("Remote URL") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Username") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text("Personal Access Token") },
            modifier = Modifier.fillMaxWidth()
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        Text("Status: $statusMessage", color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(16.dp))

        // --- Git Action Buttons ---

        // 1. Manage Remote (Create/Edit)
        Button(
            onClick = {
                runGitOp("Update Remote") {
                    val git = Git.open(repoDir)
                    val config = git.repository.config
                    config.setString("remote", "origin", "url", remoteUrl)
                    config.save()
                    git.close()
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Set / Edit Remote")
        }

        // 2. Clone
        Button(
            onClick = {
                runGitOp("Clone") {
                    if (repoDir.exists()) repoDir.deleteRecursively()
                    Git.cloneRepository()
                        .setURI(remoteUrl)
                        .setDirectory(repoDir)
                        .setCredentialsProvider(UsernamePasswordCredentialsProvider(username, token))
                        .call()
                        .close()
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Clone Repository")
        }

        // 3. Commit + Push
        Button(
            onClick = {
                runGitOp("Commit & Push") {
                    val git = Git.open(repoDir)
                    // Add all changes
                    git.add().addFilepattern(".").call()
                    
                    // Standard message commit
                    git.commit().setMessage("Auto-commit from Android app").call()
                    
                    // Push
                    git.push()
                        .setCredentialsProvider(UsernamePasswordCredentialsProvider(username, token))
                        .call()
                        
                    git.close()
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Commit & Push")
        }

        // 4. Pull
        Button(
            onClick = {
                runGitOp("Pull") {
                    val git = Git.open(repoDir)
                    git.pull()
                        .setCredentialsProvider(UsernamePasswordCredentialsProvider(username, token))
                        .call()
                    git.close()
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Pull")
        }
        
        // Extra: Delete Remote (Clear it out)
        Button(
            onClick = {
                runGitOp("Delete Remote") {
                    val git = Git.open(repoDir)
                    val config = git.repository.config
                    config.unset("remote", "origin", "url")
                    config.save()
                    git.close()
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        ) {
            Text("Delete Remote Configuration")
        }
    }
}