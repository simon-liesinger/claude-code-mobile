package com.claudecode.mobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private val viewModel: ChatViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* results handled by system */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestAllPermissions()

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFFcc785c),
                    background = Color(0xFF0f0f1a),
                    surface = Color(0xFF1a1a2e),
                    onPrimary = Color.White,
                    onBackground = Color.White,
                    onSurface = Color.White
                )
            ) {
                if (viewModel.isSetup.value) {
                    ChatScreen(viewModel)
                } else {
                    SetupScreen(viewModel)
                }
            }
        }
    }

    private fun requestAllPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (_: Exception) {
                    try {
                        startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    } catch (_: Exception) { }
                }
            }
        }

        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.VIBRATE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.addAll(listOf(
                Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO
            ))
        } else {
            permissions.addAll(listOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ))
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.addAll(listOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            ))
        }

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }
}

@Composable
fun SetupScreen(viewModel: ChatViewModel) {
    var showApiKeyInput by remember { mutableStateOf(false) }
    var apiKey by remember { mutableStateOf("") }
    var authCode by remember { mutableStateOf("") }
    val context = LocalContext.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0f0f1a)),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .padding(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1a1a2e)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    ">_ Claude Code",
                    color = Color(0xFFcc785c),
                    fontSize = 26.sp,
                    fontFamily = FontFamily.Monospace
                )

                Text(
                    "Full system access on your phone.",
                    color = Color(0xFF888888),
                    fontSize = 14.sp
                )

                // Error display
                viewModel.authError.value?.let { error ->
                    Text(
                        error,
                        color = Color(0xFFff6666),
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )
                }

                if (!viewModel.oAuthPending.value) {
                    // Primary: OAuth login
                    Button(
                        onClick = {
                            val url = viewModel.getOAuthUrl()
                            viewModel.oAuthPending.value = true
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            context.startActivity(intent)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFcc785c)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Log in with Claude", color = Color.White, fontSize = 16.sp)
                    }

                    // Secondary: API key
                    Text(
                        if (showApiKeyInput) "Hide API key input" else "Or use an API key instead",
                        color = Color(0xFF666666),
                        fontSize = 12.sp,
                        modifier = Modifier.clickable { showApiKeyInput = !showApiKeyInput }
                    )

                    AnimatedVisibility(visible = showApiKeyInput) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = apiKey,
                                onValueChange = { apiKey = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("API Key") },
                                visualTransformation = PasswordVisualTransformation(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = Color(0xFFcc785c),
                                    unfocusedBorderColor = Color(0xFF333355),
                                    focusedLabelColor = Color(0xFFcc785c),
                                    unfocusedLabelColor = Color(0xFF666666),
                                    cursorColor = Color(0xFFcc785c)
                                ),
                                singleLine = true,
                                placeholder = { Text("sk-ant-...", color = Color(0xFF444444)) }
                            )

                            Button(
                                onClick = { viewModel.saveApiKey(apiKey.trim()) },
                                enabled = apiKey.trim().startsWith("sk-ant-"),
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF555555),
                                    disabledContainerColor = Color(0xFF333355)
                                ),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Connect with API Key", color = Color.White)
                            }
                        }
                    }
                } else {
                    // OAuth code entry
                    Text(
                        "Paste the authorization code or the full callback URL:",
                        color = Color(0xFFaaaaaa),
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )

                    OutlinedTextField(
                        value = authCode,
                        onValueChange = { authCode = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Authorization Code") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFFcc785c),
                            unfocusedBorderColor = Color(0xFF333355),
                            focusedLabelColor = Color(0xFFcc785c),
                            unfocusedLabelColor = Color(0xFF666666),
                            cursorColor = Color(0xFFcc785c)
                        ),
                        singleLine = true,
                        placeholder = { Text("Code or URL...", color = Color(0xFF444444)) }
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                viewModel.oAuthPending.value = false
                                viewModel.authError.value = null
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = Color(0xFF888888)
                            )
                        ) {
                            Text("Back")
                        }

                        Button(
                            onClick = { viewModel.completeOAuth(authCode.trim()) },
                            enabled = authCode.trim().length > 5 && !viewModel.isLoading.value,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFcc785c),
                                disabledContainerColor = Color(0xFF333355)
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            if (viewModel.isLoading.value) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = Color.White
                                )
                            } else {
                                Text("Connect", color = Color.White)
                            }
                        }
                    }
                }

                Text(
                    "Credentials are stored locally on this device only.",
                    color = Color(0xFF444444),
                    fontSize = 11.sp
                )
            }
        }
    }
}
