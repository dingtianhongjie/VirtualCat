package com.example.virtualcat // ←元のプロジェクトのパッケージ名のままにする

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

val CAT_VOICES = listOf(
    "にゃー",
    "にゃ",
    "にゃお",
    "しゃー",
    "ごろごろ",
    "みゃー",
    "うにゃ？",
    "にゃーん…",
    "（眠いにゃ）",
    "（おなかすいたにゃ）",
    "（なでてほしいにゃ）",
    "（ひまだにゃ〜）",
    "（日向ぼっこ中…）",
    "（遊んでほしいにゃ！）",
    "（そろそろ寝るにゃ）",
    "（じーっ…）"
)

enum class CatActionType {
    PET,   // なでる
    TREAT, // おやつをあげる
    PLAY   // おもちゃで遊ぶ
}

class MainActivity : ComponentActivity() {
    private var isMiniRoomMode by mutableStateOf(false)
    private var incomingCatMessage by mutableStateOf<String?>(null)
    private var notificationSessionId by mutableLongStateOf(0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createNotificationChannel()
        handleIntent(intent)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (isMiniRoomMode) {
                        CatMiniRoomScreen(
                            incomingMessage = incomingCatMessage,
                            sessionId = notificationSessionId,
                            onCloseScreen = { finish() }
                        )
                    } else {
                        CatSettingsScreen()
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra("OPEN_MINI_ROOM", false) == true) {
            isMiniRoomMode = true
            incomingCatMessage = intent.getStringExtra("CAT_MESSAGE")
            notificationSessionId = System.currentTimeMillis()
        } else {
            isMiniRoomMode = false
            incomingCatMessage = null
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "cat_channel",
                "ネコの鳴き声",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "仮想ネコからの通知"
            }
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}

// 通知タップ時にのみ開く「ふれあい画面（ミニルーム）」
@Composable
fun CatMiniRoomScreen(
    incomingMessage: String?,
    sessionId: Long,
    onCloseScreen: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("cat_prefs", Context.MODE_PRIVATE) }
    val catName = remember { prefs.getString("cat_name", "たま") ?: "たま" }
    val catBitmap = remember { CatIconHelper.loadCurrentCatBitmap(context) }

    var catSpeech by remember(sessionId, incomingMessage) {
        mutableStateOf(incomingMessage ?: "（じーっとこちらを見ている…）")
    }
    var floatingEmoji by remember { mutableStateOf("") }
    var isBouncing by remember { mutableStateOf(false) }

    // 通知1回につき1操作のみ許可するためのフラグ
    var hasInteracted by remember(sessionId) { mutableStateOf(false) }

    // アイコンが弾むアニメーション
    val iconScale by animateFloatAsState(
        targetValue = if (isBouncing) 1.14f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        finishedListener = { isBouncing = false },
        label = "iconBounce"
    )

    // 絵文字がふわっと上に浮かぶアニメーション
    val emojiOffsetY = remember { Animatable(0f) }
    val emojiAlpha = remember { Animatable(0f) }
    val coroutineScope = rememberCoroutineScope()

    fun performCatInteraction(action: CatActionType) {
        if (hasInteracted) return
        hasInteracted = true

        triggerCatVibration(context, action)
        isBouncing = true

        when (action) {
            CatActionType.PET -> {
                floatingEmoji = "❤️"
                catSpeech = listOf(
                    "ごろごろごろ…（目を細めている）",
                    "にゃーん…（気持ちよさそうだ）",
                    "すりすり…（もっと撫でてほしそう）",
                    "（喉を鳴らして甘えている）"
                ).random()
            }
            CatActionType.TREAT -> {
                floatingEmoji = "🐟"
                catSpeech = listOf(
                    "もぐもぐ…おいしいにゃ！",
                    "にゃっ！（しっぽがピンと立った）",
                    "ぺろり…（満足そうに顔を洗っている）",
                    "（夢中で食べている）"
                ).random()
            }
            CatActionType.PLAY -> {
                floatingEmoji = "🧶"
                catSpeech = listOf(
                    "にゃおっ！（勢いよく飛びついた！）",
                    "（おしりを振って狙いを定めている…）",
                    "ダダダッ！（楽しそうに追いかけている）",
                    "（目をまんまるにして夢中だ！）"
                ).random()
            }
        }

        coroutineScope.launch {
            emojiOffsetY.snapTo(0f)
            emojiAlpha.snapTo(1f)
            launch {
                emojiOffsetY.animateTo(-70f, animationSpec = tween(durationMillis = 800))
            }
            launch {
                emojiAlpha.animateTo(0f, animationSpec = tween(durationMillis = 800))
            }

            // リアクション表示の2秒後に自動で画面を閉じる
            delay(2000L)
            onCloseScreen()
        }
    }

    val isActionEnabled = !hasInteracted

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly
    ) {
        // ネコの名前
        Text(
            text = catName,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        // ネコのアイコン＋浮かぶ絵文字エフェクト
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(200.dp)
        ) {
            Image(
                bitmap = catBitmap.asImageBitmap(),
                contentDescription = catName,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(170.dp)
                    .scale(iconScale)
                    .clip(CircleShape)
                    .border(3.dp, MaterialTheme.colorScheme.primary, CircleShape)
                    .clickable(enabled = isActionEnabled) {
                        performCatInteraction(CatActionType.PET)
                    }
            )

            if (floatingEmoji.isNotEmpty()) {
                Text(
                    text = floatingEmoji,
                    fontSize = 36.sp,
                    modifier = Modifier
                        .offset(x = 55.dp, y = (-55 + emojiOffsetY.value).dp)
                        .alpha(emojiAlpha.value)
                )
            }
        }

        // 吹き出し風のメッセージカード
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = catSpeech,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp)
            )
        }

        // 3つのお世話アクションボタン
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = { performCatInteraction(CatActionType.PET) },
                enabled = isActionEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Text("なでる", style = MaterialTheme.typography.titleMedium)
            }

            FilledTonalButton(
                onClick = { performCatInteraction(CatActionType.TREAT) },
                enabled = isActionEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Text("おやつをあげる", style = MaterialTheme.typography.titleMedium)
            }

            OutlinedButton(
                onClick = { performCatInteraction(CatActionType.PLAY) },
                enabled = isActionEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Text("おもちゃで遊ぶ", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

// 3パターン別の振動を発生させる関数
fun triggerCatVibration(context: Context, action: CatActionType) {
    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        manager.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    if (!vibrator.hasVibrator()) return

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val effect = when (action) {
            CatActionType.PET -> VibrationEffect.createWaveform(
                longArrayOf(0, 60, 50, 60, 50, 80, 120, 60, 50, 80),
                intArrayOf(0, 80, 0, 100, 0, 120, 0, 90, 0, 110),
                -1
            )
            CatActionType.TREAT -> VibrationEffect.createWaveform(
                longArrayOf(0, 50, 120, 50, 120, 70),
                intArrayOf(0, 140, 0, 140, 0, 180),
                -1
            )
            CatActionType.PLAY -> VibrationEffect.createWaveform(
                longArrayOf(0, 120, 80, 40, 40, 40, 40, 90),
                intArrayOf(0, 220, 0, 160, 200, 160, 220, 255),
                -1
            )
        }
        vibrator.vibrate(effect)
    } else {
        @Suppress("DEPRECATION")
        vibrator.vibrate(150L)
    }
}

@Composable
fun CatSettingsScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("cat_prefs", Context.MODE_PRIVATE) }

    var catName by remember { mutableStateOf(prefs.getString("cat_name", "たま") ?: "たま") }
    var dailyCount by remember { mutableIntStateOf(prefs.getInt("daily_count", 5)) }
    var sleepStartHour by remember { mutableIntStateOf(prefs.getInt("sleep_start_hour", 23)) }
    var sleepStartMinute by remember { mutableIntStateOf(prefs.getInt("sleep_start_minute", 0)) }
    var sleepEndHour by remember { mutableIntStateOf(prefs.getInt("sleep_end_hour", 7)) }
    var sleepEndMinute by remember { mutableIntStateOf(prefs.getInt("sleep_end_minute", 0)) }

    var iconType by remember { mutableStateOf(prefs.getString("icon_type", "preset") ?: "preset") }
    var selectedPresetId by remember { mutableIntStateOf(prefs.getInt("preset_id", 0)) }
    var currentBitmap by remember { mutableStateOf(CatIconHelper.loadCurrentCatBitmap(context)) }

    var pendingCropBitmap by remember { mutableStateOf<Bitmap?>(null) }

    val presetBitmaps = remember {
        CatPreset.entries.associateWith { CatIconHelper.createPresetBitmap(it) }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val loaded = CatIconHelper.loadOrientedBitmapFromUri(context, uri)
            if (loaded != null) {
                pendingCropBitmap = loaded
            } else {
                Toast.makeText(context, "画像の読み込みに失敗しました", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            sendRandomCatNotification(context, catName.ifBlank { "たま" })
        }
    }

    pendingCropBitmap?.let { bitmapToCrop ->
        ImageCropDialog(
            bitmap = bitmapToCrop,
            onDismiss = { pendingCropBitmap = null },
            onConfirm = { scale, offset, boxSizePx ->
                val saved = CatIconHelper.saveCroppedBitmap(
                    context = context,
                    bitmap = bitmapToCrop,
                    userScale = scale,
                    offsetX = offset.x,
                    offsetY = offset.y,
                    boxSizePx = boxSizePx
                )
                if (saved) {
                    iconType = "custom"
                    prefs.edit().putString("icon_type", "custom").apply()
                    currentBitmap = CatIconHelper.loadCurrentCatBitmap(context)
                    Toast.makeText(context, "写真を設定しました", Toast.LENGTH_SHORT).show()
                }
                pendingCropBitmap = null
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text(
            text = "ネコのお世話設定",
            style = MaterialTheme.typography.headlineSmall
        )

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Image(
                bitmap = currentBitmap.asImageBitmap(),
                contentDescription = "選択中のネコアイコン",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(100.dp)
                    .clip(CircleShape)
                    .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
            )

            Text(
                text = "アイコンを選ぶ",
                style = MaterialTheme.typography.labelLarge
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
            ) {
                CatPreset.entries.forEach { preset ->
                    val isSelected = (iconType == "preset" && selectedPresetId == preset.id)
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable {
                            iconType = "preset"
                            selectedPresetId = preset.id
                            prefs.edit()
                                .putString("icon_type", "preset")
                                .putInt("preset_id", preset.id)
                                .apply()
                            currentBitmap = CatIconHelper.loadCurrentCatBitmap(context)
                        }
                    ) {
                        Image(
                            bitmap = presetBitmaps[preset]!!.asImageBitmap(),
                            contentDescription = preset.label,
                            modifier = Modifier
                                .size(52.dp)
                                .clip(CircleShape)
                                .border(
                                    width = if (isSelected) 3.dp else 1.dp,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else Color.LightGray,
                                    shape = CircleShape
                                )
                        )
                        Text(
                            text = preset.label,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }

            OutlinedButton(
                onClick = {
                    photoPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                }
            ) {
                Text("アルバムから写真を選ぶ")
            }
        }

        HorizontalDivider()

        OutlinedTextField(
            value = catName,
            onValueChange = { catName = it },
            label = { Text("ネコの名前") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Column {
            Text(text = "1日の通知回数：${dailyCount}回")
            Slider(
                value = dailyCount.toFloat(),
                onValueChange = { dailyCount = it.toInt() },
                valueRange = 1f..20f,
                steps = 18
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = "寝ている時間（この間は通知しません）")
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(onClick = {
                    TimePickerDialog(
                        context,
                        { _, hour, minute ->
                            sleepStartHour = hour
                            sleepStartMinute = minute
                        },
                        sleepStartHour,
                        sleepStartMinute,
                        true
                    ).show()
                }) {
                    Text(String.format(Locale.JAPAN, "%02d:%02d", sleepStartHour, sleepStartMinute))
                }

                Text("〜")

                OutlinedButton(onClick = {
                    TimePickerDialog(
                        context,
                        { _, hour, minute ->
                            sleepEndHour = hour
                            sleepEndMinute = minute
                        },
                        sleepEndHour,
                        sleepEndMinute,
                        true
                    ).show()
                }) {
                    Text(String.format(Locale.JAPAN, "%02d:%02d", sleepEndHour, sleepEndMinute))
                }
            }
        }

        HorizontalDivider()

        Button(
            onClick = {
                prefs.edit()
                    .putString("cat_name", catName.ifBlank { "たま" })
                    .putInt("daily_count", dailyCount)
                    .putInt("sleep_start_hour", sleepStartHour)
                    .putInt("sleep_start_minute", sleepStartMinute)
                    .putInt("sleep_end_hour", sleepEndHour)
                    .putInt("sleep_end_minute", sleepEndMinute)
                    .apply()

                CatScheduler.scheduleNotifications(context)

                Toast.makeText(context, "設定を保存し、通知を予約しました", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("設定を保存して通知を予約")
        }

        OutlinedButton(
            onClick = {
                val nameToUse = catName.ifBlank { "たま" }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val hasPermission = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED

                    if (hasPermission) {
                        sendRandomCatNotification(context, nameToUse)
                    } else {
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                } else {
                    sendRandomCatNotification(context, nameToUse)
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("今すぐ鳴かせる")
        }
    }
}

@Composable
fun ImageCropDialog(
    bitmap: Bitmap,
    onDismiss: () -> Unit,
    onConfirm: (scale: Float, offset: Offset, boxSizePx: Float) -> Unit
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var boxSizePx by remember { mutableFloatStateOf(1f) }

    fun clampOffset(targetScale: Float, targetOffset: Offset): Offset {
        if (boxSizePx <= 1f) return Offset.Zero
        val baseScale = maxOf(boxSizePx / bitmap.width.toFloat(), boxSizePx / bitmap.height.toFloat())
        val totalScale = baseScale * targetScale
        val maxX = ((bitmap.width * totalScale) - boxSizePx).coerceAtLeast(0f) / 2f
        val maxY = ((bitmap.height * totalScale) - boxSizePx).coerceAtLeast(0f) / 2f
        return Offset(
            x = targetOffset.x.coerceIn(-maxX, maxX),
            y = targetOffset.y.coerceIn(-maxY, maxY)
        )
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "アイコンの位置とサイズ調整",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "ドラッグで位置移動、2本指またはスライダーで拡大",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Box(
                    modifier = Modifier
                        .size(260.dp)
                        .clipToBounds()
                        .background(Color.Black)
                        .onSizeChanged { size ->
                            boxSizePx = size.width.toFloat()
                        }
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                val newScale = (scale * zoom).coerceIn(1f, 4f)
                                val newOffset = clampOffset(newScale, offset + pan)
                                scale = newScale
                                offset = newOffset
                            }
                        }
                ) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "切り抜き調整画像",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offset.x,
                                translationY = offset.y
                            )
                    )

                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val circlePath = Path().apply {
                            addOval(androidx.compose.ui.geometry.Rect(Offset.Zero, size))
                        }
                        clipPath(circlePath, clipOp = ClipOp.Difference) {
                            drawRect(Color.Black.copy(alpha = 0.55f))
                        }
                        drawCircle(
                            color = Color.White.copy(alpha = 0.85f),
                            style = Stroke(width = 2.dp.toPx())
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("1x", style = MaterialTheme.typography.labelSmall)
                    Slider(
                        value = scale,
                        onValueChange = { newScale ->
                            scale = newScale
                            offset = clampOffset(newScale, offset)
                        },
                        valueRange = 1f..4f,
                        modifier = Modifier.weight(1f)
                    )
                    Text("4x", style = MaterialTheme.typography.labelSmall)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("キャンセル")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = { onConfirm(scale, offset, boxSizePx) }) {
                        Text("決定")
                    }
                }
            }
        }
    }
}

fun sendRandomCatNotification(context: Context, catName: String) {
    val randomVoice = CAT_VOICES.random()
    val catIconBitmap = CatIconHelper.loadCurrentCatBitmap(context)

    val openIntent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra("OPEN_MINI_ROOM", true)
        putExtra("CAT_MESSAGE", randomVoice)
    }
    val contentPendingIntent = PendingIntent.getActivity(
        context,
        System.currentTimeMillis().toInt(),
        openIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val owner = Person.Builder().setName("飼い主").build()
    val catPerson = Person.Builder()
        .setName(catName)
        .setIcon(IconCompat.createWithBitmap(catIconBitmap))
        .build()

    val messagingStyle = NotificationCompat.MessagingStyle(owner)
        .addMessage(randomVoice, System.currentTimeMillis(), catPerson)

    val builder = NotificationCompat.Builder(context, "cat_channel")
        .setSmallIcon(R.drawable.ic_stat_cat)
        .setStyle(messagingStyle)
        .setContentIntent(contentPendingIntent)
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setAutoCancel(true)

    try {
        val notificationId = System.currentTimeMillis().toInt()
        NotificationManagerCompat.from(context).notify(notificationId, builder.build())
    } catch (e: SecurityException) {
        e.printStackTrace()
    }
}