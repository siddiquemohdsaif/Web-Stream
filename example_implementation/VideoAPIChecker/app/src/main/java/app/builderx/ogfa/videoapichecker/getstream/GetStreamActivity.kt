package app.builderx.ogfa.videoapichecker.getstream

import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.builderx.ogfa.videoapichecker.Util.CallAutoCutConfig
import app.builderx.ogfa.videoapichecker.Util.ComponentManager
import io.getstream.video.android.compose.permission.LaunchCallPermissions
import io.getstream.video.android.compose.theme.VideoTheme
import io.getstream.video.android.compose.ui.components.call.renderer.ParticipantVideo
import io.getstream.video.android.core.GEO
import io.getstream.video.android.core.ParticipantState
import io.getstream.video.android.core.RealtimeConnection
import io.getstream.video.android.core.StreamVideo
import io.getstream.video.android.core.StreamVideoBuilder
import io.getstream.video.android.core.model.PreferredVideoResolution
import io.getstream.video.android.model.User
import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class GetStreamActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MaterialTheme {
                GetStreamRoot(
                    onClose = { finish() },
                    showToast = { message ->
                        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
                    },
                )
            }
        }
    }

    override fun onDestroy() {
        StreamVideo.removeClient()
        super.onDestroy()
    }
}

private const val DEFAULT_STREAM_API_KEY = "sjwc6at8rr53"
private const val DEFAULT_STREAM_API_SECRET = "m2hqh832tyfap9rvadkrsum3p7kdpsv9sfr6v2429ny7hqdf5hryynqf4q65ekqd"
private const val TARGET_VIDEO_FPS = 15
private val TARGET_REMOTE_VIDEO_RESOLUTION = PreferredVideoResolution(width = 1280, height = 720)

private data class StreamCredentials(
    val apiKey: String,
    val userToken: String,
    val userId: String,
    val userName: String,
    val callId: String,
)

@Composable
private fun GetStreamRoot(
    onClose: () -> Unit,
    showToast: (String) -> Unit,
) {
    var credentials by remember { mutableStateOf<StreamCredentials?>(null) }

    if (credentials == null) {
        StreamSetupScreen(
            onStartCall = { credentials = it },
        )
    } else {
        StreamCallScreen(
            credentials = credentials!!,
            onClose = onClose,
            showToast = showToast,
        )
    }
}

@Composable
private fun StreamSetupScreen(
    onStartCall: (StreamCredentials) -> Unit,
) {
    var apiKey by remember { mutableStateOf(DEFAULT_STREAM_API_KEY) }
    var apiSecret by remember { mutableStateOf(DEFAULT_STREAM_API_SECRET) }
    var userToken by remember { mutableStateOf("") }
    var userId by remember { mutableStateOf("android-user") }
    var userName by remember { mutableStateOf("Android User") }
    var callId by remember { mutableStateOf("test-call") }
    var error by remember { mutableStateOf<String?>(null) }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF050606)),
        color = Color(0xFF050606),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Scrims()
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Waiting for remote video",
                    color = Color(0xFFD7FFFFFF),
                    fontSize = 18.sp,
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 24.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xD91B211F))
                    .padding(16.dp),
            ) {
                Text(
                    text = "GetStream video call",
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                )

                StreamTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = "API key",
                )
                StreamTextField(
                    value = userToken,
                    onValueChange = { userToken = it },
                    label = "User token (optional)",
                    visualTransformation = PasswordVisualTransformation(),
                )
                StreamTextField(
                    value = apiSecret,
                    onValueChange = { apiSecret = it },
                    label = "API secret (dev only)",
                    visualTransformation = PasswordVisualTransformation(),
                )
                StreamTextField(
                    value = userId,
                    onValueChange = { userId = it },
                    label = "User ID",
                )
                StreamTextField(
                    value = userName,
                    onValueChange = { userName = it },
                    label = "Display name",
                )
                StreamTextField(
                    value = callId,
                    onValueChange = { callId = it },
                    label = "Call ID",
                )

                if (error != null) {
                    Text(
                        text = error!!,
                        color = Color(0xFFFFA4A4),
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }

                Button(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(66.dp)
                        .padding(top = 14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF25D366)),
                    shape = RoundedCornerShape(4.dp),
                    onClick = {
                        val trimmedApiKey = apiKey.trim()
                        val trimmedApiSecret = apiSecret.trim()
                        val trimmedUserToken = userToken.trim()
                        val trimmedUserId = userId.trim()
                        val trimmedUserName = userName.trim()
                        val trimmedCallId = callId.trim()

                        error = when {
                            trimmedApiKey.isEmpty() -> "API key is required."
                            trimmedUserToken.isEmpty() && trimmedApiSecret.isEmpty() ->
                                "User token or dev API secret is required."
                            trimmedUserId.isEmpty() -> "User ID is required."
                            trimmedCallId.isEmpty() -> "Call ID is required."
                            else -> null
                        }

                        if (error == null) {
                            val token = trimmedUserToken.ifEmpty {
                                generateStreamUserToken(trimmedApiSecret, trimmedUserId)
                            }
                            onStartCall(
                                StreamCredentials(
                                    apiKey = trimmedApiKey,
                                    userToken = token,
                                    userId = trimmedUserId,
                                    userName = trimmedUserName.ifEmpty { trimmedUserId },
                                    callId = trimmedCallId,
                                ),
                            )
                        }
                    },
                ) {
                    Text(
                        text = "Start video call",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun StreamTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation =
        androidx.compose.ui.text.input.VisualTransformation.None,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
        label = { Text(label) },
        singleLine = true,
        visualTransformation = visualTransformation,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            focusedLabelColor = Color(0xFFE8FFFFFF),
            unfocusedLabelColor = Color(0xFFB3FFFFFF),
            focusedBorderColor = Color(0xAAFFFFFF),
            unfocusedBorderColor = Color(0x66FFFFFF),
            cursorColor = Color.White,
        ),
    )
}

private fun generateStreamUserToken(
    apiSecret: String,
    userId: String,
    validitySeconds: Long = 24 * 60 * 60,
): String {
    val issuedAtSeconds = System.currentTimeMillis() / 1000
    val expiresAtSeconds = issuedAtSeconds + validitySeconds
    val header = """{"alg":"HS256","typ":"JWT"}"""
    val payload = """{"user_id":"${userId.escapeJson()}","iat":$issuedAtSeconds,"exp":$expiresAtSeconds}"""
    val unsignedToken = "${base64Url(header)}.${base64Url(payload)}"
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(apiSecret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
    val signature = Base64.encodeToString(
        mac.doFinal(unsignedToken.toByteArray(StandardCharsets.UTF_8)),
        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
    )
    return "$unsignedToken.$signature"
}

private fun base64Url(value: String): String =
    Base64.encodeToString(
        value.toByteArray(StandardCharsets.UTF_8),
        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
    )

private fun String.escapeJson(): String =
    buildString {
        for (char in this@escapeJson) {
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
    }

@Composable
private fun StreamCallScreen(
    credentials: StreamCredentials,
    onClose: () -> Unit,
    showToast: (String) -> Unit,
) {
    val appContext = LocalContext.current.applicationContext
    val client = remember(credentials) {
        StreamVideo.removeClient()
        StreamVideoBuilder(
            context = appContext,
            apiKey = credentials.apiKey,
            geo = GEO.GlobalEdgeNetwork,
            user = User(
                id = credentials.userId,
                name = credentials.userName,
            ),
            token = credentials.userToken,
        ).build()
    }
    val call = remember(client, credentials.callId) {
        client.call(type = "default", id = credentials.callId)
    }
    val componentSession = remember(credentials) {
        ComponentManager.startGetStreamComponentSession(appContext)
    }
    var joinStarted by remember(credentials) { mutableStateOf(false) }
    var joinError by remember(credentials) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    DisposableEffect(componentSession) {
        onDispose {
            ComponentManager.finishComponentSession(componentSession)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LaunchCallPermissions(
            call = call,
            onAllPermissionsGranted = {
                if (!joinStarted) {
                    joinStarted = true
                    joinError = null
                    val result = call.join(create = true)
                    result.onSuccess {
                        call.setPreferredIncomingVideoResolution(TARGET_REMOTE_VIDEO_RESOLUTION)
                    }
                    result.onError { error ->
                        joinStarted = false
                        joinError = error.message
                        showToast("GetStream auth/join failed: ${error.message}")
                    }
                }
            },
        )

        VideoTheme {
            MatchingCallScreen(
                call = call,
                componentSession = componentSession,
                joinError = joinError,
                onEnd = {
                    scope.launch {
                        call.leave()
                        onClose()
                    }
                },
                onMute = { enabled ->
                    scope.launch { call.microphone.setEnabled(enabled) }
                },
                onFlip = {
                    scope.launch { call.camera.flip() }
                },
            )
        }
    }
}

@Composable
private fun MatchingCallScreen(
    call: io.getstream.video.android.core.Call,
    componentSession: ComponentManager.CallSession?,
    joinError: String?,
    onEnd: () -> Unit,
    onMute: (Boolean) -> Unit,
    onFlip: () -> Unit,
) {
    val rootView = LocalView.current
    val connection by call.state.connection.collectAsState()
    val me by call.state.me.collectAsState()
    val remoteParticipants by call.state.remoteParticipants.collectAsState()
    val microphoneEnabled by call.microphone.isEnabled.collectAsState()
    val remoteParticipant = remoteParticipants.firstOrNull()
    val remoteVideoConnected = remoteVideoConnected(remoteParticipant)
    var connectedAtMs by remember { mutableStateOf<Long?>(null) }
    var connectedSeconds by remember { mutableStateOf(0L) }
    var autoCutTriggered by remember { mutableStateOf(false) }
    val status = when {
        joinError != null -> "Join failed"
        connection == RealtimeConnection.Connected && remoteParticipant != null ->
            "${remoteParticipants.size} remote participant${if (remoteParticipants.size == 1) "" else "s"}"
        connection == RealtimeConnection.Connected -> "Joined ${call.id}"
        else -> "Joining..."
    }
    LaunchedEffect(remoteVideoConnected) {
        if (!remoteVideoConnected) {
            componentSession?.stopRemoteVideoStorage()
            connectedAtMs = null
            connectedSeconds = 0L
            autoCutTriggered = false
            return@LaunchedEffect
        }

        delay(300L)
        componentSession?.startRemoteVideoStorage(findBestVideoView(rootView) ?: rootView, TARGET_VIDEO_FPS)
        val startedAt = connectedAtMs ?: SystemClock.elapsedRealtime()
        connectedAtMs = startedAt
        while (true) {
            connectedSeconds = ((SystemClock.elapsedRealtime() - startedAt) / 1000L).coerceAtLeast(0L)
            if (
                CallAutoCutConfig.isEnabled() &&
                connectedSeconds >= CallAutoCutConfig.AUTO_CUT_SECONDS &&
                !autoCutTriggered
            ) {
                autoCutTriggered = true
                onEnd()
                return@LaunchedEffect
            }
            delay(1000L)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF050606)),
    ) {
        if (remoteParticipant != null) {
            ParticipantVideo(
                modifier = Modifier.fillMaxSize(),
                call = call,
                participant = remoteParticipant,
            )
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 32.dp),
                ) {
                    Text(
                        text = if (joinError == null) "Waiting for remote video" else "GetStream join failed",
                        color = if (joinError == null) Color(0xFFD7FFFFFF) else Color(0xFFFFA4A4),
                        fontSize = 18.sp,
                        fontWeight = if (joinError == null) FontWeight.Normal else FontWeight.Bold,
                    )
                    if (joinError != null) {
                        Text(
                            text = joinError,
                            color = Color(0xFFD9FFFFFF),
                            fontSize = 14.sp,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }

        Scrims()

        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(start = 20.dp, top = 28.dp, end = 158.dp),
        ) {
            Text(
                text = "GetStream",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = status,
                color = Color(0xFFD9FFFFFF),
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                text = "Connected: $connectedSeconds sec",
                color = Color(0xFFD9FFFFFF),
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 92.dp, end = 18.dp)
                .size(width = 118.dp, height = 168.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF111614))
                .border(2.dp, Color(0x66FFFFFF), RoundedCornerShape(8.dp))
                .padding(2.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (me != null) {
                ParticipantVideo(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(6.dp)),
                    call = call,
                    participant = me!!,
                )
            } else {
                Text(
                    text = "You",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 18.dp, end = 18.dp, bottom = 28.dp, top = 18.dp),
        ) {
            CallButton(
                text = if (microphoneEnabled) "Mute" else "Unmute",
                color = Color(0xFF2B3431),
                modifier = Modifier.weight(1f),
                onClick = { onMute(!microphoneEnabled) },
            )
            Spacer(modifier = Modifier.width(10.dp))
            CallButton(
                text = "Flip",
                color = Color(0xFF2B3431),
                modifier = Modifier.weight(1f),
                onClick = onFlip,
            )
            Spacer(modifier = Modifier.width(10.dp))
            CallButton(
                text = "End",
                color = Color(0xFFE53935),
                modifier = Modifier.weight(1f),
                bold = true,
                onClick = onEnd,
            )
        }
    }
}

@Composable
private fun remoteVideoConnected(participant: ParticipantState?): Boolean {
    if (participant == null) {
        return false
    }
    val videoEnabled by participant.videoEnabled.collectAsState()
    val videoTrack by participant.videoTrack.collectAsState()
    return videoEnabled && videoTrack != null
}

private fun findBestVideoView(rootView: View): View? {
    val candidates = mutableListOf<View>()
    collectVideoViews(rootView, candidates)
    return candidates
        .filter { it.width > 0 && it.height > 0 && it.isShown }
        .maxByOrNull { it.width * it.height }
}

private fun collectVideoViews(view: View, candidates: MutableList<View>) {
    if (view is SurfaceView || view is TextureView) {
        candidates += view
    }

    if (view is ViewGroup) {
        for (index in 0 until view.childCount) {
            collectVideoViews(view.getChildAt(index), candidates)
        }
    }
}

@Composable
private fun CallButton(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    bold: Boolean = false,
    onClick: () -> Unit,
) {
    Button(
        modifier = modifier.height(54.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color),
        shape = RoundedCornerShape(4.dp),
        onClick = onClick,
    ) {
        Text(
            text = text,
            color = Color.White,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
private fun BoxScope.Scrims() {
    Box(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .height(190.dp)
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xD9000000), Color.Transparent),
                ),
            ),
    )
    Box(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .height(230.dp)
            .background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, Color(0xD9000000)),
                ),
            ),
    )
}
