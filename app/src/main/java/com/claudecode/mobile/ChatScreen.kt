package com.claudecode.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private val BgDark = Color(0xFF0f0f1a)
private val BgMedium = Color(0xFF1a1a2e)
private val BgLight = Color(0xFF1e1e35)
private val AccentOrange = Color(0xFFcc785c)
private val TextDim = Color(0xFF888888)
private val ToolResultGreen = Color(0xFF88cc88)
private val UserBubble = Color(0xFF2a2a55)
private val BorderDim = Color(0xFF333355)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: ChatViewModel) {
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(viewModel.messages.size) {
        if (viewModel.messages.isNotEmpty()) {
            listState.animateScrollToItem(viewModel.messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Claude Code", fontFamily = FontFamily.Monospace, fontSize = 18.sp)
                        val (inp, out) = viewModel.tokenCount.value
                        if (inp > 0) {
                            Text(
                                "${inp + out} tokens",
                                fontSize = 11.sp,
                                color = TextDim
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.clearHistory() }) {
                        Icon(Icons.Default.DeleteSweep, "Clear", tint = TextDim)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BgMedium,
                    titleContentColor = Color.White
                )
            )
        },
        containerColor = BgDark
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Messages
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(viewModel.messages, key = { System.identityHashCode(it) }) { message ->
                    MessageBubble(message)
                }

                if (viewModel.isLoading.value) {
                    item {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = AccentOrange
                            )
                            Text("Working...", color = TextDim, fontSize = 13.sp)
                        }
                    }
                }
            }

            // Input bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BgMedium)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message Claude...", fontSize = 14.sp) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = AccentOrange,
                        unfocusedBorderColor = BorderDim,
                        cursorColor = AccentOrange,
                        focusedPlaceholderColor = Color(0xFF555555),
                        unfocusedPlaceholderColor = Color(0xFF555555)
                    ),
                    maxLines = 6,
                    textStyle = LocalTextStyle.current.copy(fontSize = 14.sp)
                )

                Spacer(modifier = Modifier.width(6.dp))

                IconButton(
                    onClick = {
                        if (inputText.isNotBlank() && !viewModel.isLoading.value) {
                            val msg = inputText.trim()
                            inputText = ""
                            viewModel.sendMessage(msg)
                        }
                    },
                    enabled = inputText.isNotBlank() && !viewModel.isLoading.value,
                    modifier = Modifier
                        .padding(bottom = 4.dp)
                        .size(44.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = if (inputText.isNotBlank() && !viewModel.isLoading.value)
                            AccentOrange else Color(0xFF444444)
                    )
                }
            }
        }
    }
}

@Composable
fun MessageBubble(message: ChatMessage) {
    val isUser = message.role == "user"
    val isToolResult = message.isToolResult
    val isToolCall = message.toolName != null && !isToolResult

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        when {
            isToolCall -> {
                // Tool invocation indicator
                Row(
                    modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 1.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = message.text,
                        color = AccentOrange,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 2
                    )
                }
            }
            isToolResult -> {
                // Tool result output
                SelectionContainer {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF0a0a12))
                            .padding(8.dp)
                            .horizontalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = if (message.text.length > 3000) {
                                message.text.take(3000) + "\n... [display truncated]"
                            } else message.text,
                            color = ToolResultGreen,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 14.sp
                        )
                    }
                }
            }
            isUser -> {
                // User message
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.88f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(UserBubble)
                        .padding(12.dp)
                ) {
                    SelectionContainer {
                        Text(
                            text = message.text,
                            color = Color.White,
                            fontSize = 14.sp,
                            lineHeight = 20.sp
                        )
                    }
                }
            }
            else -> {
                // Assistant message
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.95f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(BgLight)
                        .padding(12.dp)
                ) {
                    SelectionContainer {
                        Text(
                            text = message.text,
                            color = if (message.isError) Color(0xFFff6666) else Color.White,
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            fontWeight = if (message.isError) FontWeight.Medium else FontWeight.Normal
                        )
                    }
                }
            }
        }
    }
}
