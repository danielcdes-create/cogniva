package br.com.concursoprep.app.ui.screens

import android.media.MediaPlayer
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import br.com.concursoprep.app.domain.missions.MissionCardView
import br.com.concursoprep.app.domain.missions.MissionDetailView
import br.com.concursoprep.app.domain.missions.MissionSceneView
import br.com.concursoprep.app.domain.missions.MissionsCatalogLoader
import br.com.concursoprep.app.domain.missions.MissionsCatalogView
import br.com.concursoprep.app.domain.missions.MissionsFinanceMath
import br.com.concursoprep.app.domain.missions.NativeVisualSpec
import br.com.concursoprep.app.ui.theme.CognivaBrandHeader
import kotlinx.coroutines.delay
import java.io.File
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MissionsCatalogScreen(
    catalog: MissionsCatalogView?,
    isBusy: Boolean,
    onOpenMission: (missionId: String, resume: Boolean) -> Unit,
    onRefresh: () -> Unit = {},
) {
    val effectiveCatalog = remember(catalog) {
        catalog ?: MissionsCatalogLoader.buildCatalogView(
            MissionsCatalogLoader.loadBundled(),
            emptyMap(),
        )
    }

    LaunchedEffect(Unit) {
        onRefresh()
    }

    Surface(
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(key = "missions_header") {
                CognivaBrandHeader(
                    ecosystemProduct = "Cogniva · Missões",
                    subtitle = "Narrativas profissionais, decisões ativas e vínculo curricular",
                )
            }

            items(effectiveCatalog.cards, key = { it.missionId }) { card ->
                MissionCatalogCard(
                    card = card,
                    isBusy = isBusy,
                    onOpenMission = onOpenMission,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MissionCatalogCard(
    card: MissionCardView,
    isBusy: Boolean,
    onOpenMission: (String, Boolean) -> Unit,
) {
    val prog = card.narrativeProgress
    val perf = card.interactionPerformance
    val statusContainer = when (prog.status) {
        "COMPLETED" -> MaterialTheme.colorScheme.tertiaryContainer
        "IN_PROGRESS" -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val statusContent = when (prog.status) {
        "COMPLETED" -> MaterialTheme.colorScheme.onTertiaryContainer
        "IN_PROGRESS" -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val ctaLabel = when (prog.status) {
        "IN_PROGRESS" -> "Continuar missão (Cena ${prog.currentSceneOrder} de ${card.scenesCount})"
        "COMPLETED" -> "Revisitar missão"
        else -> "Iniciar missão"
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "${card.institution} · ${card.role}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = card.careerHeader,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
            )
            Text(
                text = "${card.seasonTitle} · Disciplina: ${card.disciplineName} · Tema: ${card.seasonTheme}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = card.fullTitle,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = statusContainer,
                    contentColor = statusContent,
                ) {
                    Text(
                        text = prog.statusLabelPt,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            }

            Text(
                text = card.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                text = "Duração real: ${card.duration.displayLabel} · ${card.scenesCount} cenas",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                card.workedConceptLabels.forEach { label ->
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
            }

            LinearProgressIndicator(
                progress = { (prog.progressPct / 100.0).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(4.dp)),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Progresso narrativo: ${prog.progressPct.toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Decisões: ${perf.answeredDecisionsCount}/${perf.totalDecisionsCount} (formativas)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Button(
                onClick = { onOpenMission(card.missionId, prog.status == "IN_PROGRESS") },
                enabled = !isBusy,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    text = ctaLabel,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MissionPlayerScreen(
    detail: MissionDetailView,
    isBusy: Boolean,
    onExitMission: (sceneId: String?, positionMs: Long?) -> Unit,
    onAdvanceScene: (completedSceneId: String, nextSceneId: String?, viaTranscript: Boolean) -> Unit,
    onJumpToScene: (targetSceneId: String) -> Unit,
    onSubmitDecision: (sceneId: String, interactionId: String, optionId: String, timeSpentMs: Long, viaTranscript: Boolean) -> Unit,
    onRepeatMission: () -> Unit,
    onStartRelatedPractice: () -> Unit,
    onResolveVideoFile: suspend (assetId: String, sha256: String?) -> File?,
    onPreloadNextVideo: (assetId: String?, sha256: String?) -> Unit,
) {
    val context = LocalContext.current
    var showConclusion by rememberSaveable(detail.missionId) {
        mutableStateOf(false)
    }
    var activeSceneId by rememberSaveable(detail.missionId, detail.narrativeProgress.currentSceneId) {
        mutableStateOf(
            detail.narrativeProgress.currentSceneId.ifBlank {
                detail.scenes.firstOrNull()?.sceneId.orEmpty()
            }
        )
    }
    val scene = detail.scenes.find { it.sceneId == activeSceneId } ?: detail.scenes.firstOrNull()
    var currentPosMs by remember(activeSceneId) {
        mutableLongStateOf(detail.narrativeProgress.scenePositionsMs[activeSceneId] ?: 0L)
    }

    BackHandler {
        onExitMission(scene?.sceneId, currentPosMs)
    }

    Surface(
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        if (showConclusion || scene == null) {
            MissionConclusionSection(
                detail = detail,
                isBusy = isBusy,
                onRevisitScene = { targetSid ->
                    activeSceneId = targetSid
                    showConclusion = false
                    onJumpToScene(targetSid)
                },
                onStartRelatedPractice = onStartRelatedPractice,
                onRepeatMission = {
                    showConclusion = false
                    onRepeatMission()
                },
                onBackToCatalog = { onExitMission(scene?.sceneId, currentPosMs) },
            )
            return@Surface
        }

        var transcriptOpen by rememberSaveable(scene.sceneId) { mutableStateOf(false) }
        var captionsEnabled by rememberSaveable { mutableStateOf(true) }
        var autoAdvanceEnabled by rememberSaveable { mutableStateOf(true) }
        var userIntervened by remember(scene.sceneId) { mutableStateOf(false) }
        var sceneStartedAtMs by remember(scene.sceneId) { mutableLongStateOf(System.currentTimeMillis()) }

        var isVideoLoading by remember(scene.sceneId) { mutableStateOf(scene.availableOnServer) }
        var localVideoFile by remember(scene.sceneId) { mutableStateOf<File?>(null) }
        var videoErrored by remember(scene.sceneId) { mutableStateOf(!scene.availableOnServer) }
        var isPlaying by remember(scene.sceneId) { mutableStateOf(false) }
        var isMuted by rememberSaveable { mutableStateOf(false) }
        var videoViewRef by remember(scene.sceneId) { mutableStateOf<VideoView?>(null) }
        var mediaPlayerRef by remember(scene.sceneId) { mutableStateOf<MediaPlayer?>(null) }
        var retryNonce by remember(scene.sceneId) { mutableLongStateOf(0L) }

        LaunchedEffect(scene.sceneId, retryNonce) {
            sceneStartedAtMs = System.currentTimeMillis()
            if (!scene.availableOnServer) {
                isVideoLoading = false
                videoErrored = true
                return@LaunchedEffect
            }
            isVideoLoading = true
            videoErrored = false
            val file = onResolveVideoFile(scene.asset.assetId, scene.asset.sha256)
            localVideoFile = file
            isVideoLoading = false
            if (file == null) {
                videoErrored = true
            } else {
                onPreloadNextVideo(scene.nextAssetId, scene.nextAssetSha256)
            }
        }

        LaunchedEffect(isPlaying, videoViewRef) {
            while (isPlaying && videoViewRef != null) {
                runCatching {
                    if (videoViewRef?.isPlaying == true) {
                        currentPosMs = videoViewRef?.currentPosition?.toLong() ?: currentPosMs
                    }
                }
                delay(200L)
            }
        }

        DisposableEffect(scene.sceneId) {
            onDispose {
                runCatching { videoViewRef?.stopPlayback() }
                videoViewRef = null
                mediaPlayerRef = null
            }
        }

        val activeCue = remember(currentPosMs, captionsEnabled, scene.asset.captions) {
            if (!captionsEnabled) null
            else scene.asset.captions.firstOrNull { currentPosMs in it.startMs..it.endMs }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Top bar: Exit + Scene Steps
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { onExitMission(scene.sceneId, currentPosMs) }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Catálogo")
                }
                if (detail.narrativeProgress.status == "COMPLETED") {
                    OutlinedButton(onClick = { showConclusion = true }) {
                        Text("Ver conclusão")
                    }
                }
            }

            Text(
                text = "${detail.careerHeader} · ${detail.displayCode}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Etapa ${scene.order} de ${detail.scenes.size} · ${scene.title}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                detail.scenes.forEach { s ->
                    val accessible = MissionsFinanceMath.canAccessScene(
                        detail.scenes,
                        s.sceneId,
                        detail.narrativeProgress,
                    )
                    FilterChip(
                        selected = s.sceneId == scene.sceneId,
                        enabled = accessible && !isBusy,
                        onClick = {
                            activeSceneId = s.sceneId
                            onJumpToScene(s.sceneId)
                        },
                        label = { Text("${s.order}") },
                    )
                }
            }

            // 16:9 Audiovisual Stage + Transcript Fallback
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF050C18)),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .background(Color(0xFF030710)),
                        contentAlignment = Alignment.Center,
                    ) {
                        when {
                            isVideoLoading -> {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    CircularProgressIndicator(color = Color(0xFF38BDF8))
                                    Text(
                                        text = "Preparando vídeo da cena…",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFFCBD5E1),
                                    )
                                }
                            }
                            videoErrored || localVideoFile == null -> {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(16.dp)
                                        .verticalScroll(rememberScrollState()),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        text = "Modo Transcrição Ativo · Vídeo indisponível",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = Color(0xFFFDE68A),
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Text(
                                        text = scene.transcriptPtBr,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color(0xFFF8FAFC),
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(onClick = { retryNonce += 1L }) {
                                            Text("Tentar vídeo", color = Color(0xFF38BDF8))
                                        }
                                        if (!scene.requiresDecisionToAdvance) {
                                            Button(
                                                onClick = {
                                                    if (scene.nextSceneId != null) {
                                                        val nxt = scene.nextSceneId
                                                        onAdvanceScene(scene.sceneId, nxt, true)
                                                        activeSceneId = nxt
                                                    } else {
                                                        onAdvanceScene(scene.sceneId, null, true)
                                                        showConclusion = true
                                                    }
                                                },
                                            ) {
                                                Text(if (scene.nextSceneId != null) "Prosseguir →" else "Concluir →")
                                            }
                                        }
                                    }
                                }
                            }
                            else -> {
                                val readyFile = localVideoFile!!
                                AndroidView(
                                    modifier = Modifier.fillMaxSize(),
                                    factory = { ctx ->
                                        VideoView(ctx).apply {
                                            videoViewRef = this
                                            setVideoPath(readyFile.absolutePath)
                                            setOnPreparedListener { mp ->
                                                mediaPlayerRef = mp
                                                val vol = if (isMuted) 0f else 1f
                                                runCatching { mp.setVolume(vol, vol) }
                                                val savedMs = detail.narrativeProgress.scenePositionsMs[scene.sceneId] ?: 0L
                                                if (savedMs in 1L..((scene.asset.durationSeconds * 900).toLong())) {
                                                    seekTo(savedMs.toInt())
                                                }
                                                start()
                                                isPlaying = true
                                            }
                                            setOnCompletionListener {
                                                isPlaying = false
                                                currentPosMs = (scene.asset.durationSeconds * 1000).toLong()
                                                if (
                                                    MissionsFinanceMath.shouldAutoAdvanceScene(
                                                        scene = scene,
                                                        autoAdvanceEnabled = autoAdvanceEnabled,
                                                        userIntervened = userIntervened,
                                                        videoUnavailableOrErrored = false,
                                                    )
                                                ) {
                                                    val nxt = scene.nextSceneId
                                                    if (nxt != null) {
                                                        onAdvanceScene(scene.sceneId, nxt, false)
                                                        activeSceneId = nxt
                                                    }
                                                }
                                            }
                                            setOnErrorListener { _, _, _ ->
                                                isPlaying = false
                                                videoErrored = true
                                                transcriptOpen = true
                                                true
                                            }
                                        }
                                    },
                                )

                                if (activeCue != null) {
                                    Surface(
                                        color = Color(0xCC040B16),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier
                                            .align(Alignment.BottomCenter)
                                            .padding(12.dp),
                                    ) {
                                        Text(
                                            text = activeCue.text,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color.White,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Player Controls Bar
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        if (!videoErrored && localVideoFile != null) {
                            val totalMs = (scene.asset.durationSeconds * 1000.0).toLong().coerceAtLeast(1000L)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                IconButton(
                                    onClick = {
                                        val vv = videoViewRef ?: return@IconButton
                                        if (vv.isPlaying) {
                                            userIntervened = true
                                            vv.pause()
                                            isPlaying = false
                                        } else {
                                            if (currentPosMs >= totalMs - 250L) {
                                                vv.seekTo(0)
                                                currentPosMs = 0L
                                            }
                                            vv.start()
                                            isPlaying = true
                                        }
                                    },
                                ) {
                                    Icon(
                                        imageVector = when {
                                            isPlaying -> Icons.Default.Pause
                                            currentPosMs >= totalMs - 250L -> Icons.Default.Replay
                                            else -> Icons.Default.PlayArrow
                                        },
                                        contentDescription = if (isPlaying) "Pausar vídeo" else "Reproduzir vídeo",
                                    )
                                }

                                Slider(
                                    value = currentPosMs.toFloat().coerceIn(0f, totalMs.toFloat()),
                                    onValueChange = { nextVal ->
                                        userIntervened = true
                                        currentPosMs = nextVal.toLong()
                                        videoViewRef?.seekTo(nextVal.toInt())
                                    },
                                    valueRange = 0f..totalMs.toFloat(),
                                    modifier = Modifier.weight(1f),
                                )

                                Text(
                                    text = "${MissionsFinanceMath.formatClockSeconds(currentPosMs / 1000.0)} / ${MissionsFinanceMath.formatClockSeconds(scene.asset.durationSeconds)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(start = 6.dp),
                                )

                                IconButton(
                                    onClick = {
                                        isMuted = !isMuted
                                        val vol = if (isMuted) 0f else 1f
                                        runCatching { mediaPlayerRef?.setVolume(vol, vol) }
                                    },
                                ) {
                                    Icon(
                                        imageVector = if (isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                                        contentDescription = if (isMuted) "Ativar áudio" else "Silenciar áudio",
                                    )
                                }
                            }
                        }

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            FilterChip(
                                selected = captionsEnabled,
                                onClick = { captionsEnabled = !captionsEnabled },
                                leadingIcon = { Icon(Icons.Default.ClosedCaption, contentDescription = null) },
                                label = { Text(if (captionsEnabled) "Legendas PT-BR" else "Sem legendas") },
                            )
                            FilterChip(
                                selected = transcriptOpen,
                                onClick = {
                                    userIntervened = true
                                    transcriptOpen = !transcriptOpen
                                },
                                leadingIcon = { Icon(Icons.Default.Description, contentDescription = null) },
                                label = { Text("Transcrição") },
                            )
                            if (scene.autoAdvanceOnVideoEnd) {
                                FilterChip(
                                    selected = autoAdvanceEnabled,
                                    onClick = { autoAdvanceEnabled = !autoAdvanceEnabled },
                                    label = {
                                        Text(if (autoAdvanceEnabled) "Auto-avanço: Ativo" else "Auto-avanço: Pausado")
                                    },
                                )
                            }
                        }
                    }
                }
            }

            if (transcriptOpen) {
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "Transcrição · ${scene.title}",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = scene.transcriptPtBr,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            // Native App-Rendered Bond Present Value Curve (Scene 4)
            if (scene.nativeVisual?.visualType == "BOND_PRESENT_VALUE_CURVE") {
                BondPresentValueChartCard(visual = scene.nativeVisual)
            }

            // Narrative Context or Didactic Decision Card
            val inter = scene.interaction
            if (inter != null) {
                DecisionInteractionCard(
                    scene = scene,
                    isBusy = isBusy,
                    onSelectOption = { optId ->
                        val spent = (System.currentTimeMillis() - sceneStartedAtMs).coerceAtLeast(0L)
                        onSubmitDecision(scene.sceneId, inter.interactionId, optId, spent, videoErrored)
                    },
                    onContinue = {
                        if (scene.nextSceneId != null) {
                            val nxt = scene.nextSceneId
                            onAdvanceScene(scene.sceneId, nxt, videoErrored)
                            activeSceneId = nxt
                        } else {
                            onAdvanceScene(scene.sceneId, null, videoErrored)
                            showConclusion = true
                        }
                    },
                )

                // Native App-Rendered Duration Comparison Chart after answering Scene 5
                if (inter.answered != null && scene.nativeVisual?.visualType == "ZERO_COUPON_DURATION_COMPARISON") {
                    DurationComparisonChartCard(visual = scene.nativeVisual)
                }
            } else {
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = "${scene.character.name} · ${scene.character.roleTitle}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = scene.narrativeContext,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (!scene.problemStatement.isNullOrBlank()) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            ) {
                                Text(
                                    text = scene.problemStatement,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(12.dp),
                                )
                            }
                        }
                        Button(
                            onClick = {
                                if (scene.nextSceneId != null) {
                                    val nxt = scene.nextSceneId
                                    onAdvanceScene(scene.sceneId, nxt, videoErrored)
                                    activeSceneId = nxt
                                } else {
                                    onAdvanceScene(scene.sceneId, null, videoErrored)
                                    showConclusion = true
                                }
                            },
                            enabled = !isBusy,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text(
                                text = if (scene.nextSceneId != null) "Avançar para a próxima cena →" else "Ver conclusão da missão →",
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DecisionInteractionCard(
    scene: MissionSceneView,
    isBusy: Boolean,
    onSelectOption: (String) -> Unit,
    onContinue: () -> Unit,
) {
    val inter = scene.interaction ?: return
    val answered = inter.answered

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "PONTO DE DECISÃO NARRATIVA · ATIVIDADE DIDÁTICA",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = inter.prompt,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            if (!inter.contextLead.isNullOrBlank()) {
                Text(
                    text = inter.contextLead,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = inter.ceterisParibusNote,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            inter.options.forEach { opt ->
                val isExpected = answered != null && opt.optionId == answered.expectedOptionId
                val isWrongSelected = answered != null && opt.optionId == answered.selectedOptionId && !answered.isCorrect
                val borderColor = when {
                    isExpected -> Color(0xFF22C55E)
                    isWrongSelected -> Color(0xFFF59E0B)
                    else -> MaterialTheme.colorScheme.outlineVariant
                }
                val bg = when {
                    isExpected -> Color(0xFF123D27)
                    isWrongSelected -> Color(0xFF452908)
                    else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                }
                val fg = if (isExpected || isWrongSelected) Color.White else MaterialTheme.colorScheme.onSurface

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = bg,
                    contentColor = fg,
                    border = BorderStroke(1.5.dp, borderColor),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .clickable(enabled = !isBusy) { onSelectOption(opt.optionId) },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = "${opt.optionId}.",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.ExtraBold,
                        )
                        Text(
                            text = opt.label,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }

            if (answered != null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (answered.isCorrect) Color(0xFF123D27) else Color(0xFF452908),
                    contentColor = Color.White,
                    border = BorderStroke(
                        1.dp,
                        if (answered.isCorrect) Color(0xFF4ADE80) else Color(0xFFFBBF24),
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = if (answered.isCorrect) {
                                "Resposta esperada (${answered.expectedOptionId}) confirmada!"
                            } else {
                                "Você selecionou ${answered.selectedOptionId} · Resposta conceitual esperada: ${answered.expectedOptionId}"
                            },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(text = answered.optionFeedback, style = MaterialTheme.typography.bodySmall)
                        Text(text = answered.conceptualExplanation, style = MaterialTheme.typography.bodySmall)
                        Button(
                            onClick = onContinue,
                            enabled = !isBusy,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp),
                        ) {
                            Text(
                                text = if (scene.nextSceneId != null) "Continuar para a próxima cena →" else "Ver conclusão da missão →",
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BondPresentValueChartCard(visual: NativeVisualSpec) {
    val p = visual.parameters
    var simRatePct by rememberSaveable(visual.visualId) {
        mutableFloatStateOf(p.shockedRatePct.toFloat())
    }
    val points = remember(p) { MissionsFinanceMath.buildPresentValueCurve(p) }
    val initialPrice = MissionsFinanceMath.presentValueZeroCoupon(p.faceValueBrl, p.initialRatePct, p.maturityYears)
    val simPrice = MissionsFinanceMath.presentValueZeroCoupon(p.faceValueBrl, simRatePct.toDouble(), p.maturityYears)
    val deltaPct = MissionsFinanceMath.priceChangePct(p.faceValueBrl, p.initialRatePct, simRatePct.toDouble(), p.maturityYears)

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "GRÁFICO NATIVO DO APLICATIVO · ${visual.formulaLabel}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = visual.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = visual.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                text = "Taxa exigida simulada: ${String.format(Locale.forLanguageTag("pt-BR"), "%.1f", simRatePct)}% a.a. → ${MissionsFinanceMath.formatBrl(simPrice)} (${MissionsFinanceMath.formatSignedPct(deltaPct)} vs. ${p.initialRatePct.toInt()}%)",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )

            Slider(
                value = simRatePct,
                onValueChange = { simRatePct = it },
                valueRange = p.minRatePct.toFloat()..p.maxRatePct.toFloat(),
                modifier = Modifier.fillMaxWidth(),
            )

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(175.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF071120))
                    .padding(16.dp),
            ) {
                val minP = (points.minOf { it.priceBrl } * 0.96).toFloat()
                val maxP = (points.maxOf { it.priceBrl } * 1.02).toFloat()
                val minR = p.minRatePct.toFloat()
                val maxR = p.maxRatePct.toFloat()

                fun xOf(rate: Float): Float = ((rate - minR) / (maxR - minR).coerceAtLeast(0.1f)) * size.width
                fun yOf(price: Float): Float = (1f - ((price - minP) / (maxP - minP).coerceAtLeast(1f))) * size.height

                // Axes
                drawLine(Color(0xFF284166), Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 3f)
                drawLine(Color(0xFF284166), Offset(0f, 0f), Offset(0f, size.height), strokeWidth = 3f)

                val path = Path()
                points.forEachIndexed { idx, pt ->
                    val x = xOf(pt.ratePct.toFloat())
                    val y = yOf(pt.priceBrl.toFloat())
                    if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, color = Color(0xFF38BDF8), style = Stroke(width = 6f, cap = StrokeCap.Round))

                // Base marker (green) & simulated marker (amber)
                drawCircle(
                    color = Color(0xFF4ADE80),
                    radius = 12f,
                    center = Offset(xOf(p.initialRatePct.toFloat()), yOf(initialPrice.toFloat())),
                )
                drawCircle(
                    color = Color(0xFFFBBF24),
                    radius = 14f,
                    center = Offset(xOf(simRatePct), yOf(simPrice.toFloat())),
                )
            }

            visual.highlights.forEach { item ->
                Text(
                    text = "• $item",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
fun DurationComparisonChartCard(visual: NativeVisualSpec) {
    val cmp = remember(visual.parameters) {
        MissionsFinanceMath.buildDurationComparison(visual.parameters)
    }
    val maxDrop = maxOf(1.0, kotlin.math.abs(cmp.shortBond.changePct), kotlin.math.abs(cmp.longBond.changePct))

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "COMPARATIVO NATIVO · ${visual.formulaLabel}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = visual.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Zero Cupom ${cmp.shortBond.years} ano (D = 1): ${MissionsFinanceMath.formatSignedPct(cmp.shortBond.changePct)} (${MissionsFinanceMath.formatBrl(cmp.shortBond.initialPrice)} → ${MissionsFinanceMath.formatBrl(cmp.shortBond.shockedPrice)})",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Zero Cupom ${cmp.longBond.years} anos (D = 5): ${MissionsFinanceMath.formatSignedPct(cmp.longBond.changePct)} (${MissionsFinanceMath.formatBrl(cmp.longBond.initialPrice)} → ${MissionsFinanceMath.formatBrl(cmp.longBond.shockedPrice)})",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(92.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF071120))
                    .padding(14.dp),
            ) {
                val shortFrac = (kotlin.math.abs(cmp.shortBond.changePct) / maxDrop).toFloat().coerceIn(0.06f, 1f)
                val longFrac = (kotlin.math.abs(cmp.longBond.changePct) / maxDrop).toFloat().coerceIn(0.06f, 1f)
                val barH = (size.height - 14f) / 2f

                drawRoundRect(
                    color = Color(0xFF38BDF8),
                    topLeft = Offset(0f, 0f),
                    size = Size(size.width * shortFrac, barH),
                    cornerRadius = CornerRadius(12f, 12f),
                )
                drawRoundRect(
                    color = Color(0xFFFBBF24),
                    topLeft = Offset(0f, barH + 14f),
                    size = Size(size.width * longFrac, barH),
                    cornerRadius = CornerRadius(12f, 12f),
                )
            }

            visual.highlights.forEach { item ->
                Text(text = "• $item", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun MissionConclusionSection(
    detail: MissionDetailView,
    isBusy: Boolean,
    onRevisitScene: (String) -> Unit,
    onStartRelatedPractice: () -> Unit,
    onRepeatMission: () -> Unit,
    onBackToCatalog: () -> Unit,
) {
    val prog = detail.narrativeProgress
    val perf = detail.interactionPerformance
    val mastery = detail.demonstratedMastery

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = "${detail.careerHeader} · ${detail.displayCode} CONCLUÍDA",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = detail.conclusion.headline,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.ExtraBold,
        )
        Text(text = detail.conclusion.narrativeClosure, style = MaterialTheme.typography.bodyMedium)

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
            Text(
                text = detail.conclusion.epistemologicalNote,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(14.dp),
            )
        }

        // 3-Tier Epistemological Summary
        EpistemicSummaryCard(
            title = "1. Progresso narrativo",
            metric = "${prog.progressPct.toInt()}% (${prog.completedScenesCount}/${prog.totalScenesCount} cenas)",
            note = "Percurso audiovisual da missão registrado na sua sessão.",
        )
        EpistemicSummaryCard(
            title = "2. Desempenho nas interações",
            metric = "${perf.firstAttemptCorrectCount}/${perf.totalDecisionsCount} na 1ª escolha",
            note = "Atividade didática formativa — não compõe indicador formal de domínio.",
        )
        EpistemicSummaryCard(
            title = "3. Domínio demonstrado (acervo)",
            metric = "${mastery.distinctHumanQuestionsPracticed}/${mastery.minQuestionsForMastery} questões humanas",
            note = mastery.masteryLabelPt,
        )

        Text(
            text = "Conceitos trabalhados e vínculos curriculares",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )

        detail.workedConcepts.forEach { concept ->
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(
                    1.dp,
                    if (concept.recommendedRevisit) Color(0xFFF59E0B) else MaterialTheme.colorScheme.outlineVariant,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = concept.label,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        if (concept.recommendedRevisit) {
                            Text(
                                text = "Revisitação sugerida",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFF59E0B),
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    Text(text = concept.summary, style = MaterialTheme.typography.bodySmall)
                    Text(
                        text = "Currículo: ${concept.canonicalSubjectId} · ${concept.activeTopicIds.joinToString(", ")}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (concept.recommendedRevisit && concept.relatedSceneIds.isNotEmpty()) {
                        TextButton(onClick = { onRevisitScene(concept.relatedSceneIds.first()) }) {
                            Text("Revisitar cena deste conceito")
                        }
                    }
                }
            }
        }

        Button(
            onClick = onStartRelatedPractice,
            enabled = !isBusy,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(
                text = if (mastery.practiceAvailable) {
                    "Praticar questões reais relacionadas (${mastery.availableHumanQuestionsCount})"
                } else {
                    "Verificar questões reais no acervo"
                },
                fontWeight = FontWeight.Bold,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(
                onClick = onRepeatMission,
                enabled = !isBusy,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp),
            ) {
                Text("Repetir missão")
            }
            OutlinedButton(
                onClick = onBackToCatalog,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp),
            ) {
                Text("Ao catálogo")
            }
        }
    }
}

@Composable
private fun EpistemicSummaryCard(title: String, metric: String, note: String) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = metric,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
            )
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
