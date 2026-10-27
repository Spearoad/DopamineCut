package com.example.dopaminecut2.service

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import com.example.dopaminecut2.DopamineCutApplication
import com.example.dopaminecut2.BuildConfig
import com.example.dopaminecut2.data.repository.UserRepositoryInterface
import com.example.dopaminecut2.di.AppDependencies
import com.example.dopaminecut2.domain.ContentCategory
import com.example.dopaminecut2.domain.SupportedPlatform
import com.example.dopaminecut2.logic.ai.DopamineScorePolicy
import com.example.dopaminecut2.logic.ai.BatchContentClassifier
import com.example.dopaminecut2.logic.ai.ClassificationBatchQueue
import com.example.dopaminecut2.logic.ai.ClassifiedContent
import com.example.dopaminecut2.logic.ai.ContentClassificationBatch
import com.example.dopaminecut2.logic.ai.OCRProcessor
import com.example.dopaminecut2.logic.ai.OcrContentTextSanitizer
import com.example.dopaminecut2.logic.ai.PendingContentClassification
import com.example.dopaminecut2.logic.manager.AppManagerInterface
import com.example.dopaminecut2.logic.manager.InstagramManager
import com.example.dopaminecut2.logic.manager.KakaotalkManager
import com.example.dopaminecut2.logic.manager.ScreenSnapshot
import com.example.dopaminecut2.logic.manager.YoutubeManager
import com.example.dopaminecut2.logic.shortform.CompletedShortformSession
import com.example.dopaminecut2.logic.shortform.ContentKindConfirmation
import com.example.dopaminecut2.logic.shortform.ShortformContentKind
import com.example.dopaminecut2.logic.shortform.ShortformCountDelta
import com.example.dopaminecut2.logic.shortform.ShortformScreenState
import com.example.dopaminecut2.logic.shortform.ShortformSessionTracker
import com.example.dopaminecut2.logic.shortform.ShortformSessionCheckpoint
import com.example.dopaminecut2.logic.shortform.VideoIdentitySessionResolver
import com.example.dopaminecut2.logic.shortform.IdentityQuality
import com.example.dopaminecut2.logic.shortform.InstagramOnDeviceContentClassifier
import com.example.dopaminecut2.logic.shortform.InstagramUiClass
import com.example.dopaminecut2.logic.shortform.YoutubeOnDeviceContentClassifier
import com.example.dopaminecut2.session.ServiceSessionManager
import com.example.dopaminecut2.statistics.UsageClassification
import com.example.dopaminecut2.statistics.UsageStatisticsManager
import com.example.dopaminecut2.time.MonotonicClock
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import java.io.File
import kotlin.coroutines.resume

class AppBlockService : AccessibilityService() {
    private var measurementEnabled = true
    private var contentAnalysisEnabled = true
    private var qualityElapsed = 0L
    private var qualitySeconds = 0L
    private var qualityRemainderMs = 0L
    private var qualityLastWrite = 0L
    private var continuousWatchSec = 0L
    private var continuousRemainderMs = 0L
    private var continuousLastElapsed = 0L
    private var continuousNoticeSent = false
    private var continuousBreakMinutes = 0
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val classificationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainExecutorCompat = Executor(mainHandler::post)

    private lateinit var dependencies: AppDependencies
    private lateinit var userRepository: UserRepositoryInterface
    private lateinit var usageStatistics: UsageStatisticsManager
    private lateinit var sessionManager: ServiceSessionManager
    private lateinit var monotonicClock: MonotonicClock
    private lateinit var viewTracker: ShortformSessionTracker
    private lateinit var ocrProcessor: OCRProcessor
    private var youtubeContentClassifier: YoutubeOnDeviceContentClassifier? = null
    private var instagramContentClassifier: InstagramOnDeviceContentClassifier? = null
    private lateinit var batchClassifier: BatchContentClassifier
    private val classificationQueue = ClassificationBatchQueue()
    private val ocrTextSanitizer = OcrContentTextSanitizer()
    private val contentRegionResolver = com.example.dopaminecut2.logic.ai.YoutubeContentRegionResolver()
    private var youtubeLayoutKey: String? = null
    private var layoutValidationPending = false
    private val identityResolver = VideoIdentitySessionResolver()
    private val youtubeLiveSession = com.example.dopaminecut2.logic.shortform.YoutubeLiveSession()
    private var diagnosticForegroundPackage: String? = null
    private val screenshotMutex = Mutex()
    private val contentKindConfirmation = ContentKindConfirmation()
    private val entryEvidenceGrace = com.example.dopaminecut2.logic.shortform.EntryEvidenceGrace()
    private var recognitionHealth = com.example.dopaminecut2.logic.shortform.RecognitionHealth()
    private var youtubeIdentityReady = false
    private var lastHealthLogElapsedMs = 0L
    private var screenGeneration = 0L
    private var lastContentClassificationElapsedMs = -CONTENT_RECHECK_INTERVAL_MS
    private var lastScreenRefreshElapsedMs = -SCREEN_REFRESH_INTERVAL_MS

    private val appManagers = listOf(
        YoutubeManager(),
        InstagramManager(),
        KakaotalkManager()
    ).associateBy(AppManagerInterface::packageName)

    private val normalSessions = mutableMapOf<String, NormalSessionOwner>()
    // This worker survives service cancellation long enough to drain already accepted metric writes.
    private val usageWrites = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private var usageWriteJob: Job? = null
    private val sessionTransitionMutex = Mutex()

    private var onboardingObservationJob: Job? = null
    private var tickerJob: Job? = null
    private var contentKindJob: Job? = null
    private var contentOcrJob: Job? = null
    private var currentAppManager: AppManagerInterface? = null
    private var currentSnapshot = ScreenSnapshot(emptyList(), emptyList())
    private var currentIsShortform = false
    private var currentScreenState = ShortformScreenState.OUTSIDE
    private var currentContentKey: String? = null
    private var currentContentKind = ShortformContentKind.UNKNOWN
    private var lastContentOcrElapsedMs = 0L
    private var lastScreenshotElapsedMs = 0L
    private var latestOcrContentKey: String? = null
    private var latestOcrText = ""
    private var latestOcrElapsedMs = 0L

    private lateinit var goalPresenter: GoalInterventionPresenter
    private var goalObservationJob: Job? = null
    private var managedGoals: List<com.example.dopaminecut2.domain.ManagedGoal> = emptyList()
    private val managedUsage = mutableMapOf<String, com.example.dopaminecut2.data.model.AppUsage>()
    private val managedCheckpoints = mutableMapOf<String, ShortformSessionCheckpoint>()
    private var ledger = com.example.dopaminecut2.data.local.InterventionLedger()
    private val snoozeElapsedUntil = mutableMapOf<String, Long>()
    private var overlayGoal: com.example.dopaminecut2.domain.ManagedGoal? = null
    private var managedUsageReady = false
    private var managedGoalsLoaded = false
    private var managedSetupReady = false
    private var goalNotificationsEnabled = true
    private var managedEnforcementBusy = false
    private var lastManagedDismissElapsedMs = -2_000L
    private var lastManagedFailureElapsedMs = -30_000L
    private var diagnosticAdEvidence: com.example.dopaminecut2.logic.shortform.YoutubeAdEvidence? = null
    private var currentAppStartElapsedMs = 0L
    private var lastRunTimeFlushElapsedMs = 0L
    private var lastRetryElapsedMs = 0L
    @Volatile private var retryInProgress = false
    @Volatile private var serviceActive = false
    private var sessionReady = false

    private data class NormalSessionOwner(
        val userId: String,
        val date: String,
        val viewSessionId: String,
        val restrictions: List<ContentCategory>,
        val recorded: CompletableDeferred<Boolean> = CompletableDeferred(),
        var classificationStarted: Boolean = false,
        var completed: Boolean = false,
        var resolved: Boolean = false
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        val app = application as DopamineCutApplication
        dependencies = app.dependenciesOrNull() ?: run {
            Toast.makeText(this, "Firebase 설정이 없어 감지 서비스를 중지합니다.", Toast.LENGTH_LONG).show()
            disableSelf()
            return
        }
        serviceActive = true
        goalPresenter = GoalInterventionPresenter(this)
        userRepository = dependencies.userRepository
        usageStatistics = dependencies.usageStatisticsManager
        monotonicClock = dependencies.monotonicClock
        sessionManager = ServiceSessionManager(
            dependencies.authRepository,
            dependencies.dateIdProvider
        )
        ocrProcessor = OCRProcessor()
        batchClassifier = dependencies.batchContentClassifier
        usageWriteJob = dependencies.applicationScope.launch {
            for (write in usageWrites) {
                try {
                    write()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    reportFailureOnMain("시청 기록 로컬 저장", error)
                }
            }
        }
        youtubeContentClassifier = runCatching {
            YoutubeOnDeviceContentClassifier(applicationContext)
        }.onFailure { error ->
            Log.e(TAG, "YouTube CV 모델 초기화 실패: 콘텐츠를 UNKNOWN으로 유지합니다.", error)
        }.getOrNull()

        instagramContentClassifier = runCatching {
            InstagramOnDeviceContentClassifier(applicationContext)
        }.onSuccess {
            Log.i(TAG, "✅ Instagram CV 모델 메모리 로드 및 무결성 검증 성공!")
        }.onFailure { error ->
            Log.e(TAG, "❌ Instagram CV 모델 초기화 실패: 콘텐츠를 UNKNOWN으로 유지합니다.", error)
        }.getOrNull()

        viewTracker = ShortformSessionTracker(
            clockMs = monotonicClock::nowMillis,
            onCountDelta = ::onViewCountDelta,
            onCheckpoint = ::onTrackedSessionCheckpoint,
            onSessionCompleted = ::onTrackedSessionCompleted,
            onProvisionalCheckpoint = ::onProvisionalCheckpoint,
            onProvisionalSettled = ::onProvisionalSettled
        )
        dependencies.dataControls.quiesceService = {
            sessionTransitionMutex.withLock {
                measurementEnabled = false
                qualitySeconds = 0L
                qualityRemainderMs = 0L
                accountAndPersistCurrentRunTime()
                stopShortformTracking()
                abandonClassifications("MEASUREMENT_PAUSED")
                currentAppManager = null
                dismissGoalOverlay()
                drainUsageWrites()
                while (retryInProgress) delay(50)
            }
        }
        serviceScope.launch {
            dependencies.authRepository.authState().collectLatest { uid ->
                if (uid != null) dependencies.habitStore.settings(uid).collect { settings ->
                    val resume = !measurementEnabled && settings.measurement
                    if (!settings.measurement && measurementEnabled) dependencies.dataControls.quiesceService?.invoke()
                    if (!settings.content && contentAnalysisEnabled) { abandonClassifications("ANALYSIS_DISABLED"); clearLatestOcr() }
                    measurementEnabled = settings.measurement
                    contentAnalysisEnabled = settings.content
                    if (resume) refreshSessionIfNeeded(force = true)
                }
            }
        }

        serviceScope.launch {
            refreshSessionIfNeeded(force = true)
            retryPendingIfDue(force = true)
        }
        tickerJob = serviceScope.launch {
            while (true) {
                delay(TICK_INTERVAL_MS)
                refreshSessionIfNeeded()
                recordQualityHeartbeat()
                refreshForegroundScreen(force = true)
                viewTracker.tick()
                retryYoutubeContentIfDue()
                refreshYoutubeContentOcrIfDue()
                classificationQueue.pollDue(monotonicClock.nowMillis())?.let(::dispatchClassificationBatch)
                flushRunTimeIfDue()
                enforceLimits()
                enforceContinuousBreak()
                retryPendingIfDue()
            }
        }
        Log.i(TAG, "접근성 감지 서비스가 연결되었습니다.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !::viewTracker.isInitialized) return
        val activeSession = sessionManager.active()
        if (dependencies.authRepository.currentUserId() != activeSession?.userId) {
            serviceScope.launch { refreshSessionIfNeeded() }
            return
        }

        refreshForegroundScreen()
    }

    /** 이벤트 발신 앱 대신 실제 활성 창을 확인한다. 비대상 앱의 노드/텍스트는 수집하지 않는다. */
    private fun refreshForegroundScreen(force: Boolean = false) {
        if (!measurementEnabled) return
        if (!sessionReady || sessionManager.active() == null) return
        val interactive = getSystemService(PowerManager::class.java).isInteractive &&
                !getSystemService(KeyguardManager::class.java).isKeyguardLocked
        val rootNode = if (interactive) rootInActiveWindow else null
        val foregroundPackage = rootNode?.packageName?.toString()
        if (goalPresenter.isShowing) {
            if (foregroundPackage != null && foregroundPackage != packageName &&
                foregroundPackage != currentAppManager?.packageName) dismissGoalOverlay()
            return
        }
        if (BuildConfig.DEBUG && File(cacheDir, "identity-diagnostics.enabled").exists() &&
            diagnosticForegroundPackage != foregroundPackage) {
            diagnosticForegroundPackage = foregroundPackage
            Log.d("YoutubePipelineDiagnostic", "youtube=${foregroundPackage == "com.google.android.youtube"}; package=$foregroundPackage")
        }
        val newManager = rootNode?.packageName?.toString()?.let(appManagers::get)
        if (newManager !== currentAppManager) {
            accountAndPersistCurrentRunTime()
            stopShortformTracking()
            entryEvidenceGrace.reset()
            recognitionHealth = com.example.dopaminecut2.logic.shortform.RecognitionHealth()
            currentAppManager = newManager
            currentSnapshot = ScreenSnapshot(emptyList(), emptyList())
            currentIsShortform = false
            currentAppStartElapsedMs = if (newManager == null) 0L else monotonicClock.nowMillis()
            lastRunTimeFlushElapsedMs = currentAppStartElapsedMs
        }

        val manager = currentAppManager ?: return
        if (rootNode == null) return
        val now = monotonicClock.nowMillis()
        if (!force && now - lastScreenRefreshElapsedMs < SCREEN_REFRESH_INTERVAL_MS) return
        lastScreenRefreshElapsedMs = now
        currentSnapshot = ScreenSnapshot.from(rootNode)
        if (manager.platform == SupportedPlatform.INSTAGRAM) logInstagramSnapshot(currentSnapshot)
        if (manager.platform == SupportedPlatform.YOUTUBE) {
            val nextLayout = contentRegionResolver.layoutKey(currentSnapshot)
            if (youtubeLayoutKey != null && youtubeLayoutKey != nextLayout) {
                // 배치 변경은 영상 전환이 아니다. 식별자/집계 세션을 유지하고 화면 결과만 무효화한다.
                screenGeneration++
                contentKindJob?.cancel()
                contentOcrJob?.cancel()
                contentKindConfirmation.reset()
                clearLatestOcr()
                lastContentClassificationElapsedMs = -CONTENT_RECHECK_INTERVAL_MS
                layoutValidationPending = true
                viewTracker.setPaused(true)
            }
            youtubeLayoutKey = nextLayout
        }
        if (manager.platform == SupportedPlatform.YOUTUBE && BuildConfig.DEBUG &&
            File(cacheDir, "identity-diagnostics.enabled").exists()) {
            if (force) {
                currentSnapshot.elements.firstOrNull {
                    it.className?.endsWith("SeekBar") == true
                }?.contentDescription?.let {
                    Log.d("YoutubePipelineDiagnostic", "playbackProgress=$it")
                }
            }
            val evidence = com.example.dopaminecut2.logic.shortform.YoutubeAdEvidenceDetector.detect(currentSnapshot)
            if (evidence != diagnosticAdEvidence) {
                diagnosticAdEvidence = evidence
                Log.d("YoutubePipelineDiagnostic", "adEvidence=$evidence; nodes=${currentSnapshot.elements.size}")
            }
        }
        updateShortformTracking(manager)
        enforceLimits()
    }



    // Developer opt-in only. No persistence, network upload, or screenshot capture.
    private var instagramDiagnosticLastMs = -2_000L
    private var instagramDiagnosticSignature: String? = null
    private var instagramDiagnosticSequence = 0L
    private var instagramTrackingDiagnosticSignature: String? = null

    private fun logInstagramSnapshot(snapshot: ScreenSnapshot) {
        if (!BuildConfig.DEBUG || !File(cacheDir, "instagram-diagnostics.enabled").exists()) return
        val now = monotonicClock.nowMillis()
        if (now - instagramDiagnosticLastMs < 2_000L) return
        instagramDiagnosticLastMs = now
        val includeText = File(cacheDir, "instagram-diagnostics-text.enabled").exists()
        val rows = snapshot.elements.mapIndexed { index, element ->
            org.json.JSONObject().apply {
                put("index", index)
                put("id", element.viewId ?: org.json.JSONObject.NULL)
                put("class", element.className ?: org.json.JSONObject.NULL)
                put("selected", element.isSelected)
                element.bounds?.let { b ->
                    put("bounds", org.json.JSONArray(listOf(b.left, b.top, b.right, b.bottom)))
                }
                // Text is disabled unless separately opted in. Bound every log line.
                if (includeText) {
                    put("text", element.text?.take(300) ?: org.json.JSONObject.NULL)
                    put("description", element.contentDescription?.take(300) ?: org.json.JSONObject.NULL)
                } else {
                    put("textLength", element.text?.length ?: 0)
                    put("descriptionLength", element.contentDescription?.length ?: 0)
                }
            }.toString()
        }
        val signature = java.security.MessageDigest.getInstance("SHA-256")
            .digest((includeText.toString() + rows.joinToString("\n")).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        if (signature == instagramDiagnosticSignature) return
        instagramDiagnosticSignature = signature
        val sequence = ++instagramDiagnosticSequence
        val detection = (currentAppManager as? InstagramManager)?.detectShortformScreen(snapshot)
        Log.d("InstagramUiDiagnostic", "BEGIN seq=$sequence elapsedMs=$now count=${rows.size} " +
                "display=${snapshot.displayId} window=${snapshot.window} state=${detection?.state} evidence=${detection?.evidence} text=$includeText")
        rows.forEach { row -> Log.d("InstagramUiDiagnostic", "seq=$sequence $row") }
        Log.d("InstagramUiDiagnostic", "END seq=$sequence")
    }

    private fun updateInstagramTracking(manager: InstagramManager) {
        val decision = manager.observeTracking(currentSnapshot, monotonicClock.nowMillis())
        if (BuildConfig.DEBUG && File(cacheDir, "instagram-diagnostics.enabled").exists()) {
            val signature = "${decision.reason}:${decision.contentKey}:${decision.paused}"
            if (signature != instagramTrackingDiagnosticSignature) {
                instagramTrackingDiagnosticSignature = signature
                Log.d("InstagramTrackingDiagnostic", "reason=${decision.reason} retain=${decision.retain} " +
                        "paused=${decision.paused} switched=${decision.switched} key=${decision.contentKey?.take(12)}")
            }
        }

        currentScreenState = if (decision.retain && !decision.paused) {
            ShortformScreenState.INSIDE
        } else if (decision.retain) {
            ShortformScreenState.OVERLAY
        } else ShortformScreenState.OUTSIDE
        currentIsShortform = decision.retain && !decision.paused
        currentContentKind = if (decision.retain) ShortformContentKind.NORMAL else ShortformContentKind.UNKNOWN
        if (!decision.retain) {
            // Keep the coordinator's pending entry; do not reset it on its first frame.
            viewTracker.onScreenChanged(false, null, null, ShortformContentKind.UNKNOWN)
            currentContentKey = null
            return
        }
        val key = decision.contentKey ?: return
        if (decision.switched || key != currentContentKey) {
            screenGeneration++
            clearLatestOcr()
            currentContentKey = key
        }
        // Finish a paused old session BEFORE resuming the new one.
        viewTracker.onScreenChanged(true, SupportedPlatform.INSTAGRAM, key, ShortformContentKind.NORMAL)
        viewTracker.setPaused(decision.paused)
    }

    private fun updateShortformTracking(manager: AppManagerInterface) {
        if (manager is InstagramManager) {
            updateInstagramTracking(manager)
            return
        }

        val detection = manager.detectShortformScreen(currentSnapshot)
        val previousScreenState = currentScreenState
        currentScreenState = detection.state
        currentIsShortform = detection.isConfirmedShortform
        if (manager.platform == SupportedPlatform.YOUTUBE) {
            youtubeIdentityReady = if (youtubeLiveSession.key != null) youtubeLiveSession.confirmed
            else manager.getVideoIdentity(currentSnapshot, monotonicClock.nowMillis())?.quality == IdentityQuality.HIGH
            recognitionHealth.observe(detection.state, youtubeIdentityReady, monotonicClock.nowMillis())
            logRecognitionHealthIfDue()
        }
        if (!currentIsShortform) {
            if (manager.platform == SupportedPlatform.YOUTUBE &&
                (currentScreenState == ShortformScreenState.CANDIDATE ||
                        entryEvidenceGrace.shouldRetain(detection.state, monotonicClock.nowMillis()))) {
                viewTracker.setPaused(true)
                retryYoutubeContentIfDue()
                return
            }
            stopShortformTracking()
            return
        }
        if (manager.platform == SupportedPlatform.YOUTUBE) entryEvidenceGrace.shouldRetain(detection.state, monotonicClock.nowMillis())

        if (currentScreenState == ShortformScreenState.OVERLAY) {
            if (previousScreenState != ShortformScreenState.OVERLAY) {
                screenGeneration++
                contentKindJob?.cancel()
                contentOcrJob?.cancel()
                contentKindConfirmation.reset()
                clearLatestOcr()
            }
            viewTracker.setPaused(true)
            return
        }
        if (manager.platform == SupportedPlatform.YOUTUBE &&
            com.example.dopaminecut2.logic.shortform.YoutubeAdEvidenceDetector.detect(currentSnapshot).explicitAd) {
            // 광고에는 작성자 핸들이 없을 수 있다. 광고를 이전 일반 영상의 세션에 붙이지 않는다.
            if (currentContentKind != ShortformContentKind.AD) {
                if (viewTracker.isProvisional()) {
                    viewTracker.activeViewSessionId()?.let {
                        viewTracker.resolveProvisional(it, ShortformContentKind.AD, currentContentKey.orEmpty())
                    }
                }
                stopShortformTracking()
                currentContentKind = ShortformContentKind.AD
                Log.d(TAG, "YouTube 명시적 광고 라벨 확인: AD, 시청 집계 제외")
            }
            return
        }
        if (manager.platform == SupportedPlatform.YOUTUBE && youtubeLiveSession.key != null) {
            if (layoutValidationPending) {
                viewTracker.setPaused(true)
                retryYoutubeContentIfDue()
                return
            }
            // 라이브는 텍스트 변화가 아니라 체류 세션으로 시간을 측정한다.
            viewTracker.setPaused(!youtubeLiveSession.confirmed)
            if (youtubeLiveSession.confirmed) {
                viewTracker.onScreenChanged(true, SupportedPlatform.YOUTUBE,
                    youtubeLiveSession.key, youtubeLiveSession.kind)
            }
            retryYoutubeContentIfDue()
            return
        }
        if (manager.platform == SupportedPlatform.YOUTUBE && layoutValidationPending) {
            viewTracker.setPaused(true)
            retryYoutubeContentIfDue()
            return
        }
        val candidate = manager.getVideoIdentity(currentSnapshot, monotonicClock.nowMillis())
        if (manager.platform == SupportedPlatform.YOUTUBE && candidate?.quality != IdentityQuality.HIGH) {
            if (currentContentKey == null && currentScreenState == ShortformScreenState.INSIDE) {
                beginYoutubeContent("youtube_probe_${java.util.UUID.randomUUID()}")
            }
            // 작성자·제목이 없는 화면에서 임의 식별자를 만들거나 이전 영상에 시간을 붙이지 않는다.
            viewTracker.setPaused(!viewTracker.isProvisional() || currentContentKey?.startsWith("youtube_probe_") != true)
            retryYoutubeContentIfDue()
            return
        }
        viewTracker.setPaused(false)
        val identity = identityResolver.observe(candidate)
        val contentKey = identity?.contentKey ?: run {
            viewTracker.onScreenChanged(false, null, null, ShortformContentKind.UNKNOWN)
            return
        }
        if (manager.platform == SupportedPlatform.YOUTUBE && contentKey != currentContentKey &&
            BuildConfig.DEBUG && File(cacheDir, "identity-diagnostics.enabled").exists()) {
            // 개발자가 명시적으로 켠 로컬 진단에만 문자열을 기록한다. 운영 로그/서버에는 기록하지 않는다.
            Log.d("YoutubeIdentityDiagnostic", "creator=${identity.creator}; title=${identity.title}; key=$contentKey")
        }

        when (manager.platform) {
            // 1. 인스타그램일 때: 우리가 만든 ONNX AI 모델로 화면 판별
            SupportedPlatform.INSTAGRAM -> {
                if (contentKey != currentContentKey) {
                    currentContentKey = contentKey
                    currentContentKind = ShortformContentKind.UNKNOWN
                    contentKindConfirmation.reset()
                    classifyInstagramContent(contentKey)
                } else if (currentContentKind != ShortformContentKind.UNKNOWN) {
                    viewTracker.onScreenChanged(true, SupportedPlatform.INSTAGRAM, contentKey, currentContentKind)
                } else if (contentKindJob?.isActive != true) {
                    classifyInstagramContent(contentKey)
                }
                return
            }

            // 2. 유튜브일 때: 아래쪽의 기존 유튜브 전용 코드를 그대로 실행하도록 통과
            SupportedPlatform.YOUTUBE -> Unit

            // 3. 카카오톡(기타)일 때: 기존처럼 텍스트로만 광고 여부 판별 후 종료
            else -> {
                contentKindJob?.cancel()
                currentContentKey = contentKey
                currentContentKind = if (manager.isAdContent(currentSnapshot)) {
                    ShortformContentKind.AD
                } else {
                    ShortformContentKind.NORMAL
                }
                viewTracker.onScreenChanged(true, manager.platform, contentKey, currentContentKind)
                return
            }
        }

        if (contentKey != currentContentKey) {
            beginYoutubeContent(contentKey)
        } else if (currentContentKind != ShortformContentKind.UNKNOWN) {
            viewTracker.onScreenChanged(true, manager.platform, contentKey, currentContentKind)
        }
    }

    private fun classifyInstagramContent(contentKey: String?) {
        val model = instagramContentClassifier ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val generation = screenGeneration
        contentKindJob?.cancel()
        contentKindJob = serviceScope.launch {
            delay(CONTENT_SETTLE_MS)
            val bitmap = takeScreenshotBitmap() ?: return@launch
            val inferenceStarted = monotonicClock.nowMillis()
            val prediction = try {
                model.classify(bitmap)
                    .onFailure { error -> Log.w(TAG, "Instagram CV 추론 실패", error) }
                    .getOrNull()
            } finally {
                bitmap.recycle()
            } ?: return@launch

            if (!serviceActive || generation != screenGeneration ||
                currentAppManager?.platform != SupportedPlatform.INSTAGRAM) return@launch

            // CV 모델이 안전 구역(FEED_SEARCH, PROFILE_DM, STORY)으로 판별하면 숏폼 집계 중단
            if (prediction.uiClass.isSafeNonReelScreen || prediction.uiClass == InstagramUiClass.UNKNOWN) {
                Log.d(
                    TAG,
                    "Instagram 안전 구역/비릴스 판정: uiClass=${prediction.uiClass}, " +
                            "conf=${"%.3f".format(prediction.confidence)} -> 시청 집계 제외"
                )
                currentContentKind = ShortformContentKind.AD // 같은 화면에서 불필요한 재캡처 반복 방지
                viewTracker.onScreenChanged(false, null, null, ShortformContentKind.UNKNOWN)
                return@launch
            }

            // 접근성 텍스트에서 '광고/협찬'이 발견되었거나 CV 모델이 REEL_AD로 판별하면 AD 처리
            val explicitAd = currentAppManager?.isAdContent(currentSnapshot) == true
            val resolvedKind = if (explicitAd) ShortformContentKind.AD else prediction.contentKind
            currentContentKind = resolvedKind

            viewTracker.onScreenChanged(
                isShortform = resolvedKind == ShortformContentKind.NORMAL,
                platform = SupportedPlatform.INSTAGRAM,
                contentKey = contentKey,
                contentKind = resolvedKind
            )
            Log.d(
                TAG,
                "Instagram 콘텐츠 판정: uiClass=${prediction.uiClass}, resolved=$resolvedKind " +
                        "(explicitAd=$explicitAd, conf=${"%.3f".format(prediction.confidence)}, " +
                        "latencyMs=${monotonicClock.nowMillis() - inferenceStarted}, key=${contentKey?.take(12)})"
            )
        }
    }

    private fun beginYoutubeContent(contentKey: String) {
        Log.d(TAG, "YouTube 시청 세션 전환: ${currentContentKey?.take(10)} -> ${contentKey.take(10)}")
        viewTracker.onScreenChanged(false, null, null, ShortformContentKind.UNKNOWN)
        screenGeneration++
        contentKindJob?.cancel()
        contentOcrJob?.cancel()
        currentContentKey = contentKey
        currentContentKind = ShortformContentKind.UNKNOWN
        contentKindConfirmation.reset()
        clearLatestOcr()
        viewTracker.beginProvisional(SupportedPlatform.YOUTUBE, contentKey)
        classifyYoutubeContent(contentKey)
    }

    private fun retryYoutubeContentIfDue() {
        if (currentAppManager?.platform != SupportedPlatform.YOUTUBE) return
        if (!com.example.dopaminecut2.logic.shortform.RecognitionSafety.canProbe(currentScreenState)) return
        if (contentKindJob?.isActive == true) return
        if (!layoutValidationPending && youtubeLiveSession.key == null && currentContentKind != ShortformContentKind.UNKNOWN && youtubeIdentityReady && currentIsShortform) return
        val interval = if (youtubeLiveSession.key != null) 2_000L else CONTENT_RECHECK_INTERVAL_MS
        if (monotonicClock.nowMillis() - lastContentClassificationElapsedMs < interval) return
        classifyYoutubeContent(currentContentKey)
    }

    private fun classifyYoutubeContent(contentKey: String?) {
        val model = youtubeContentClassifier ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val generation = screenGeneration
        lastContentClassificationElapsedMs = monotonicClock.nowMillis()
        contentKindJob = serviceScope.launch {
            // 전환 애니메이션 직후 캡처를 피하고, 결과 반영 전 활성 화면을 다시 확인한다.
            delay(CONTENT_SETTLE_MS)
            refreshForegroundScreen(force = true)
            if (!isCurrentYoutubeFrame(generation, contentKey)) return@launch
            val capturedSessionId = viewTracker.activeViewSessionId()
            val bitmap = takeScreenshotBitmap()
            val inferenceStarted = monotonicClock.nowMillis()
            val prediction = try {
                bitmap?.let { model.classify(it)
                    .onFailure { error -> Log.w(TAG, "YouTube CV 추론 실패", error) }
                    .getOrNull() }
            } finally {
                bitmap?.recycle()
            }
            refreshForegroundScreen(force = true)
            if (!isCurrentYoutubeFrame(generation, contentKey) ||
                viewTracker.activeViewSessionId() != capturedSessionId) return@launch
            val evidence = com.example.dopaminecut2.logic.shortform.YoutubeAdEvidenceDetector.detect(currentSnapshot)
            val decision = com.example.dopaminecut2.logic.shortform.YoutubeAdEvidencePolicy.resolve(prediction, evidence)
            recognitionHealth.inference(prediction?.kind, prediction == null)
            if (!currentIsShortform) {
                viewTracker.setPaused(true)
                return@launch
            }
            val confirmedKind = contentKindConfirmation.observe(
                decision.kind,
                monotonicClock.nowMillis()
            )
            if (layoutValidationPending && confirmedKind == ShortformContentKind.UNKNOWN) {
                // 재확인 첫 프레임 때문에 기존 세션을 종료하면 같은 영상이 재집계된다.
                viewTracker.setPaused(true)
                return@launch
            }
            if (confirmedKind == ShortformContentKind.UNKNOWN && viewTracker.isProvisional()) {
                // Waiting for CV is not an overlay: continue provisional time, never confirmed totals.
                return@launch
            }
            if (confirmedKind == ShortformContentKind.LIVE || confirmedKind == ShortformContentKind.PHOTO_POST) {
                // 제목 없이도 시간 전용 콘텐츠 두 프레임 확정으로 체류 세션을 시작한다.
                currentContentKey = youtubeLiveSession.confirm(confirmedKind)
                layoutValidationPending = false
                currentContentKind = confirmedKind
                youtubeIdentityReady = true
                identityResolver.reset()
                clearLatestOcr()
                viewTracker.setPaused(false)
            } else if (youtubeLiveSession.key != null) {
                if (confirmedKind == ShortformContentKind.NORMAL || confirmedKind == ShortformContentKind.AD) {
                    // 일반/광고로 전환하면 라이브 정산. 다음 관측에서 일반 식별자를 추출한다.
                    stopShortformTracking()
                    Log.d(TAG, "YouTube 라이브 이탈: $confirmedKind, 라이브 세션 종료")
                    return@launch
                }
                // 불확실한 구간은 시간만 일시 중지하고 동일 세션을 보존한다.
                youtubeLiveSession.pause()
                youtubeIdentityReady = false
                viewTracker.setPaused(true)
                Log.d(TAG, "YouTube 라이브 재확인 보류: raw=${prediction?.kind}, " +
                        "resolved=${decision.kind}, probabilities=${prediction?.probabilities}; 시간 일시 중지")
                return@launch
            } else {
                if (!youtubeIdentityReady || contentKey == null) {
                    viewTracker.setPaused(true)
                    return@launch
                }
                layoutValidationPending = false
                currentContentKind = confirmedKind
                viewTracker.setPaused(false)
            }
            val promoted = capturedSessionId != null && viewTracker.isProvisional() &&
                    viewTracker.resolveProvisional(capturedSessionId, currentContentKind, currentContentKey.orEmpty())
            // Promotion can synchronously trigger an intervention; do not recreate its stopped session.
            if (!promoted) viewTracker.onScreenChanged(
                isShortform = true,
                platform = SupportedPlatform.YOUTUBE,
                contentKey = currentContentKey,
                contentKind = currentContentKind
            )
            Log.d(
                TAG,
                "YouTube 콘텐츠 판정: raw=${prediction?.kind}, resolved=${decision.kind}, confirmed=$currentContentKind " +
                        "(adEvidence=$evidence, reason=${decision.reason}) " +
                        "(probabilities=${prediction?.probabilities}, " +
                        "latencyMs=${monotonicClock.nowMillis() - inferenceStarted}, key=${currentContentKey?.take(24)})"
            )
        }
    }

    private fun isCurrentYoutubeFrame(generation: Long, key: String?): Boolean =
        serviceActive && generation == screenGeneration && currentContentKey == key &&
                currentAppManager?.platform == SupportedPlatform.YOUTUBE &&
                com.example.dopaminecut2.logic.shortform.RecognitionSafety.canProbe(currentScreenState) &&
                sessionManager.active()?.userId == dependencies.authRepository.currentUserId()

    private fun refreshYoutubeContentOcrIfDue() {
        if (!contentAnalysisEnabled || !measurementEnabled) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (currentAppManager?.platform != SupportedPlatform.YOUTUBE || !currentIsShortform) return
        if (currentScreenState == ShortformScreenState.OVERLAY) return
        if (currentContentKind != ShortformContentKind.NORMAL || !youtubeIdentityReady) return
        if (layoutValidationPending) return
        if (contentKindJob?.isActive == true) return
        if (contentOcrJob?.isActive == true) return
        val now = monotonicClock.nowMillis()
        if (now - lastContentOcrElapsedMs < CONTENT_OCR_INTERVAL_MS) return
        lastContentOcrElapsedMs = now
        val generation = screenGeneration
        val capturedKey = currentContentKey

        contentOcrJob = serviceScope.launch {
            val beforeCaptureLayout = contentRegionResolver.layoutKey(ScreenSnapshot.from(rootInActiveWindow))
            val bitmap = takeScreenshotBitmap() ?: return@launch
            val captureSnapshot = ScreenSnapshot.from(rootInActiveWindow)
            val captureLayout = contentRegionResolver.layoutKey(captureSnapshot)
            try {
                if (beforeCaptureLayout != captureLayout) return@launch
                val frame = ocrProcessor.recognizeFrame(InputImage.fromBitmap(bitmap, 0))
                    .onFailure { error -> Log.w(TAG, "YouTube 분류용 OCR 실패", error) }
                    .getOrNull() ?: return@launch
                refreshForegroundScreen(force = true)
                if (!isCurrentYoutubeFrame(generation, capturedKey) || !youtubeIdentityReady ||
                    currentScreenState != ShortformScreenState.INSIDE || currentContentKind != ShortformContentKind.NORMAL) return@launch
                // OCR은 분류용 텍스트만 갱신한다. 식별자 생성·영상 전환에는 절대 사용하지 않는다.
                latestOcrContentKey = capturedKey
                if (captureLayout != contentRegionResolver.layoutKey(currentSnapshot)) return@launch
                latestOcrText = ocrTextSanitizer.sanitizeYoutube(frame, captureSnapshot)
                latestOcrElapsedMs = monotonicClock.nowMillis()
            } finally {
                bitmap.recycle()
            }
        }
    }

    private fun stopShortformTracking() {
        (appManagers[SupportedPlatform.INSTAGRAM.packageName] as? InstagramManager)?.resetTracking()

        youtubeLayoutKey = null
        layoutValidationPending = false
        screenGeneration++
        contentKindConfirmation.reset()
        contentKindJob?.cancel()
        contentKindJob = null
        contentOcrJob?.cancel()
        contentOcrJob = null
        viewTracker.onScreenChanged(false, null, null, ShortformContentKind.UNKNOWN)
        identityResolver.reset()
        youtubeLiveSession.reset()
        currentIsShortform = false
        currentScreenState = ShortformScreenState.OUTSIDE
        currentContentKey = null
        currentContentKind = ShortformContentKind.UNKNOWN
        lastContentOcrElapsedMs = 0L
        youtubeIdentityReady = false
        lastContentClassificationElapsedMs = -CONTENT_RECHECK_INTERVAL_MS
        clearLatestOcr()
    }

    private fun logRecognitionHealthIfDue() {
        val now = monotonicClock.nowMillis()
        if (now - lastHealthLogElapsedMs < 60_000L) return
        lastHealthLogElapsedMs = now
        if (BuildConfig.DEBUG) {
            val health = recognitionHealth.snapshot()
            Log.d("YoutubeRecognitionHealth", "$health; needsReview=${health.needsReview}")
        }
    }

    private fun clearLatestOcr() {
        latestOcrContentKey = null
        latestOcrText = ""
        latestOcrElapsedMs = 0L
    }

    private suspend fun refreshSessionIfNeeded(force: Boolean = false) = sessionTransitionMutex.withLock {
        val transition = sessionManager.pendingTransition(force) ?: return@withLock
        transition.previous?.let { previous ->
            if (qualitySeconds > 0) dependencies.habitStore.heartbeat(previous.userId, previous.date, qualitySeconds,
                java.time.LocalTime.now().hour < 21, dependencies.wallClock.now().time)
        }
        qualitySeconds = 0L; qualityElapsed = 0L
        qualityRemainderMs = 0L
        continuousWatchSec = 0L; continuousLastElapsed = 0L; continuousNoticeSent = false
        sessionReady = false

        // 이전 세션을 활성 상태로 둔 채 마지막 사용량과 숏폼을 정산한다.
        accountAndPersistCurrentRunTime()
        if (::viewTracker.isInitialized) stopShortformTracking()
        entryEvidenceGrace.reset()
        recognitionHealth = com.example.dopaminecut2.logic.shortform.RecognitionHealth()
        if (transition.previous?.userId != transition.current?.userId) {
            abandonClassifications("ACCOUNT_CHANGED")
        } else {
            flushClassificationBatch()
        }
        drainUsageWrites()
        onboardingObservationJob?.cancel()
        onboardingObservationJob = null
        goalObservationJob?.cancel()
        dismissGoalOverlay()
        goalPresenter.clearNotifications()

        sessionManager.activate(transition.current)
        resetInMemorySession()
        val current = transition.current ?: return@withLock

        if (force || transition.previous?.userId != current.userId) {
            usageStatistics.finalizePendingClassifications(current.userId, "SESSION_RESTARTED")
                .onFailure { reportFailure("미완료 분류 복구", it) }
        }

        runCatching { dependencies.onboardingStore.observeOnboarding(current.userId).first() }
            .onSuccess {
                managedSetupReady = it.stage == com.example.dopaminecut2.data.local.OnboardingStage.COMPLETE
            }
            .onFailure { reportFailure("개입 준비 상태 조회", it) }
        userRepository.getDailyStatistics(current.userId, current.date)
            .onSuccess { stats ->
                managedUsage.putAll(stats?.appUsage.orEmpty())
                managedUsageReady = true
                usageStatistics.prepareSession(current.userId, current.date, stats)
            }
            .onFailure { reportFailure("오늘 통계 조회", it) }

        sessionReady = dependencies.authRepository.currentUserId() == current.userId
        runCatching { dependencies.interventionLedgerStore.loadInterventionLedger(current.userId, current.date) }
            .onSuccess { value ->
                ledger = value
                val wallNow = dependencies.wallClock.now().time
                value.snoozedUntil.forEach { (key, until) ->
                    snoozeElapsedUntil[key] = monotonicClock.nowMillis() + (until - wallNow).coerceIn(0L, 300_000L)
                }
            }.onFailure { reportFailure("개입 이력 복원", it) }
        goalObservationJob = serviceScope.launch {
            combine(dependencies.goalStore.observeGoals(current.userId), dependencies.notificationSettingsStore.observeNotificationSettings(current.userId)) {
                    goals, settings -> goals to settings.isEnabled(com.example.dopaminecut2.data.local.NotificationOption.GOAL_PROGRESS)
            }.catch { reportFailure("목표 관찰", it) }.collect { (goals, notificationsEnabled) ->
                if (sessionManager.active() != current || dependencies.authRepository.currentUserId() != current.userId) return@collect
                managedGoals.filter { old -> old !in goals }.forEach { goalPresenter.clearNotification(it.metric) }
                if (managedGoals.firstOrNull { it.metric == com.example.dopaminecut2.domain.GoalMetric.DAILY_TIME } != goals.firstOrNull { it.metric == com.example.dopaminecut2.domain.GoalMetric.DAILY_TIME }) {
                    continuousWatchSec = 0; continuousNoticeSent = false
                }
                managedGoals = goals
                managedGoalsLoaded = true
                goalNotificationsEnabled = notificationsEnabled
                if (!notificationsEnabled) goalPresenter.clearNotifications()
                if (overlayGoal != null && overlayGoal !in goals.filter { it.status == com.example.dopaminecut2.domain.GoalStatus.ACTIVE }) dismissGoalOverlay()
                enforceLimits()
            }
        }

        onboardingObservationJob = serviceScope.launch {
            dependencies.onboardingStore.observeOnboarding(current.userId)
                .catch { reportFailure("개입 준비 상태 동기화", it) }
                .collect { progress ->
                    if (current.userId == sessionManager.active()?.userId) {
                        managedSetupReady = progress.stage == com.example.dopaminecut2.data.local.OnboardingStage.COMPLETE
                        if (!managedSetupReady) dismissGoalOverlay()
                    }
                }
        }
    }

    private fun resetInMemorySession() {
        managedGoals = emptyList()
        managedGoalsLoaded = false
        lastManagedFailureElapsedMs = -30_000L
        managedUsage.clear()
        managedCheckpoints.clear()
        ledger = com.example.dopaminecut2.data.local.InterventionLedger()
        snoozeElapsedUntil.clear()
        managedUsageReady = false
        managedSetupReady = false
        goalNotificationsEnabled = true
        managedEnforcementBusy = false
        lastManagedDismissElapsedMs = -2_000L
        currentAppManager = null
        currentSnapshot = ScreenSnapshot(emptyList(), emptyList())
        currentIsShortform = false
        currentScreenState = ShortformScreenState.OUTSIDE
        youtubeLiveSession.reset()
        currentContentKey = null
        currentContentKind = ShortformContentKind.UNKNOWN
        lastContentOcrElapsedMs = 0L
        clearLatestOcr()
        currentAppStartElapsedMs = 0L
        lastRunTimeFlushElapsedMs = 0L
    }

    private fun onViewCountDelta(delta: ShortformCountDelta) {
        val owner = normalSessions[delta.viewSessionId]
        if (delta.contentKind == ShortformContentKind.NORMAL && owner != null && !owner.classificationStarted) {
            owner.classificationStarted = true
            captureAndQueueClassification(delta, owner)
        }
        enforceLimits()
    }

    private fun onProvisionalCheckpoint(checkpoint: com.example.dopaminecut2.logic.shortform.ProvisionalViewCheckpoint) {
        val session = sessionManager.active() ?: return
        val record = com.example.dopaminecut2.data.local.PendingRecognition(
            session.userId, session.date, checkpoint.viewSessionId, checkpoint.platform,
            checkpoint.durationSec, checkpoint.ended, dependencies.wallClock.now().time
        )
        enqueueUsageWrite {
            runCatching { dependencies.pendingRecognitionStore.upsertPendingRecognition(record) }
                .onFailure { reportFailureOnMain("미확인 시청 기록 저장", it) }
        }
    }

    private fun onProvisionalSettled(sessionId: String) {
        val userId = sessionManager.active()?.userId ?: return
        enqueueUsageWrite {
            runCatching { dependencies.pendingRecognitionStore.removePendingRecognition(userId, sessionId) }
                .onFailure { reportFailureOnMain("미확인 시청 기록 정리", it) }
        }
    }

    private fun onTrackedSessionCheckpoint(checkpoint: ShortformSessionCheckpoint) {
        if (BuildConfig.DEBUG && checkpoint.platform == SupportedPlatform.INSTAGRAM &&
            File(cacheDir, "instagram-diagnostics.enabled").exists()) {
            Log.d("InstagramTrackingDiagnostic", "CHECKPOINT session=${checkpoint.viewSessionId} " +
                    "durationSec=${checkpoint.durationSec} count=${checkpoint.count}")
        }

        val previous = managedCheckpoints[checkpoint.viewSessionId]
        val usage = managedUsage[checkpoint.platform.storageKey] ?: com.example.dopaminecut2.data.model.AppUsage()
        managedUsage[checkpoint.platform.storageKey] = usage.copy(
            shortformTimeSec = usage.shortformTimeSec + (checkpoint.durationSec - (previous?.durationSec ?: 0L)).coerceAtLeast(0L),
            shortformCount = usage.shortformCount + (checkpoint.count - (previous?.count ?: 0L)).coerceAtLeast(0L))
        managedCheckpoints[checkpoint.viewSessionId] = checkpoint
        if (BuildConfig.DEBUG && File(cacheDir, "identity-diagnostics.enabled").exists() &&
            checkpoint.platform == SupportedPlatform.YOUTUBE) {
            Log.d("YoutubePipelineDiagnostic", "usage kind=${checkpoint.contentKind}; key=${checkpoint.contentKey.take(12)}; durationSec=${checkpoint.durationSec}; count=${checkpoint.count}")
        }
        val session = sessionManager.active() ?: return
        val occurredAtEpochMs = dependencies.wallClock.now().time
        val newTime = (checkpoint.durationSec - (previous?.durationSec ?: 0L)).coerceAtLeast(0L)
        val owner = if (checkpoint.contentKind == ShortformContentKind.NORMAL) {
            normalSessions.getOrPut(checkpoint.viewSessionId) {
                NormalSessionOwner(session.userId, session.date, checkpoint.viewSessionId, emptyList())
            }
        } else null
        enqueueUsageWrite {
            dependencies.habitStore.addTime(session.userId, session.date, newTime,
                java.time.Instant.ofEpochMilli(occurredAtEpochMs).atZone(java.time.ZoneId.systemDefault()).hour < 21)
            val result = usageStatistics.checkpointShortformSession(
                userId = session.userId,
                date = session.date,
                viewSessionId = checkpoint.viewSessionId,
                platform = checkpoint.platform,
                durationSec = checkpoint.durationSec,
                count = checkpoint.count,
                occurredAtEpochMs = occurredAtEpochMs,
                initialCategory = if (checkpoint.contentKind == ShortformContentKind.LIVE) {
                    ContentCategory.LIVE
                } else ContentCategory.UNKNOWN
            )
            owner?.recorded?.complete(result.isSuccess)
            result.onFailure { reportFailureOnMain("시청량 저장", it) }
            if (result.isSuccess && checkpoint.contentKind == ShortformContentKind.PHOTO_POST) {
                usageStatistics.resolveShortformCategory(session.userId, session.date, checkpoint.viewSessionId,
                    com.example.dopaminecut2.statistics.UsageClassification(
                        ContentCategory.UNKNOWN, "UNRESOLVED", "ON_DEVICE", reason = "PHOTO_POST_TIME_ONLY"
                    )).onFailure { reportFailureOnMain("사진 게시물 시간 저장", it) }
            }
        }
    }

    private fun onTrackedSessionCompleted(completed: CompletedShortformSession) {
        managedCheckpoints.remove(completed.viewSessionId)
        val owner = normalSessions[completed.viewSessionId] ?: return
        owner.completed = true
        if (owner.resolved) normalSessions.remove(completed.viewSessionId)
    }

    private fun isCurrentClassificationFrame(
        delta: ShortformCountDelta,
        owner: NormalSessionOwner,
        generation: Long
    ): Boolean = serviceActive && !owner.completed && !owner.resolved &&
            dependencies.authRepository.currentUserId() == owner.userId &&
            screenGeneration == generation && currentContentKey == delta.contentKey &&
            currentAppManager?.platform == delta.platform &&
            currentScreenState == ShortformScreenState.INSIDE &&
            viewTracker.activeViewSessionId() == delta.viewSessionId &&
            (delta.platform != SupportedPlatform.YOUTUBE || youtubeIdentityReady && currentContentKind == ShortformContentKind.NORMAL)

    private fun captureAndQueueClassification(delta: ShortformCountDelta, owner: NormalSessionOwner) {
        if (!contentAnalysisEnabled) {
            resolveCategory(owner, ClassifiedContent.unresolved(owner.viewSessionId, "ANALYSIS_DISABLED"))
            return
        }
        val generation = screenGeneration
        // YouTube는 전체 접근성 텍스트로 폴백하면 제외한 작성자·제목이 다시 섞인다.
        val fallbackText = if (delta.platform == SupportedPlatform.YOUTUBE) ""
        else ocrTextSanitizer.sanitizeFallback(currentSnapshot.texts)
        classificationScope.launch {
            try {
                if (!owner.recorded.await()) {
                    resolveCategory(owner, ClassifiedContent.unresolved(delta.viewSessionId, "LOCAL_STORAGE_ERROR"))
                    return@launch
                }
                refreshForegroundScreen(force = true)
                if (!isCurrentClassificationFrame(delta, owner, generation)) {
                    resolveCategory(owner, ClassifiedContent.unresolved(delta.viewSessionId, "SCREEN_CHANGED"))
                    return@launch
                }
                val cachedText = latestOcrText.takeIf {
                    latestOcrContentKey == delta.contentKey &&
                            monotonicClock.nowMillis() - latestOcrElapsedMs <= OCR_CACHE_MAX_AGE_MS && it.isNotBlank()
                }
                val recognizedText = cachedText ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val beforeCaptureLayout = contentRegionResolver.layoutKey(ScreenSnapshot.from(rootInActiveWindow))
                    val bitmap = takeScreenshotBitmap()
                    val captureSnapshot = ScreenSnapshot.from(rootInActiveWindow)
                    val captureLayout = contentRegionResolver.layoutKey(captureSnapshot)
                    try {
                        refreshForegroundScreen(force = true)
                        if (!isCurrentClassificationFrame(delta, owner, generation) ||
                            delta.platform == SupportedPlatform.YOUTUBE && beforeCaptureLayout != captureLayout) {
                            resolveCategory(owner, ClassifiedContent.unresolved(delta.viewSessionId, "SCREEN_CHANGED"))
                            return@launch
                        }
                        bitmap?.let { image ->
                            ocrProcessor.recognizeFrame(InputImage.fromBitmap(image, 0)).getOrNull()
                                ?.let { frame ->
                                    if (delta.platform == SupportedPlatform.YOUTUBE) {
                                        refreshForegroundScreen(force = true)
                                        if (captureLayout != contentRegionResolver.layoutKey(currentSnapshot) ||
                                            !isCurrentClassificationFrame(delta, owner, generation)) ""
                                        else ocrTextSanitizer.sanitizeYoutube(frame, captureSnapshot)
                                    }
                                    else ocrTextSanitizer.sanitize(frame)
                                }
                        }
                    } finally {
                        bitmap?.recycle()
                    }
                } else null
                refreshForegroundScreen(force = true)
                if (!isCurrentClassificationFrame(delta, owner, generation)) {
                    resolveCategory(owner, ClassifiedContent.unresolved(delta.viewSessionId, "SCREEN_CHANGED"))
                    return@launch
                }
                val text = recognizedText?.takeIf(String::isNotBlank) ?: fallbackText
                if (text.isBlank()) {
                    resolveCategory(owner, ClassifiedContent.unresolved(delta.viewSessionId, "INSUFFICIENT_TEXT"))
                    return@launch
                }
                classificationQueue.offer(
                    PendingContentClassification(
                        viewSessionId = delta.viewSessionId,
                        contentKey = delta.contentKey,
                        platform = delta.platform,
                        ocrText = text,
                        enqueuedAtElapsedMs = monotonicClock.nowMillis(),
                        ownerUserId = owner.userId
                    )
                )?.let(::dispatchClassificationBatch)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "분류용 OCR 실패: ${error.javaClass.simpleName}")
                resolveCategory(owner, ClassifiedContent.unresolved(delta.viewSessionId, "OCR_ERROR"))
            }
        }
    }

    private fun dispatchClassificationBatch(batch: ContentClassificationBatch) {
        classificationScope.launch {
            val classified = batchClassifier.classify(batch.items).getOrElse {
                Log.w(TAG, "콘텐츠 분류 실패: 시청량은 유지하고 미확인으로 처리합니다.")
                batch.items.map { item ->
                    ClassifiedContent.unresolved(item.viewSessionId, "PROVIDER_ERROR")
                }
            }
            classified.forEach { result ->
                normalSessions[result.viewSessionId]?.let { owner ->
                    val ownedResult = if (dependencies.authRepository.currentUserId() == owner.userId) {
                        result
                    } else ClassifiedContent.unresolved(result.viewSessionId, "ACCOUNT_CHANGED")
                    resolveCategory(owner, ownedResult)
                }
            }
        }
    }

    private fun resolveCategory(owner: NormalSessionOwner, result: ClassifiedContent) {
        if (owner.resolved) return
        owner.resolved = true
        enqueueUsageWrite {
            usageStatistics.resolveShortformCategory(
                owner.userId, owner.date, owner.viewSessionId,
                UsageClassification(
                    category = result.category,
                    status = result.status.name,
                    source = result.source.name,
                    reason = result.reason,
                    modelVersion = result.modelVersion,
                    taxonomyVersion = result.taxonomyVersion,
                    confidence = result.confidence,
                    deductedScore = DopamineScorePolicy.deductedScore(result.category, owner.restrictions)
                )
            ).onFailure { reportFailureOnMain("콘텐츠 분류 저장", it) }
        }
        if (owner.completed) normalSessions.remove(owner.viewSessionId)
    }

    private fun flushClassificationBatch() {
        classificationQueue.flush()?.let(::dispatchClassificationBatch)
    }

    private fun abandonClassifications(reason: String) {
        classificationScope.coroutineContext.cancelChildren()
        classificationQueue.flush() // Drop volatile OCR; persisted usage remains.
        normalSessions.values.toList().forEach { owner ->
            resolveCategory(owner, ClassifiedContent.unresolved(owner.viewSessionId, reason))
        }
        normalSessions.clear()
    }

    private fun enqueueUsageWrite(write: suspend () -> Unit) {
        if (usageWrites.trySend(write).isFailure) {
            reportFailureOnMain("시청 기록 저장", IllegalStateException("저장 작업이 종료되었습니다."))
        }
    }

    private suspend fun drainUsageWrites() {
        val drained = CompletableDeferred<Unit>()
        enqueueUsageWrite { drained.complete(Unit) }
        drained.await()
    }

    @SuppressLint("NewApi")
    private suspend fun takeScreenshotBitmap(): Bitmap? = screenshotMutex.withLock {
        val now = monotonicClock.nowMillis()
        val remainingDelay = SCREENSHOT_MIN_INTERVAL_MS - (now - lastScreenshotElapsedMs)
        if (remainingDelay > 0L) delay(remainingDelay)
        // 캡처 간격을 기다리는 동안 사용자가 다른 화면으로 이동할 수 있다.
        val manager = currentAppManager ?: return@withLock null
        if (!serviceActive || !com.example.dopaminecut2.logic.shortform.RecognitionSafety.canProbe(currentScreenState)) return@withLock null
        if (!getSystemService(PowerManager::class.java).isInteractive ||
            getSystemService(KeyguardManager::class.java).isKeyguardLocked) return@withLock null
        val root = rootInActiveWindow ?: return@withLock null
        if (root.packageName?.toString() != manager.packageName) return@withLock null
        if (!com.example.dopaminecut2.logic.shortform.RecognitionSafety.canProbe(manager.detectShortformScreen(ScreenSnapshot.from(root)).state)) {
            return@withLock null
        }
        suspendCancellableCoroutine { continuation ->
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutorCompat,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        lastScreenshotElapsedMs = monotonicClock.nowMillis()
                        val hardwareBuffer = screenshot.hardwareBuffer
                        val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshot.colorSpace)
                            ?.copy(Bitmap.Config.ARGB_8888, false)
                        hardwareBuffer.close()
                        if (continuation.isActive) continuation.resume(bitmap) else bitmap?.recycle()
                    }

                    override fun onFailure(errorCode: Int) {
                        lastScreenshotElapsedMs = monotonicClock.nowMillis()
                        Log.w(TAG, "화면 캡처 실패: $errorCode")
                        if (continuation.isActive) continuation.resume(null)
                    }
                }
            )
        }
    }

    private fun flushRunTimeIfDue() {
        val now = monotonicClock.nowMillis()
        if (currentAppManager != null && now - lastRunTimeFlushElapsedMs >= RUN_TIME_FLUSH_MS) {
            accountAndPersistCurrentRunTime()
        }
    }

    private fun accountAndPersistCurrentRunTime() {
        val manager = currentAppManager ?: return
        val session = sessionManager.active() ?: return
        if (currentAppStartElapsedMs <= 0L) return

        val now = monotonicClock.nowMillis()
        val durationSec = (now - currentAppStartElapsedMs) / 1_000L
        if (durationSec <= 0L) return

        val usage = managedUsage[manager.platform.storageKey] ?: com.example.dopaminecut2.data.model.AppUsage()
        managedUsage[manager.platform.storageKey] = usage.copy(runTimeSec = usage.runTimeSec + durationSec)
        currentAppStartElapsedMs += durationSec * 1_000L
        lastRunTimeFlushElapsedMs = now
        enqueueUsageWrite {
            usageStatistics.recordRunTime(
                userId = session.userId,
                date = session.date,
                platform = manager.platform,
                runTimeSec = durationSec
            ).onFailure { reportFailureOnMain("앱 사용 시간 저장", it) }
        }
    }

    private fun enforceLimits() {
        if (measurementEnabled) enforceManagedGoals()
    }

    private fun enforceManagedGoals() {
        val session = sessionManager.active() ?: return
        val manager = currentAppManager ?: return
        if (!sessionReady || !managedSetupReady || !managedUsageReady || !managedGoalsLoaded || managedEnforcementBusy || goalPresenter.isShowing ||
            monotonicClock.nowMillis() - lastManagedDismissElapsedMs < 2_000L ||
            monotonicClock.nowMillis() - lastManagedFailureElapsedMs < 30_000L ||
            dependencies.authRepository.currentUserId() != session.userId) return
        val activeAppSec = if (currentAppStartElapsedMs > 0) (monotonicClock.nowMillis() - currentAppStartElapsedMs) / 1000 else 0
        val active = viewTracker.activeProgress()
        val effective = com.example.dopaminecut2.logic.ManagedGoalProgress.effectiveUsage(
            managedUsage, manager.platform, activeAppSec, active, active?.let { managedCheckpoints[it.viewSessionId] }
        )
        val eligible = currentScreenState == ShortformScreenState.INSIDE &&
                currentContentKind in listOf(ShortformContentKind.NORMAL, ShortformContentKind.LIVE, ShortformContentKind.PHOTO_POST) &&
                (manager.platform != SupportedPlatform.YOUTUBE || youtubeIdentityReady)
        val requests = com.example.dopaminecut2.logic.ManagedGoalPolicy.evaluate(managedGoals, effective, manager.platform,
            eligible, currentContentKind == ShortformContentKind.NORMAL)
        managedEnforcementBusy = true
        try {
            for (request in requests) {
                val goal = request.goal
                val action = com.example.dopaminecut2.logic.GoalInterventionExecutionPolicy.action(
                    request, goalNotificationsEnabled, request.key in ledger.notified,
                    snoozeElapsedUntil[request.key] ?: 0L, monotonicClock.nowMillis()
                )
                if (action == com.example.dopaminecut2.logic.GoalInterventionAction.NONE) continue
                if (action == com.example.dopaminecut2.logic.GoalInterventionAction.NOTIFY) {
                    if (goalPresenter.notify(request)) {
                        ledger = ledger.copy(notified = (ledger.notified + request.key).toList().takeLast(100).toSet())
                        persistLedger()
                    }
                } else {
                    accountAndPersistCurrentRunTime()
                    currentAppStartElapsedMs = 0L
                    viewTracker.setPaused(true)
                    currentScreenState = ShortformScreenState.OVERLAY
                    overlayGoal = goal
                    try {
                        goalPresenter.show(goal, finish = {
                            dismissGoalOverlay()
                            if (goal.metric == com.example.dopaminecut2.domain.GoalMetric.APP_TIME) performGlobalAction(GLOBAL_ACTION_HOME)
                            else performGlobalAction(GLOBAL_ACTION_BACK)
                        }, extend = {
                            snoozeElapsedUntil[request.key] = monotonicClock.nowMillis() + 300_000L
                            ledger = ledger.copy(snoozedUntil = ledger.snoozedUntil + (request.key to (dependencies.wallClock.now().time + 300_000L)))
                            persistLedger()
                            dismissGoalOverlay()
                        }, settings = {
                            dismissGoalOverlay()
                            runCatching { startActivity(android.content.Intent(this, com.example.dopaminecut2.main.MainActivity::class.java)
                                .putExtra("open_goal_settings", true)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                                .onFailure { reportFailure("목표 설정 화면 열기", it) }
                        })
                    } catch (error: Exception) {
                        lastManagedFailureElapsedMs = monotonicClock.nowMillis()
                        dismissGoalOverlay()
                        reportFailure("개입 화면 표시", error)
                    }
                    break
                }
            }
        } finally { managedEnforcementBusy = false }
    }

    private fun persistLedger() {
        val session = sessionManager.active() ?: return
        val captured = ledger
        enqueueUsageWrite {
            runCatching { dependencies.interventionLedgerStore.saveInterventionLedger(session.userId, session.date, captured) }
                .onFailure { reportFailureOnMain("개입 이력 저장", it) }
        }
    }

    private fun dismissGoalOverlay() {
        if (!::goalPresenter.isInitialized || (!goalPresenter.isShowing && overlayGoal == null)) { overlayGoal = null; return }
        goalPresenter.dismiss()
        lastManagedDismissElapsedMs = monotonicClock.nowMillis()
        overlayGoal = null
        if (::viewTracker.isInitialized) viewTracker.setPaused(false)
        if (::monotonicClock.isInitialized && currentAppManager != null) {
            currentAppStartElapsedMs = monotonicClock.nowMillis()
            lastRunTimeFlushElapsedMs = currentAppStartElapsedMs
        }
    }

    private suspend fun recordQualityHeartbeat() {
        val now = monotonicClock.nowMillis()
        val delta = if (qualityElapsed > 0) now - qualityElapsed else 0L
        qualityElapsed = now
        val session = sessionManager.active() ?: return
        if (measurementEnabled && delta in 1..5_000 && getSystemService(PowerManager::class.java).isInteractive) {
            qualityRemainderMs += delta
            qualitySeconds += qualityRemainderMs / 1000
            qualityRemainderMs %= 1000
        }
        if (measurementEnabled && now - qualityLastWrite >= 30_000) {
            continuousBreakMinutes = dependencies.habitStore.plans(session.userId)[com.example.dopaminecut2.domain.GoalMetric.DAILY_TIME]
                ?.takeIf { plan -> managedGoals.any { it.metric == plan.metric && it.revision == plan.revision } }?.continuousBreakMinutes ?: 0
            dependencies.habitStore.heartbeat(session.userId, session.date, qualitySeconds,
                java.time.LocalTime.now().hour < 21, dependencies.wallClock.now().time)
            qualitySeconds = 0; qualityLastWrite = now
        }
    }

    private fun enforceContinuousBreak() {
        val now = monotonicClock.nowMillis()
        val elapsedMs = if (continuousLastElapsed > 0) (now - continuousLastElapsed).coerceIn(0L, 2_000L) else 0
        continuousLastElapsed = now
        if (!measurementEnabled || currentScreenState == ShortformScreenState.OUTSIDE || currentAppManager == null) {
            continuousWatchSec = 0; continuousRemainderMs = 0; continuousNoticeSent = false; return
        }
        if (currentScreenState != ShortformScreenState.INSIDE || currentContentKind in setOf(ShortformContentKind.UNKNOWN, ShortformContentKind.AD)) return
        val goal = managedGoals.firstOrNull { it.metric == com.example.dopaminecut2.domain.GoalMetric.DAILY_TIME &&
                it.status == com.example.dopaminecut2.domain.GoalStatus.ACTIVE && currentAppManager?.platform in it.platforms } ?: return
        continuousRemainderMs += elapsedMs
        continuousWatchSec += continuousRemainderMs / 1000
        continuousRemainderMs %= 1000
        if (continuousBreakMinutes > 0 && continuousWatchSec >= continuousBreakMinutes * 60L &&
            !continuousNoticeSent && goalNotificationsEnabled && goal.intervention != com.example.dopaminecut2.domain.InterventionMode.RECORD_ONLY && !goalPresenter.isShowing) {
            continuousNoticeSent = true
            goalPresenter.notifyRest(continuousBreakMinutes)
        }
    }

    private fun retryPendingIfDue(force: Boolean = false) {
        if (!measurementEnabled) return
        if (retryInProgress) return
        val userId = sessionManager.active()?.userId ?: return
        val now = monotonicClock.nowMillis()
        if (!force && now - lastRetryElapsedMs < RETRY_INTERVAL_MS) return
        lastRetryElapsedMs = now
        retryInProgress = true
        dependencies.applicationScope.launch {
            try {
                usageStatistics.retryPending(userId, RETRY_BATCH_SIZE)
            } finally {
                retryInProgress = false
            }
        }.also { job ->
            dependencies.dataControls.finishingJobs.add(job)
            job.invokeOnCompletion { dependencies.dataControls.finishingJobs.remove(job) }
        }
    }

    private fun reportFailure(operation: String, error: Throwable) {
        Log.e(TAG, "$operation 실패", error)
        Toast.makeText(
            this,
            "$operation 실패: ${error.localizedMessage ?: "알 수 없는 오류"}",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun reportFailureOnMain(operation: String, error: Throwable) {
        Log.e(TAG, "$operation 실패", error)
        if (serviceActive) showToastOnMain("$operation 실패: ${error.localizedMessage ?: "알 수 없는 오류"}")
    }

    private fun showToastOnMain(message: String) {
        mainHandler.post {
            if (serviceActive) Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    override fun onInterrupt() {
        if (::viewTracker.isInitialized) stopShortformTracking()
    }

    override fun onDestroy() {
        usageWriteJob?.let { job ->
            dependencies.dataControls.finishingJobs.add(job)
            job.invokeOnCompletion { dependencies.dataControls.finishingJobs.remove(job) }
        }
        if (::dependencies.isInitialized) dependencies.dataControls.quiesceService = null
        dismissGoalOverlay()
        if (::goalPresenter.isInitialized) goalPresenter.clearNotifications()
        goalObservationJob?.cancel()
        serviceActive = false
        sessionReady = false
        accountAndPersistCurrentRunTime()
        if (::viewTracker.isInitialized) viewTracker.release()
        abandonClassifications("SERVICE_STOPPED")
        classificationScope.cancel()
        usageWrites.close()
        if (::ocrProcessor.isInitialized) ocrProcessor.close()
        youtubeContentClassifier?.close()
        instagramContentClassifier?.close()
        onboardingObservationJob?.cancel()
        tickerJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "AppBlockService"
        const val TICK_INTERVAL_MS = 1_000L
        const val SCREEN_REFRESH_INTERVAL_MS = 150L
        const val CONTENT_RECHECK_INTERVAL_MS = 1_000L
        const val CONTENT_SETTLE_MS = 150L
        const val CONTENT_OCR_INTERVAL_MS = 1_500L
        const val SCREENSHOT_MIN_INTERVAL_MS = 500L
        const val OCR_CACHE_MAX_AGE_MS = 3_000L
        const val RUN_TIME_FLUSH_MS = 10_000L
        const val RETRY_INTERVAL_MS = 5 * 60 * 1_000L
        const val RETRY_BATCH_SIZE = 31
    }
}
