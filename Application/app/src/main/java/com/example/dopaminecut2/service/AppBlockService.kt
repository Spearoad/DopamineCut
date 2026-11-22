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
import com.example.dopaminecut2.logic.ai.ContentSamplingPolicy
import com.example.dopaminecut2.logic.ai.FrameCaptureGate
import com.example.dopaminecut2.logic.manager.ContentTrackingCommand
import com.example.dopaminecut2.logic.manager.ContentTrackingContext
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
import com.example.dopaminecut2.logic.shortform.KakaotalkOnDeviceContentClassifier
import com.example.dopaminecut2.logic.shortform.KakaotalkFrameToken
import com.example.dopaminecut2.logic.shortform.KakaotalkRecognitionPipeline
import com.example.dopaminecut2.logic.shortform.KakaotalkRecognitionState
import com.example.dopaminecut2.logic.shortform.KakaotalkSessionBinder
import com.example.dopaminecut2.logic.shortform.InstagramRecognitionPipeline
import com.example.dopaminecut2.logic.shortform.InstagramRecognitionState
import com.example.dopaminecut2.logic.shortform.InstagramFrameToken
import com.example.dopaminecut2.logic.shortform.YoutubeOnDeviceContentClassifier
import com.example.dopaminecut2.session.ServiceSessionManager
import com.example.dopaminecut2.statistics.UsageClassification
import com.example.dopaminecut2.statistics.UsageStatisticsManager
import com.example.dopaminecut2.time.MonotonicClock
import com.example.dopaminecut2.service.runtime.ServiceTaskRunner
import com.example.dopaminecut2.service.runtime.FailureNoticeLimiter
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import com.example.dopaminecut2.statistics.UsageWriteCommand
import com.example.dopaminecut2.statistics.UsageWriteCoordinator
import com.example.dopaminecut2.statistics.UsageWriteExecutor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.asCoroutineDispatcher
import com.example.dopaminecut2.service.runtime.AsyncSnapshotCoordinator
import com.example.dopaminecut2.service.runtime.AccessibilitySnapshotReader
import com.example.dopaminecut2.service.runtime.ForegroundSnapshot
import java.util.concurrent.Executors
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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
    // Screenshot conversion must not queue behind accessibility/UI work on the main thread.
    private val screenshotExecutor = Dispatchers.Default.asExecutor()
    private val recognitionResumeTrace by lazy {
        if (BuildConfig.DEBUG && File(cacheDir, "recognition-resume-trace.enabled").exists())
            com.example.dopaminecut2.service.runtime.RecognitionResumeTrace() else null
    }
    private val taskRunner = ServiceTaskRunner({ android.os.SystemClock.elapsedRealtime() }, ::reportFailureOnMain)
    private val failureNotices = FailureNoticeLimiter()
    private var sessionRefreshJob: Job? = null
    private var goalPlanRefreshJob: Job? = null

    private lateinit var dependencies: AppDependencies
    private lateinit var userRepository: UserRepositoryInterface
    private lateinit var usageStatistics: UsageStatisticsManager
    private lateinit var sessionManager: ServiceSessionManager
    private lateinit var monotonicClock: MonotonicClock
    private lateinit var viewTracker: ShortformSessionTracker
    private lateinit var ocrProcessor: OCRProcessor
    private var youtubeContentClassifier: YoutubeOnDeviceContentClassifier? = null
    private var instagramContentClassifier: InstagramOnDeviceContentClassifier? = null
    private var kakaotalkContentClassifier: KakaotalkOnDeviceContentClassifier? = null
    private lateinit var kakaotalkPipeline: KakaotalkRecognitionPipeline
    private var kakaotalkDiagnosticSignature: String? = null
    private val contentClassifiers: Map<SupportedPlatform, (String?) -> Unit> = mapOf(
        SupportedPlatform.YOUTUBE to ::classifyYoutubeContent
    )
    private lateinit var instagramPipeline: InstagramRecognitionPipeline
    private lateinit var batchClassifier: BatchContentClassifier
    private val classificationQueue = ClassificationBatchQueue()
    private val ocrTextSanitizer = OcrContentTextSanitizer()
    private val contentRegionResolver = com.example.dopaminecut2.logic.ai.YoutubeContentRegionResolver()
    private var youtubeLayoutKey: String? = null
    private var layoutValidationPending = false
    private val identityResolver = VideoIdentitySessionResolver()
    private val youtubeLiveSession = com.example.dopaminecut2.logic.shortform.YoutubeLiveSession()
    private var diagnosticForegroundPackage: String? = null
    private val frameCaptureGate = FrameCaptureGate()
    private val contentKindConfirmation = ContentKindConfirmation()
    private val entryEvidenceGrace = com.example.dopaminecut2.logic.shortform.EntryEvidenceGrace()
    private var recognitionHealth = com.example.dopaminecut2.logic.shortform.RecognitionHealth()
    private var youtubeIdentityReady = false
    private var lastHealthLogElapsedMs = 0L
    private var screenGeneration = 0L
    private var lastContentClassificationElapsedMs = -CONTENT_RECHECK_INTERVAL_MS

    private val appManagers = listOf(
        YoutubeManager(),
        InstagramManager(),
        KakaotalkManager()
    ).associateBy(AppManagerInterface::packageName)

    private val normalSessions = mutableMapOf<String, NormalSessionOwner>()
    // Owned local commands can finish after service cancellation.
    private lateinit var usageWrites: UsageWriteCoordinator
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
    private var latestOcrContentKey: String? = null
    private var latestOcrSessionId: String? = null
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
    private lateinit var powerManager: PowerManager
    private lateinit var keyguardManager: KeyguardManager
    private val snapshotDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "AccessibilitySnapshotReader").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private lateinit var snapshots: AsyncSnapshotCoordinator<com.example.dopaminecut2.session.ServiceSession, ForegroundSnapshot>
    private var foregroundSnapshotReady = false
    private var lastSnapshotDiagnosticMs = -5_000L

    private data class NormalSessionOwner(
        val userId: String,
        val date: String,
        val viewSessionId: String,
        val restrictions: List<ContentCategory>,
        val recorded: CompletableDeferred<Result<Unit>> = CompletableDeferred(),
        var classificationStarted: Boolean = false,
        var completed: Boolean = false,
        var resolved: Boolean = false
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (serviceActive) return
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
        powerManager = getSystemService(PowerManager::class.java)
        keyguardManager = getSystemService(KeyguardManager::class.java)
        sessionManager = ServiceSessionManager(
            dependencies.authRepository,
            dependencies.dateIdProvider
        )
        ocrProcessor = OCRProcessor()
        batchClassifier = dependencies.batchContentClassifier
        val executor = UsageWriteExecutor(usageStatistics, dependencies.habitStore,
            dependencies.pendingRecognitionStore, dependencies.interventionLedgerStore)
        usageWrites = UsageWriteCoordinator(dependencies.applicationScope, monotonicClock::nowMillis,
            executor::execute) { command, error -> reportFailureOnMain(command.operation, error) }
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
            Log.e(TAG, "Instagram CV 모델 초기화 실패: 콘텐츠를 UNKNOWN으로 유지합니다.", error)
        }.getOrNull()

        kakaotalkContentClassifier = runCatching {
            KakaotalkOnDeviceContentClassifier(applicationContext)
        }.onSuccess {
            Log.i(TAG, "✅ KakaoTalk CV 모델 메모리 로드 및 무결성 검증 성공!")
        }.onFailure { error ->
            Log.e(TAG, "❌ KakaoTalk CV 모델 초기화 실패: 접근성 기반 추적만 사용합니다.", error)
        }.getOrNull()

        viewTracker = ShortformSessionTracker(
            clockMs = monotonicClock::nowMillis,
            onQualifiedSession = ::onViewQualified,
            onCheckpoint = ::onTrackedSessionCheckpoint,
            onSessionCompleted = ::onTrackedSessionCompleted,
            onProvisionalCheckpoint = ::onProvisionalCheckpoint,
            onProvisionalSettled = ::onProvisionalSettled,
            admitNormalSession = { platform ->
                platform == currentAppManager?.platform && measurementEnabled &&
                        foregroundSnapshotReady && snapshots.cached() != null &&
                        !enforceManagedGoals(countAdmission = true)
            },
            admitRepeatedNormalSession = { platform ->
                platform == currentAppManager?.platform && measurementEnabled &&
                        foregroundSnapshotReady && snapshots.cached() != null &&
                        !enforceManagedGoals(countAdmission = false)
            }
        )
        val reader = AccessibilitySnapshotReader(this, powerManager, keyguardManager, appManagers.keys)
        snapshots = AsyncSnapshotCoordinator(serviceScope, snapshotDispatcher, monotonicClock::nowMillis,
            owner = { sessionManager.active()?.takeIf { sessionReady && measurementEnabled && serviceActive &&
                    it.userId == dependencies.authRepository.currentUserId() && it.date == dependencies.dateIdProvider.currentDateId() } },
            read = { reader.read() },
            consume = { observed ->
                try {
                    foregroundSnapshotReady = true
                    applyForegroundSnapshot(observed.value)
                    if (BuildConfig.DEBUG && monotonicClock.nowMillis() - lastSnapshotDiagnosticMs >= 5_000) {
                        lastSnapshotDiagnosticMs = monotonicClock.nowMillis()
                        Log.d("UiSnapshotDiagnostic", "event=APPLIED readMs=${observed.completedMs - observed.startedMs} " +
                                "nodes=${observed.value.snapshot?.elements?.size ?: 0} complete=${observed.value.complete} " +
                                "main=${Looper.myLooper() == Looper.getMainLooper()}")
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    pauseAfterMeasurementFailure()
                    reportFailureOnMain("접근성 화면 반영", error)
                }
            },
            rejected = { reason ->
                pauseForUncertainScreen()
                if (BuildConfig.DEBUG) Log.d("UiSnapshotDiagnostic", "event=REJECTED reason=$reason")
            },
            intervalMs = SCREEN_REFRESH_INTERVAL_MS)
        instagramPipeline = InstagramRecognitionPipeline(serviceScope,
            appManagers.getValue(SupportedPlatform.INSTAGRAM.packageName) as InstagramManager,
            monotonicClock::nowMillis,
            owner = { sessionManager.active()?.takeIf { sessionReady && measurementEnabled &&
                    dependencies.authRepository.currentUserId() == it.userId &&
                    dependencies.dateIdProvider.currentDateId() == it.date &&
                    currentAppManager?.platform == SupportedPlatform.INSTAGRAM } },
            infer = ::captureInstagramPrediction, emit = ::applyInstagramState,
            onFailure = { reportFailureOnMain("Instagram 콘텐츠 확인", it) },
            cvAvailable = { instagramContentClassifier != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R })
        kakaotalkPipeline = KakaotalkRecognitionPipeline(serviceScope,
            appManagers.getValue(SupportedPlatform.KAKAOTALK.packageName) as KakaotalkManager,
            monotonicClock::nowMillis,
            owner = { sessionManager.active()?.takeIf { sessionReady && measurementEnabled &&
                    dependencies.authRepository.currentUserId() == it.userId &&
                    dependencies.dateIdProvider.currentDateId() == it.date &&
                    currentAppManager?.platform == SupportedPlatform.KAKAOTALK } },
            infer = ::captureKakaotalkPrediction, emit = ::applyKakaotalkState,
            trace = { if (BuildConfig.DEBUG) Log.d("KakaoTrackingDiagnostic", it) },
            cvAvailable = { kakaotalkContentClassifier != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R },
            onFailure = { reportFailureOnMain("KakaoTalk 콘텐츠 확인", it) })
        dependencies.dataControls.quiesceService = {
            sessionTransitionMutex.withLock {
                measurementEnabled = false
                if (::snapshots.isInitialized) snapshots.invalidate()
                foregroundSnapshotReady = false
                qualitySeconds = 0L
                qualityRemainderMs = 0L
                accountAndPersistCurrentRunTime()
                stopShortformTracking()
                abandonClassifications("MEASUREMENT_PAUSED")
                currentAppManager = null
                dismissGoalOverlay()
                drainUsageWrites()
                // Cloud upload and deletion are serialized by DataControls.withUsageSync.
            }
        }
        serviceScope.launch {
            dependencies.authRepository.authState().collectLatest { uid ->
                if (uid != null) dependencies.habitStore.settings(uid).collect { settings ->
                    val resume = !measurementEnabled && settings.measurement
                    if (!settings.measurement && measurementEnabled) taskRunner.run("측정 중지 정산") {
                        dependencies.dataControls.quiesceService?.invoke()
                    }
                    if (!settings.content && contentAnalysisEnabled) { abandonClassifications("ANALYSIS_DISABLED"); clearLatestOcr() }
                    measurementEnabled = settings.measurement
                    contentAnalysisEnabled = settings.content
                    if (resume) taskRunner.run("측정 재개") { refreshSessionIfNeeded(force = true) }
                }
            }
        }

        serviceScope.launch {
            taskRunner.run("초기 세션 준비") { refreshSessionIfNeeded(force = true) }
            retryPendingIfDue(force = true)
        }
        tickerJob = serviceScope.launch {
            while (isActive) {
                delay(TICK_INTERVAL_MS)
                val ready = taskRunner.run("측정 세션 확인") { refreshSessionIfNeeded(force = !sessionReady) }
                if (!ready || !sessionReady) {
                    pauseAfterMeasurementFailure()
                    continue
                }
                taskRunner.run("측정 품질 확인") { recordQualityHeartbeat() }
                val measured = taskRunner.run("사용량 측정") {
                    if (::snapshots.isInitialized && snapshots.cached() == null) pauseForUncertainScreen()
                    requestForegroundSnapshot()
                    viewTracker.tick()
                    flushRunTimeIfDue()
                }
                if (!measured) { pauseAfterMeasurementFailure(); continue }
                taskRunner.run("콘텐츠 분석 예약") {
                    retryYoutubeContentIfDue()
                    refreshYoutubeContentOcrIfDue()
                    classificationQueue.pollDue(monotonicClock.nowMillis())?.let(::dispatchClassificationBatch)
                }
                taskRunner.run("목표 개입") { enforceLimits(); enforceContinuousBreak() }
                retryPendingIfDue()
            }
        }
        Log.i(TAG, "접근성 감지 서비스가 연결되었습니다.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !::viewTracker.isInitialized) return
        val activeSession = sessionManager.active()
        if (dependencies.authRepository.currentUserId() != activeSession?.userId) {
            if (sessionRefreshJob?.isActive != true) sessionRefreshJob = serviceScope.launch {
                taskRunner.run("계정 전환 확인") { refreshSessionIfNeeded() }
            }
            return
        }

        try {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                snapshots.invalidate()
                pauseForUncertainScreen()
                // Keyboard/system events are hints, not authoritative app exits.
                // Only the worker's active-window result may close the current viewing session.
            }
            requestForegroundSnapshot()
        }
        catch (error: Exception) {
            if (error is CancellationException) throw error
            pauseAfterMeasurementFailure()
            reportFailureOnMain("접근성 화면 확인", error)
        }
    }

    private fun pauseAfterMeasurementFailure() {
        sessionReady = false
        if (::snapshots.isInitialized) snapshots.invalidate()
        foregroundSnapshotReady = false
        screenGeneration++
        contentKindJob?.cancel()
        contentOcrJob?.cancel()
        try {
            if (::instagramPipeline.isInitialized) instagramPipeline.stop()
            if (::kakaotalkPipeline.isInitialized) kakaotalkPipeline.stop()
            viewTracker.setPaused(true)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            reportFailureOnMain("측정 오류 정산", error)
        }
    }

    /** Every caller goes through the same single-reader/coalescing path; force never reads on Main. */
    private fun requestForegroundSnapshot() {
        if (!measurementEnabled) return
        if (!sessionReady || sessionManager.active() == null) return
        if (::snapshots.isInitialized) snapshots.request()
    }

    private suspend fun awaitForegroundSnapshot(notBeforeMs: Long = Long.MIN_VALUE): Boolean {
        if (!serviceActive || !sessionReady || !measurementEnabled || !::snapshots.isInitialized) return false
        val observed = withTimeoutOrNull(1_500) { snapshots.awaitFresh(notBeforeMs = notBeforeMs) }
        return observed != null && foregroundSnapshotReady && snapshots.cached() != null &&
                observed.value.snapshot != null && observed.value.packageName == currentAppManager?.packageName &&
                !goalPresenter.isShowing
    }

    private fun pauseForUncertainScreen() {
        if (!foregroundSnapshotReady) return
        foregroundSnapshotReady = false
        currentIsShortform = false
        screenGeneration++
        contentKindJob?.cancel()
        contentOcrJob?.cancel()
        clearLatestOcr()
        if (::viewTracker.isInitialized) viewTracker.setPaused(true)
    }

    /** Only immutable worker results enter tracking. Non-target app node/text content is never read. */
    private fun applyForegroundSnapshot(observation: ForegroundSnapshot) {
        val foregroundPackage = observation.packageName
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
        val newManager = foregroundPackage?.let(appManagers::get)
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
        if (!observation.complete) {
            // Package/app runtime is still known, but a partial tree cannot confirm viewing.
            pauseForUncertainScreen()
            return
        }
        currentSnapshot = observation.snapshot ?: return
        if (manager.platform == SupportedPlatform.INSTAGRAM) logInstagramSnapshot(currentSnapshot)
        if (manager.platform == SupportedPlatform.KAKAOTALK) logKakaotalkSnapshot(currentSnapshot)
        if (manager.platform == SupportedPlatform.YOUTUBE &&
            detectTrackingScreen(manager).state != ShortformScreenState.OVERLAY) {
            val nextLayout = contentRegionResolver.layoutKey(currentSnapshot)
            if (contentRegionResolver.requiresRevalidation(youtubeLayoutKey, nextLayout, currentContentKey)) {
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
            run {
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
    private class UiSnapshotDiagnostic(val tag: String, val flagPrefix: String) {
        var lastMs = -2_000L
        var signature: String? = null
        var sequence = 0L
    }
    private val instagramUiDiagnostic = UiSnapshotDiagnostic("InstagramUiDiagnostic", "instagram-diagnostics")
    // 카카오톡 댓글·공유창의 실제 접근성 구조를 실기기에서 기록해 오버레이 판별 근거로 쓴다.
    private val kakaotalkUiDiagnostic = UiSnapshotDiagnostic("KakaoUiDiagnostic", "kakaotalk-diagnostics")
    private var instagramTrackingDiagnosticSignature: String? = null

    private fun logInstagramSnapshot(snapshot: ScreenSnapshot) = logUiSnapshot(instagramUiDiagnostic, snapshot)

    private fun logKakaotalkSnapshot(snapshot: ScreenSnapshot) = logUiSnapshot(kakaotalkUiDiagnostic, snapshot)

    private fun logUiSnapshot(diagnostic: UiSnapshotDiagnostic, snapshot: ScreenSnapshot) {
        if (!BuildConfig.DEBUG || !File(cacheDir, "${diagnostic.flagPrefix}.enabled").exists()) return
        val now = monotonicClock.nowMillis()
        if (now - diagnostic.lastMs < 2_000L) return
        diagnostic.lastMs = now
        val includeText = File(cacheDir, "${diagnostic.flagPrefix}-text.enabled").exists()
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
        if (signature == diagnostic.signature) return
        diagnostic.signature = signature
        val sequence = ++diagnostic.sequence
        val detection = currentAppManager?.detectShortformScreen(snapshot)
        Log.d(diagnostic.tag, "BEGIN seq=$sequence elapsedMs=$now count=${rows.size} " +
                "display=${snapshot.displayId} window=${snapshot.window} state=${detection?.state} evidence=${detection?.evidence} text=$includeText")
        rows.forEach { row -> Log.d(diagnostic.tag, "seq=$sequence $row") }
        Log.d(diagnostic.tag, "END seq=$sequence")
    }

    private fun updateInstagramTracking() {
        instagramPipeline.observe(currentSnapshot)
    }

    private fun applyInstagramState(state: InstagramRecognitionState) {
        if (currentAppManager?.platform != SupportedPlatform.INSTAGRAM) return

        // 화면이 바뀐 상황 vs 영상이 바뀐 상황 을 구분하기 위해 코드 추가
        val identityChanged = currentContentKey != state.contentKey
        val screenChanged = currentScreenState != state.screen

        if (identityChanged || screenChanged) {
            screenGeneration++
            contentOcrJob?.cancel()
            contentOcrJob = null
        }

        if (identityChanged || state.screen == ShortformScreenState.OUTSIDE) {
            clearLatestOcr()
        }

        if (identityChanged)
            viewTracker.onScreenChanged(false, null, null, ShortformContentKind.UNKNOWN)
        currentContentKey = state.contentKey
        currentContentKind = state.kind
        currentScreenState = state.screen
        currentIsShortform = state.screen == ShortformScreenState.INSIDE && !state.paused && !state.excluded

        when {
            state.screen == ShortformScreenState.OUTSIDE || state.contentKey == null ->
                viewTracker.onScreenChanged(false, null, null, ShortformContentKind.UNKNOWN)
            state.excluded || state.kind == ShortformContentKind.AD -> {
                val provisionalId = viewTracker.activeViewSessionId()?.takeIf { viewTracker.isProvisional() }
                if (provisionalId != null) {
                    viewTracker.resolveProvisional(provisionalId, ShortformContentKind.AD, state.contentKey)
                } else {
                    viewTracker.onScreenChanged(false, null, null, ShortformContentKind.AD)
                }
            }
            state.paused -> viewTracker.setPaused(true)
            state.kind == ShortformContentKind.UNKNOWN -> {
                if (!goalPresenter.isShowing) {
                    viewTracker.beginProvisional(SupportedPlatform.INSTAGRAM, state.contentKey)
                }
                viewTracker.setPaused(goalPresenter.isShowing)
            }
            else -> {
                val provisionalId = viewTracker.activeViewSessionId()?.takeIf { viewTracker.isProvisional() }
                val promoted = provisionalId != null &&
                        viewTracker.resolveProvisional(provisionalId, state.kind, state.contentKey)
                if (!promoted && !goalPresenter.isShowing) viewTracker.onScreenChanged(
                    true,
                    SupportedPlatform.INSTAGRAM,
                    state.contentKey,
                    state.kind
                )
                // Promotion may synchronously show an intervention and pause this session.
                viewTracker.setPaused(goalPresenter.isShowing)
            }
        }
        if (BuildConfig.DEBUG && File(cacheDir, "instagram-diagnostics.enabled").exists()) {
            val signature = "${state.reason}:${state.contentKey}:${state.paused}:${state.excluded}:${state.kind}"
            if (signature != instagramTrackingDiagnosticSignature) {
                instagramTrackingDiagnosticSignature = signature
                Log.d("InstagramTrackingDiagnostic", "reason=${state.reason} kind=${state.kind} " +
                        "paused=${state.paused} excluded=${state.excluded} key=${state.contentKey?.take(12)}")
            }
        }
    }

    private fun detectTrackingScreen(manager: AppManagerInterface) = if (manager is YoutubeManager) {
        manager.detectShortformScreen(currentSnapshot,
            currentIsShortform || currentContentKey != null || currentScreenState == ShortformScreenState.OVERLAY)
    } else manager.detectShortformScreen(currentSnapshot)

    private fun updateShortformTracking(manager: AppManagerInterface) {
        if (manager is InstagramManager) {
            updateInstagramTracking()
            return
        }
        if (manager is KakaotalkManager) {
            kakaotalkPipeline.observe(currentSnapshot)
            return
        }

        val detection = detectTrackingScreen(manager)
        val previousScreenState = currentScreenState
        currentScreenState = detection.state
        currentIsShortform = detection.isConfirmedShortform
        if (manager.platform == SupportedPlatform.YOUTUBE && currentScreenState != ShortformScreenState.OVERLAY) {
            youtubeIdentityReady = if (youtubeLiveSession.key != null) youtubeLiveSession.confirmed
            else manager.getVideoIdentity(currentSnapshot, monotonicClock.nowMillis())?.quality == IdentityQuality.HIGH
            recognitionHealth.observe(detection.state, youtubeIdentityReady, monotonicClock.nowMillis())
            logRecognitionHealthIfDue()
        }
        if (!currentIsShortform) {
            if (manager.platform == SupportedPlatform.YOUTUBE &&
                (currentScreenState == ShortformScreenState.CANDIDATE ||
                        entryEvidenceGrace.shouldRetain(detection.state, monotonicClock.nowMillis()))
            ) {
                viewTracker.setPaused(true)
                retryYoutubeContentIfDue()
                return
            }
            stopShortformTracking()
            return
        }
        if (manager.platform == SupportedPlatform.YOUTUBE) {
            entryEvidenceGrace.shouldRetain(detection.state, monotonicClock.nowMillis())
        }

        if (currentScreenState == ShortformScreenState.OVERLAY) {
            if (previousScreenState != ShortformScreenState.OVERLAY) {
                screenGeneration++
                contentKindJob?.cancel()
                contentOcrJob?.cancel()
                contentKindConfirmation.reset()
                // Keep cached text and the active TemporalOcrCollector's buffer for this video.
                if (BuildConfig.DEBUG) Log.d("YoutubeOverlayDiagnostic",
                    "event=PAUSED evidence=${detection.evidence} session=${viewTracker.activeViewSessionId()} " +
                            "key=${currentContentKey?.take(12)} cachedOcrChars=${latestOcrText.length} " +
                            "analysisPaused=true viewingTimeContinues=${viewTracker.canContinueDuringInteraction()}")
            }
            viewTracker.onInteractionOverlay()
            return
        }
        if (previousScreenState == ShortformScreenState.OVERLAY && BuildConfig.DEBUG) {
            Log.d("YoutubeOverlayDiagnostic", "event=RETURNED session=${viewTracker.activeViewSessionId()} " +
                    "key=${currentContentKey?.take(12)} cachedOcrChars=${latestOcrText.length}")
        }
        if (manager.platform == SupportedPlatform.YOUTUBE &&
            com.example.dopaminecut2.logic.shortform.YoutubeAdEvidenceDetector.detect(currentSnapshot).explicitAd
        ) {
            if (currentContentKind != ShortformContentKind.AD) {
                if (viewTracker.isProvisional()) {
                    viewTracker.activeViewSessionId()?.let {
                        viewTracker.resolveProvisional(it, ShortformContentKind.AD, currentContentKey.orEmpty())
                    }
                }
                stopShortformTracking()
                currentContentKind = ShortformContentKind.AD
                if (BuildConfig.DEBUG) Log.d("YoutubeClassification",
                    "platform=YOUTUBE event=UI_OVERRIDE model=NOT_RUN resolved=AD confirmed=AD reason=EXPLICIT_AD_LABEL")
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
            viewTracker.setPaused(!youtubeLiveSession.confirmed)
            if (youtubeLiveSession.confirmed) {
                viewTracker.onScreenChanged(
                    true, SupportedPlatform.YOUTUBE,
                    youtubeLiveSession.key, youtubeLiveSession.kind
                )
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
            viewTracker.setPaused(!viewTracker.isProvisional() || currentContentKey?.startsWith("youtube_probe_") != true)
            retryYoutubeContentIfDue()
            return
        }
        // A fresh UI snapshot alone must not resume a session awaiting CV recovery.
        viewTracker.setPaused(manager.platform == SupportedPlatform.YOUTUBE &&
                currentContentKind == ShortformContentKind.UNKNOWN && !viewTracker.isProvisional())
        val identity = identityResolver.observe(candidate)
        val contentKey = identity?.contentKey ?: run {
            viewTracker.onScreenChanged(false, null, null, ShortformContentKind.UNKNOWN)
            return
        }
        if (manager.platform == SupportedPlatform.YOUTUBE && contentKey != currentContentKey &&
            BuildConfig.DEBUG && File(cacheDir, "identity-diagnostics.enabled").exists()
        ) {
            Log.d("YoutubeIdentityDiagnostic", "creator=${identity.creator}; title=${identity.title}; key=$contentKey")
        }

        applyContentTracking(manager, contentKey)
    }

    private fun applyContentTracking(manager: AppManagerInterface, contentKey: String) {
        val context = ContentTrackingContext(contentKey, currentContentKey, currentContentKind,
            contentKindJob?.isActive == true)
        when (val command = manager.contentTrackingCommand(currentSnapshot, context)) {
            is ContentTrackingCommand.BeginClassification ->
                beginPlatformContent(manager.platform, contentKey, command.provisional)
            ContentTrackingCommand.RetryClassification ->
                contentClassifiers.getValue(manager.platform).invoke(contentKey)
            is ContentTrackingCommand.Track -> {
                if (command.cancelInference) {
                    contentKindJob?.cancel()
                    contentKindJob = null
                }
                currentContentKey = contentKey
                currentContentKind = command.kind
                viewTracker.onScreenChanged(true, manager.platform, contentKey, command.kind)
                viewTracker.setPaused(false)
            }
            ContentTrackingCommand.Wait -> Unit
        }
    }

    private fun applyKakaotalkState(state: KakaotalkRecognitionState) {
        if (currentAppManager?.platform != SupportedPlatform.KAKAOTALK) return
        val change = KakaotalkSessionBinder.change(currentContentKey, currentScreenState, state)
        if (change.newFrame) {
            screenGeneration++
            contentOcrJob?.cancel()
        }
        // 댓글·공유창 진입·복귀에서는 같은 영상의 누적 OCR을 보존하고, 다른 영상·이탈에서만 버린다.
        if (change.newVideo) clearLatestOcr()
        currentContentKey = state.contentKey
        currentContentKind = state.kind
        currentScreenState = state.screen
        currentIsShortform = KakaotalkSessionBinder.isShortform(state)
        KakaotalkSessionBinder.bind(viewTracker, state, change.newVideo) { goalPresenter.isShowing }
        if (BuildConfig.DEBUG) {
            val signature = "${state.reason}:${state.contentKey}:${state.generation}:${state.attempts}:${state.kind}:${state.paused}:${state.excluded}"
            if (signature != kakaotalkDiagnosticSignature) {
                kakaotalkDiagnosticSignature = signature
                Log.d("KakaoTrackingDiagnostic", "reason=${state.reason} kind=${state.kind} " +
                        "paused=${state.paused} excluded=${state.excluded} generation=${state.generation} " +
                        "attempt=${state.attempts} failures=${state.executionFailures} " +
                        "unconfirmed=${state.unconfirmedResults} key=${state.contentKey?.take(12)}")
            }
        }
    }

    /** Capture and native inference stay in the service; job ownership belongs to the platform pipeline. */
    private suspend fun captureKakaotalkPrediction(token: KakaotalkFrameToken): com.example.dopaminecut2.logic.shortform.KakaotalkContentPrediction? {
        val model = kakaotalkContentClassifier ?: return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        if (!awaitForegroundSnapshot()) return null
        if (!kakaotalkPipeline.accepts(token)) return null
        val captureEpoch = snapshots.cached()?.epoch ?: return null
        if (BuildConfig.DEBUG) Log.d("KakaoTrackingDiagnostic", "CAPTURE_START key=${token.contentKey.take(12)}")
        val capturedAt = monotonicClock.nowMillis()
        val bitmap = withTimeoutOrNull(KAKAOTALK_CAPTURE_TIMEOUT_MS) { takeScreenshotBitmap() } ?: return null
        if (BuildConfig.DEBUG) Log.d("KakaoTrackingDiagnostic", "CAPTURE_RETURNED key=${token.contentKey.take(12)}")
        return try {
            if (!kakaotalkPipeline.accepts(token)) return null
            if (BuildConfig.DEBUG) Log.d("KakaoTrackingDiagnostic", "INFERENCE_START key=${token.contentKey.take(12)}")
            val started = monotonicClock.nowMillis()
            val prediction = withTimeoutOrNull(KAKAOTALK_INFERENCE_TIMEOUT_MS) {
                model.classify(bitmap).getOrThrow()
            } ?: return null
            if (!awaitForegroundSnapshot(notBeforeMs = capturedAt)) return null
            if (snapshots.cached()?.epoch != captureEpoch) return null
            if (!kakaotalkPipeline.accepts(token)) return null
            if (BuildConfig.DEBUG) Log.d(TAG, "KakaoTalk 콘텐츠 판정: uiClass=${prediction.uiClass}, " +
                    "verdict=${prediction.contentKind} (conf=${"%.3f".format(prediction.confidence)}, " +
                    "latencyMs=${monotonicClock.nowMillis() - started}, " +
                    "attempt=${kakaotalkPipeline.state.attempts}, key=${token.contentKey.take(12)})")
            prediction
        } finally { bitmap.recycle() }
    }

    private fun beginPlatformContent(platform: SupportedPlatform, contentKey: String, provisional: Boolean) {
        Log.d(TAG, "${platform.storageKey} content transition: ${currentContentKey?.take(10)} -> ${contentKey.take(10)}")
        // Finish the old session before accepting the new identity or waiting for inference.
        viewTracker.onScreenChanged(false, null, null, ShortformContentKind.UNKNOWN)
        screenGeneration++
        contentKindJob?.cancel()
        contentOcrJob?.cancel()
        currentContentKey = contentKey
        currentContentKind = ShortformContentKind.UNKNOWN
        contentKindConfirmation.reset()
        lastContentClassificationElapsedMs = -CONTENT_RECHECK_INTERVAL_MS
        clearLatestOcr()
        if (provisional) viewTracker.beginProvisional(platform, contentKey)
        contentClassifiers.getValue(platform).invoke(contentKey)
    }

    /** System capture stays here; the pipeline owns inference jobs and result validity. */
    private suspend fun captureInstagramPrediction(token: InstagramFrameToken): com.example.dopaminecut2.logic.shortform.InstagramContentPrediction? {
        val model = instagramContentClassifier ?: return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        if (!awaitForegroundSnapshot()) return null
        if (!instagramPipeline.accepts(token)) return null
        val captureEpoch = snapshots.cached()?.epoch ?: return null
        val capturedAt = monotonicClock.nowMillis()
        val bitmap = withTimeoutOrNull(INSTAGRAM_CAPTURE_TIMEOUT_MS) { takeScreenshotBitmap() } ?: return null
        return try {
            if (!instagramPipeline.accepts(token)) return null
            val started = monotonicClock.nowMillis()
            val prediction = withTimeoutOrNull(INSTAGRAM_INFERENCE_TIMEOUT_MS) {
                model.classify(bitmap).getOrThrow()
            } ?: return null
            if (!awaitForegroundSnapshot(notBeforeMs = capturedAt)) return null
            if (snapshots.cached()?.epoch != captureEpoch) return null
            if (!instagramPipeline.accepts(token)) return null
            if (BuildConfig.DEBUG) Log.d(
                TAG,
                "Instagram 콘텐츠 판정: uiClass=${prediction.uiClass}, " +
                        "verdict=${prediction.contentKind} (conf=${"%.3f".format(prediction.confidence)}, " +
                        "latencyMs=${monotonicClock.nowMillis() - started}, key=${token.contentKey.take(12)})"
            )
            prediction
        } finally { bitmap.recycle() }
    }

    private fun beginYoutubeContent(contentKey: String) {
        beginPlatformContent(SupportedPlatform.YOUTUBE, contentKey, provisional = true)
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
            if (!awaitForegroundSnapshot()) return@launch
            if (!isCurrentYoutubeFrame(generation, contentKey)) return@launch
            val capturedSessionId = viewTracker.activeViewSessionId()
            val capturedAt = monotonicClock.nowMillis()
            val bitmap = takeScreenshotBitmap()
            val inferenceStarted = monotonicClock.nowMillis()
            val prediction = try {
                bitmap?.let { model.classify(it)
                    .onFailure { error -> Log.w(TAG, "YouTube CV 추론 실패", error) }
                    .getOrNull() }
            } finally {
                bitmap?.recycle()
            }
            if (!awaitForegroundSnapshot(notBeforeMs = capturedAt)) return@launch
            if (!isCurrentYoutubeFrame(generation, contentKey) ||
                viewTracker.activeViewSessionId() != capturedSessionId) {
                if (BuildConfig.DEBUG) Log.d("YoutubeClassification",
                    com.example.dopaminecut2.logic.shortform.YoutubeClassificationDiagnostic.format(
                        prediction, null, null, "STALE_FRAME_DISCARDED", monotonicClock.nowMillis() - inferenceStarted))
                return@launch
            }
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
            if (BuildConfig.DEBUG) Log.d("YoutubeClassification",
                com.example.dopaminecut2.logic.shortform.YoutubeClassificationDiagnostic.format(
                    prediction, decision.kind, confirmedKind, decision.reason,
                    monotonicClock.nowMillis() - inferenceStarted))
            if (layoutValidationPending && confirmedKind == ShortformContentKind.UNKNOWN) {
                // 재확인 첫 프레임 때문에 기존 세션을 종료하면 같은 영상이 재집계된다.
                viewTracker.setPaused(true)
                return@launch
            }
            if (confirmedKind == ShortformContentKind.UNKNOWN && viewTracker.isProvisional()) {
                // Waiting for CV is not an overlay: continue provisional time, never confirmed totals.
                return@launch
            }
            if (confirmedKind == ShortformContentKind.UNKNOWN) {
                // Keep the last confirmed session, including its already-reported count.
                // UNKNOWN only suspends measurement; UI identity changes still begin a new session.
                currentContentKind = ShortformContentKind.UNKNOWN
                if (youtubeLiveSession.key != null) {
                    youtubeLiveSession.pause()
                    youtubeIdentityReady = false
                }
                viewTracker.setPaused(true)
                if (BuildConfig.DEBUG) Log.d("YoutubeClassification",
                    "platform=YOUTUBE event=PAUSED reason=CV_REVALIDATION session=$capturedSessionId")
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
                    if (BuildConfig.DEBUG) Log.d("YoutubeClassification",
                        "platform=YOUTUBE event=NOT_APPLIED reason=IDENTITY_NOT_READY " +
                                "identityReady=$youtubeIdentityReady hasKey=${contentKey != null} confirmed=$confirmedKind")
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
            if (BuildConfig.DEBUG) Log.d("YoutubeClassification",
                "platform=YOUTUBE event=APPLIED kind=$currentContentKind session=$capturedSessionId")
        }
    }

    private fun isCurrentYoutubeFrame(generation: Long, key: String?): Boolean =
        serviceActive && foregroundSnapshotReady && snapshots.cached() != null && generation == screenGeneration && currentContentKey == key &&
                currentAppManager?.platform == SupportedPlatform.YOUTUBE &&
                com.example.dopaminecut2.logic.shortform.RecognitionSafety.canProbe(currentScreenState) &&
                sessionManager.active()?.userId == dependencies.authRepository.currentUserId()

    private fun refreshYoutubeContentOcrIfDue() {
        if (!contentAnalysisEnabled || !measurementEnabled) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (currentAppManager?.platform != SupportedPlatform.YOUTUBE || !currentIsShortform) return
        if (currentScreenState == ShortformScreenState.OVERLAY) return
        if (currentContentKind != ShortformContentKind.NORMAL || !youtubeIdentityReady) return
        if (viewTracker.isProvisional()) return // Denied/unapproved video is not OCR input.
        // Once confirmed viewing starts its collector owns sampling; do not run duplicate OCR jobs.
        if (normalSessions[viewTracker.activeViewSessionId()]?.classificationStarted == true) return
        if (layoutValidationPending) return
        if (contentKindJob?.isActive == true) return
        if (contentOcrJob?.isActive == true) return
        val now = monotonicClock.nowMillis()
        if (now - lastContentOcrElapsedMs < CONTENT_OCR_INTERVAL_MS) return
        lastContentOcrElapsedMs = now
        val generation = screenGeneration
        val capturedKey = currentContentKey
        val capturedSessionId = viewTracker.activeViewSessionId()

        contentOcrJob = serviceScope.launch {
            if (!awaitForegroundSnapshot() || !isCurrentYoutubeFrame(generation, capturedKey)) return@launch
            val captureSnapshot = currentSnapshot
            val capturedAt = monotonicClock.nowMillis()
            val bitmap = takeScreenshotBitmap() ?: return@launch
            val captureLayout = contentRegionResolver.layoutKey(captureSnapshot)
            try {
                val frame = ocrProcessor.recognizeFrame(InputImage.fromBitmap(bitmap, 0))
                    .onFailure { error -> Log.w(TAG, "YouTube 분류용 OCR 실패", error) }
                    .getOrNull() ?: return@launch
                if (!awaitForegroundSnapshot(notBeforeMs = capturedAt)) return@launch
                if (!isCurrentYoutubeFrame(generation, capturedKey) || !youtubeIdentityReady ||
                    viewTracker.activeViewSessionId() != capturedSessionId ||
                    currentScreenState != ShortformScreenState.INSIDE || currentContentKind != ShortformContentKind.NORMAL) return@launch
                // OCR은 분류용 텍스트만 갱신한다. 식별자 생성·영상 전환에는 절대 사용하지 않는다.
                latestOcrContentKey = capturedKey
                latestOcrSessionId = capturedSessionId
                if (captureLayout != contentRegionResolver.layoutKey(currentSnapshot)) return@launch
                latestOcrText = ocrTextSanitizer.sanitizeYoutube(frame, captureSnapshot)
                latestOcrElapsedMs = monotonicClock.nowMillis()
            } finally {
                bitmap.recycle()
            }
        }
    }

    private fun stopShortformTracking() {
        if (::instagramPipeline.isInitialized) instagramPipeline.stop()
        else (appManagers[SupportedPlatform.INSTAGRAM.packageName] as? InstagramManager)?.resetTracking()
        if (::kakaotalkPipeline.isInitialized) kakaotalkPipeline.stop()
        else (appManagers[SupportedPlatform.KAKAOTALK.packageName] as? KakaotalkManager)?.resetTracking()

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
        latestOcrSessionId = null
        latestOcrText = ""
        latestOcrElapsedMs = 0L
    }

    private suspend fun refreshSessionIfNeeded(force: Boolean = false) = sessionTransitionMutex.withLock {
        val transition = sessionManager.pendingTransition(force) ?: return@withLock
        transition.previous?.let { previous ->
            if (qualitySeconds > 0) enqueueUsageWrite(UsageWriteCommand.Heartbeat(previous.userId, previous.date, qualitySeconds,
                java.time.LocalTime.now().hour < 21, dependencies.wallClock.now().time))
        }
        qualitySeconds = 0L; qualityElapsed = 0L
        qualityRemainderMs = 0L
        continuousWatchSec = 0L; continuousLastElapsed = 0L; continuousNoticeSent = false
        sessionReady = false
        if (::snapshots.isInitialized) snapshots.invalidate()
        foregroundSnapshotReady = false

        // 이전 세션을 활성 상태로 둔 채 마지막 사용량과 숏폼을 정산한다.
        accountAndPersistCurrentRunTime()
        if (::viewTracker.isInitialized) stopShortformTracking()
        if (::viewTracker.isInitialized && (transition.previous?.userId != transition.current?.userId ||
                    transition.previous?.date != transition.current?.date)) viewTracker.clearRecentCountHistory()
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

    private fun onViewQualified(delta: ShortformCountDelta) {
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
        enqueueUsageWrite(UsageWriteCommand.Pending(record))
    }

    private fun onProvisionalSettled(sessionId: String) {
        val userId = sessionManager.active()?.userId ?: return
        enqueueUsageWrite(UsageWriteCommand.SettlePending(userId, sessionId))
    }

    private fun onTrackedSessionCheckpoint(checkpoint: ShortformSessionCheckpoint) {
        if (BuildConfig.DEBUG && checkpoint.platform == SupportedPlatform.INSTAGRAM &&
            File(cacheDir, "instagram-diagnostics.enabled").exists()) {
            Log.d("InstagramTrackingDiagnostic", "CHECKPOINT session=${checkpoint.viewSessionId} " +
                    "durationSec=${checkpoint.durationSec} count=${checkpoint.count}")
        }
        if (BuildConfig.DEBUG && checkpoint.platform == SupportedPlatform.KAKAOTALK &&
            File(cacheDir, "${kakaotalkUiDiagnostic.flagPrefix}.enabled").exists()) {
            Log.d("KakaoTrackingDiagnostic", "CHECKPOINT session=${checkpoint.viewSessionId} " +
                    "kind=${checkpoint.contentKind} key=${checkpoint.contentKey.take(12)} " +
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
        val command = UsageWriteCommand.Checkpoint(session.userId, session.date, checkpoint.viewSessionId,
            checkpoint.platform, checkpoint.durationSec, checkpoint.count, occurredAtEpochMs, newTime,
            java.time.Instant.ofEpochMilli(occurredAtEpochMs).atZone(java.time.ZoneId.systemDefault()).hour < 21,
            checkpoint.contentKind)
        enqueueUsageWrite(command, owner?.recorded?.takeIf { previous == null })
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
    ): Boolean = serviceActive && foregroundSnapshotReady && snapshots.cached() != null && !owner.completed && !owner.resolved &&
            dependencies.authRepository.currentUserId() == owner.userId &&
            screenGeneration == generation && currentContentKey == delta.contentKey &&
            currentAppManager?.platform == delta.platform &&
            currentScreenState == ShortformScreenState.INSIDE &&
            viewTracker.activeViewSessionId() == delta.viewSessionId &&
            (delta.platform != SupportedPlatform.YOUTUBE ||
                    !layoutValidationPending && youtubeIdentityReady && currentContentKind == ShortformContentKind.NORMAL)

    private fun captureAndQueueClassification(delta: ShortformCountDelta, owner: NormalSessionOwner) {
        if (!contentAnalysisEnabled) {
            resolveCategory(owner, ClassifiedContent.unresolved(owner.viewSessionId, "ANALYSIS_DISABLED"))
            return
        }
        classificationScope.launch {
            try {
                if (owner.recorded.await().isFailure) {
                    resolveCategory(owner, ClassifiedContent.unresolved(delta.viewSessionId, "LOCAL_STORAGE_ERROR"))
                    return@launch
                }
                var firstSample = true
                val collector = com.example.dopaminecut2.logic.ai.TemporalOcrCollector(monotonicClock::nowMillis)
                val text = collector.collect(
                    isCurrent = {
                        serviceActive && contentAnalysisEnabled && measurementEnabled &&
                                !owner.completed && !owner.resolved &&
                                dependencies.authRepository.currentUserId() == owner.userId &&
                                currentAppManager?.platform == delta.platform &&
                                viewTracker.activeViewSessionId() == delta.viewSessionId
                    },
                    canCapture = { isCurrentClassificationFrame(delta, owner, screenGeneration) },
                    onPauseChanged = { paused, chars ->
                        if (BuildConfig.DEBUG) Log.d("OcrCollection",
                            "event=${if (paused) "PAUSED" else "RESUMED"} session=${delta.viewSessionId} bufferChars=$chars")
                    },
                    capture = {
                        val allowCached = firstSample
                        firstSample = false
                        captureClassificationText(delta, owner, screenGeneration, allowCached).also { sample ->
                            if (BuildConfig.DEBUG) Log.d("OcrCollection",
                                "event=SAMPLE platform=${delta.platform} session=${delta.viewSessionId} chars=${sample?.length ?: 0}")
                        }
                    }
                )
                if (owner.resolved || dependencies.authRepository.currentUserId() != owner.userId) return@launch
                // Session completion is not an error: send its already validated samples, never the next frame.
                if (text.isBlank()) {
                    resolveCategory(owner, ClassifiedContent.unresolved(delta.viewSessionId, "INSUFFICIENT_TEXT"))
                    return@launch
                }
                if (BuildConfig.DEBUG) Log.d("OcrCollection",
                    "event=FINALIZED platform=${delta.platform} session=${delta.viewSessionId} chars=${text.length} completed=${owner.completed}")
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

    private suspend fun captureClassificationText(
        delta: ShortformCountDelta,
        owner: NormalSessionOwner,
        generation: Long,
        allowCached: Boolean
    ): String? {
        if (!awaitForegroundSnapshot()) return null
        if (!isCurrentClassificationFrame(delta, owner, generation)) return null
        if (allowCached && delta.platform == SupportedPlatform.YOUTUBE &&
            latestOcrSessionId == delta.viewSessionId && latestOcrContentKey == delta.contentKey &&
            monotonicClock.nowMillis() - latestOcrElapsedMs <= OCR_CACHE_MAX_AGE_MS &&
            latestOcrText.isNotBlank()) return latestOcrText
        val fallback = if (delta.platform == SupportedPlatform.YOUTUBE) ""
        else ocrTextSanitizer.sanitizeFallback(currentSnapshot.texts)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return fallback
        val snapshot = currentSnapshot
        val layout = contentRegionResolver.layoutKey(snapshot)
        val capturedAt = monotonicClock.nowMillis()
        val bitmap = takeScreenshotBitmap() ?: return null
        try {
            if (!isCurrentClassificationFrame(delta, owner, generation)) return null
            val frame = ocrProcessor.recognizeFrame(InputImage.fromBitmap(bitmap, 0)).getOrNull()
            if (!awaitForegroundSnapshot(notBeforeMs = capturedAt)) return null
            if (!isCurrentClassificationFrame(delta, owner, generation) ||
                delta.platform == SupportedPlatform.YOUTUBE &&
                layout != contentRegionResolver.layoutKey(currentSnapshot)) return null
            return frame?.let {
                if (delta.platform == SupportedPlatform.YOUTUBE) ocrTextSanitizer.sanitizeYoutube(it, snapshot)
                else ocrTextSanitizer.sanitize(it)
            }?.takeIf(String::isNotBlank) ?: fallback
        } finally {
            bitmap.recycle()
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
        if (BuildConfig.DEBUG) Log.d("CategoryClassificationDiagnostic",
            "event=RESOLVE session=${result.viewSessionId} category=${result.category} " +
                    "status=${result.status} source=${result.source} reason=${result.reason} " +
                    "alreadyResolved=${owner.resolved}")
        if (owner.resolved) return
        owner.resolved = true
        enqueueUsageWrite(UsageWriteCommand.Category(owner.userId, owner.date, owner.viewSessionId,
            UsageClassification(category = result.category, status = result.status.name, source = result.source.name,
                reason = result.reason, modelVersion = result.modelVersion, taxonomyVersion = result.taxonomyVersion,
                confidence = result.confidence,
                deductedScore = DopamineScorePolicy.deductedScore(result.category, owner.restrictions))))
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

    private fun enqueueUsageWrite(command: UsageWriteCommand,
                                  receipt: CompletableDeferred<Result<Unit>>? = null) {
        usageWrites.submit(command, receipt ?: CompletableDeferred())
    }

    private suspend fun drainUsageWrites() {
        usageWrites.drain().getOrThrow()
    }

    @SuppressLint("NewApi")
    private suspend fun takeScreenshotBitmap(): Bitmap? {
        if (!awaitForegroundSnapshot()) return null
        val manager = currentAppManager ?: return null
        if (!serviceActive || !com.example.dopaminecut2.logic.shortform.RecognitionSafety.canProbe(currentScreenState)) return null
        if (!com.example.dopaminecut2.logic.shortform.RecognitionSafety.canProbe(manager.detectShortformScreen(currentSnapshot).state)) {
            return null
        }
        val interval = if (manager.platform == SupportedPlatform.YOUTUBE || manager.platform == SupportedPlatform.KAKAOTALK)
            ContentSamplingPolicy.INTERVAL_MS else SCREENSHOT_MIN_INTERVAL_MS
        // A busy/not-yet-due capture is skipped, never queued behind another video's work.
        val lease = frameCaptureGate.acquire(monotonicClock.nowMillis(), interval) ?: return null
        fun trace(stage: String) {
            if (BuildConfig.DEBUG && manager.platform == SupportedPlatform.KAKAOTALK) {
                Log.d("KakaoTrackingDiagnostic", "capture=$stage lease=$lease")
                recognitionResumeTrace?.event(stage, lease)
            }
        }
        try {
            trace("REQUESTED")
            val captured = suspendCancellableCoroutine<Bitmap?> { continuation ->
                continuation.invokeOnCancellation {
                    // Release admission immediately; finally may be waiting to resume on Main.
                    frameCaptureGate.release(lease)
                    trace("CANCELLED")
                }
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    screenshotExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshot: ScreenshotResult) {
                            trace("CALLBACK_SUCCESS")
                            val hardwareBuffer = screenshot.hardwareBuffer
                            if (!continuation.isActive) {
                                hardwareBuffer.close()
                                trace("STALE_CALLBACK_DISCARDED")
                                return
                            }
                            try {
                                val bitmap = try {
                                    Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshot.colorSpace)
                                        ?.copy(Bitmap.Config.ARGB_8888, false)
                                } finally { hardwareBuffer.close() }
                                trace("BITMAP_READY")
                                if (manager.platform == SupportedPlatform.KAKAOTALK)
                                    recognitionResumeTrace?.beforeResume(lease)
                                continuation.resume(bitmap, onCancellation = { _, value, _ -> value?.recycle() })
                                trace("RESUME_CALLED")
                            } catch (error: Exception) {
                                if (continuation.isActive) continuation.resumeWithException(error)
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            trace("CALLBACK_FAILURE_$errorCode")
                            Log.w(TAG, "화면 캡처 실패: $errorCode")
                            if (continuation.isActive) continuation.resume(null)
                        }
                    }
                )
            }
            trace("CONTINUATION_RESUMED")
            return captured
        } finally {
            frameCaptureGate.release(lease)
            trace("RELEASED")
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
        enqueueUsageWrite(UsageWriteCommand.AppTime(session.userId, session.date, manager.platform, durationSec))
    }

    private fun enforceLimits() {
        if (measurementEnabled && foregroundSnapshotReady && snapshots.cached() != null) enforceManagedGoals()
    }

    /** True means admission must remain on hold, including failed/not-ready presentation. */
    private fun enforceManagedGoals(countAdmission: Boolean = false): Boolean {
        val session = sessionManager.active() ?: return countAdmission
        val manager = currentAppManager ?: return countAdmission
        if (!sessionReady || !managedSetupReady || !managedUsageReady || !managedGoalsLoaded || managedEnforcementBusy || goalPresenter.isShowing ||
            monotonicClock.nowMillis() - lastManagedDismissElapsedMs < 2_000L ||
            monotonicClock.nowMillis() - lastManagedFailureElapsedMs < 30_000L ||
            dependencies.authRepository.currentUserId() != session.userId) return countAdmission
        val activeAppSec = if (currentAppStartElapsedMs > 0) (monotonicClock.nowMillis() - currentAppStartElapsedMs) / 1000 else 0
        val active = viewTracker.activeProgress()
        val effective = com.example.dopaminecut2.logic.ManagedGoalProgress.effectiveUsage(
            managedUsage, manager.platform, activeAppSec, active, active?.let { managedCheckpoints[it.viewSessionId] }
        )
        val interactionViewing = isYoutubeInteractionViewing()
        val effectiveKind = if (interactionViewing) active?.contentKind else currentContentKind
        val eligible = interactionViewing || (currentScreenState == ShortformScreenState.INSIDE &&
                currentContentKind in listOf(ShortformContentKind.NORMAL, ShortformContentKind.LIVE, ShortformContentKind.PHOTO_POST) &&
                (manager.platform != SupportedPlatform.YOUTUBE || youtubeIdentityReady))
        val requests = com.example.dopaminecut2.logic.ManagedGoalPolicy.evaluate(managedGoals, effective, manager.platform,
            eligible, effectiveKind == ShortformContentKind.NORMAL, countAdmission)
        if (countAdmission && (!eligible || currentContentKind != ShortformContentKind.NORMAL)) return true
        var admissionBlocked = false
        managedEnforcementBusy = true
        try {
            for (request in requests) {
                val goal = request.goal
                val action = com.example.dopaminecut2.logic.GoalInterventionExecutionPolicy.action(
                    request, goalNotificationsEnabled, request.key in ledger.notified,
                    snoozeElapsedUntil[request.key] ?: 0L, monotonicClock.nowMillis()
                )
                if (action == com.example.dopaminecut2.logic.GoalInterventionAction.NONE) continue
                if (BuildConfig.DEBUG) Log.d("GoalIntervention",
                    "platform=${manager.platform} metric=${goal.metric} mode=${goal.intervention} action=$action " +
                            "screen=$currentScreenState content=$currentContentKind eligible=$eligible")
                if (action == com.example.dopaminecut2.logic.GoalInterventionAction.NOTIFY) {
                    if (goalPresenter.notify(request)) {
                        ledger = ledger.copy(notified = (ledger.notified + request.key).toList().takeLast(100).toSet())
                        persistLedger()
                    }
                } else {
                    admissionBlocked = true
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
        return admissionBlocked
    }

    private fun persistLedger() {
        val session = sessionManager.active() ?: return
        val captured = ledger
        enqueueUsageWrite(UsageWriteCommand.Ledger(session.userId, session.date, captured))
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

    private fun recordQualityHeartbeat() {
        val now = monotonicClock.nowMillis()
        val delta = if (qualityElapsed > 0) now - qualityElapsed else 0L
        qualityElapsed = now
        val session = sessionManager.active() ?: return
        if (measurementEnabled && delta in 1..5_000 && powerManager.isInteractive) {
            qualityRemainderMs += delta
            qualitySeconds += qualityRemainderMs / 1000
            qualityRemainderMs %= 1000
        }
        if (measurementEnabled && now - qualityLastWrite >= 30_000) {
            enqueueUsageWrite(UsageWriteCommand.Heartbeat(session.userId, session.date, qualitySeconds,
                java.time.LocalTime.now().hour < 21, dependencies.wallClock.now().time))
            qualitySeconds = 0; qualityLastWrite = now
            if (BuildConfig.DEBUG) Log.d("UsageWriteHealth", usageWrites.health().toString())
            if (goalPlanRefreshJob?.isActive != true) goalPlanRefreshJob = serviceScope.launch {
                taskRunner.run("목표 비교 기준 확인") {
                    val plan = dependencies.habitStore.plans(session.userId)[com.example.dopaminecut2.domain.GoalMetric.DAILY_TIME]
                    if (sessionManager.active() == session && dependencies.authRepository.currentUserId() == session.userId) {
                        continuousBreakMinutes = plan?.takeIf { p -> managedGoals.any {
                            it.metric == p.metric && it.revision == p.revision } }?.continuousBreakMinutes ?: 0
                    }
                }
            }
        }
    }

    private fun isYoutubeInteractionViewing(): Boolean = measurementEnabled && sessionReady && foregroundSnapshotReady &&
            !goalPresenter.isShowing && currentAppManager?.platform == SupportedPlatform.YOUTUBE &&
            currentScreenState == ShortformScreenState.OVERLAY && viewTracker.canContinueDuringInteraction()

    private fun enforceContinuousBreak() {
        val now = monotonicClock.nowMillis()
        val elapsedMs = if (continuousLastElapsed > 0) (now - continuousLastElapsed).coerceIn(0L, 2_000L) else 0
        continuousLastElapsed = now
        if (!measurementEnabled || currentScreenState == ShortformScreenState.OUTSIDE || currentAppManager == null) {
            continuousWatchSec = 0; continuousRemainderMs = 0; continuousNoticeSent = false; return
        }
        if (!isYoutubeInteractionViewing() && (currentScreenState != ShortformScreenState.INSIDE ||
                    currentContentKind in setOf(ShortformContentKind.UNKNOWN, ShortformContentKind.AD))) return
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
        if (retryInProgress) return
        val userId = sessionManager.active()?.userId ?: return
        val now = monotonicClock.nowMillis()
        // This is a local eligibility check; the durable coordinator gates network traffic to 24 hours.
        if (!force && now - lastRetryElapsedMs < RETRY_INTERVAL_MS) return
        lastRetryElapsedMs = now
        retryInProgress = true
        dependencies.applicationScope.launch {
            try {
                dependencies.usageSync.sync(userId)
            } finally {
                retryInProgress = false
                if (dependencies.authRepository.currentUserId() == userId)
                    dependencies.usageSyncSchedule.ensure(userId)
            }
        }
    }

    private fun reportFailure(operation: String, error: Throwable) = reportFailureOnMain(operation, error)

    private fun reportFailureOnMain(operation: String, error: Throwable) {
        Log.e(TAG, "$operation 실패 (${error.javaClass.simpleName})")
        if (serviceActive && failureNotices.allow(operation, android.os.SystemClock.elapsedRealtime()))
            showToastOnMain("$operation 처리에 문제가 있어요. 다시 시도해 주세요.")
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
        if (::usageWrites.isInitialized) usageWrites.job.let { job ->
            dependencies.dataControls.finishingJobs.add(job)
            job.invokeOnCompletion { dependencies.dataControls.finishingJobs.remove(job) }
        }
        if (::dependencies.isInitialized) dependencies.dataControls.quiesceService = null
        dismissGoalOverlay()
        if (::goalPresenter.isInitialized) goalPresenter.clearNotifications()
        goalObservationJob?.cancel()
        serviceActive = false
        sessionReady = false
        foregroundSnapshotReady = false
        if (::snapshots.isInitialized) snapshots.close()
        accountAndPersistCurrentRunTime()
        if (::viewTracker.isInitialized) viewTracker.release()
        abandonClassifications("SERVICE_STOPPED")
        classificationScope.cancel()
        if (::usageWrites.isInitialized) usageWrites.close()
        if (::ocrProcessor.isInitialized) ocrProcessor.close()
        youtubeContentClassifier?.close()
        instagramContentClassifier?.close()
        kakaotalkContentClassifier?.close()
        onboardingObservationJob?.cancel()
        tickerJob?.cancel()
        serviceScope.cancel()
        snapshotDispatcher.close()
        recognitionResumeTrace?.close()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "AppBlockService"
        const val TICK_INTERVAL_MS = 1_000L
        const val SCREEN_REFRESH_INTERVAL_MS = 150L
        const val CONTENT_RECHECK_INTERVAL_MS = 1_000L
        const val CONTENT_SETTLE_MS = 150L
        const val INSTAGRAM_CAPTURE_TIMEOUT_MS = 2_000L
        const val INSTAGRAM_INFERENCE_TIMEOUT_MS = 2_000L
        const val KAKAOTALK_CAPTURE_TIMEOUT_MS = 2_000L
        const val KAKAOTALK_INFERENCE_TIMEOUT_MS = 2_000L
        const val CONTENT_OCR_INTERVAL_MS = ContentSamplingPolicy.INTERVAL_MS
        const val SCREENSHOT_MIN_INTERVAL_MS = 500L
        const val OCR_CACHE_MAX_AGE_MS = 3_000L
        const val RUN_TIME_FLUSH_MS = 10_000L
        const val RETRY_INTERVAL_MS = 60_000L // Local check only; cloud writes are gated to once per day.
        const val RETRY_BATCH_SIZE = 31
    }
}