package com.example.dopaminecut2.di

import android.content.Context
import com.example.dopaminecut2.auth.AuthRepository
import com.example.dopaminecut2.auth.FirebaseAuthRepository
import com.example.dopaminecut2.data.local.DataStoreManager
import com.example.dopaminecut2.data.local.OnboardingStore
import com.example.dopaminecut2.data.local.NotificationSettingsStore
import com.example.dopaminecut2.data.remote.FirebaseDataSource
import com.example.dopaminecut2.data.repository.UserRepository
import com.example.dopaminecut2.data.repository.UserRepositoryInterface
import com.example.dopaminecut2.logic.ai.BatchContentClassifier
import com.example.dopaminecut2.logic.ai.CallableBatchContentClassifier
import com.example.dopaminecut2.logic.ai.RetryingBatchContentClassifier
import com.example.dopaminecut2.statistics.UsageStatisticsManager
import com.example.dopaminecut2.time.AndroidMonotonicClock
import com.example.dopaminecut2.time.DateIdProvider
import com.example.dopaminecut2.time.LocalDateIdProvider
import com.example.dopaminecut2.time.MonotonicClock
import com.example.dopaminecut2.time.SystemWallClock
import com.example.dopaminecut2.time.WallClock
import com.example.dopaminecut2.ui.common.MeasurementPermissionChecker
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

interface AppDependencies {
    val usageSync: com.example.dopaminecut2.statistics.UsageSyncCoordinator
        get() = error("Usage sync is not configured")
    val usageSyncSchedule: com.example.dopaminecut2.statistics.UsageSyncSchedule
        get() = error("Usage sync schedule is not configured")
    val habitStore: com.example.dopaminecut2.data.local.HabitStore
    val habitInsights: com.example.dopaminecut2.statistics.HabitInsightsRepository
    val dataControls: com.example.dopaminecut2.data.repository.DataControls
    val pendingRecognitionStore: com.example.dopaminecut2.data.local.PendingRecognitionStore
    val interventionLedgerStore: com.example.dopaminecut2.data.local.InterventionLedgerStore
    val goalStore: com.example.dopaminecut2.data.local.GoalStore
    val authRepository: AuthRepository
    val userRepository: UserRepositoryInterface
    val usageStatisticsManager: UsageStatisticsManager
    val onboardingStore: OnboardingStore
    val notificationSettingsStore: NotificationSettingsStore
    val dateIdProvider: DateIdProvider
    val monotonicClock: MonotonicClock
    val wallClock: WallClock
    val applicationScope: CoroutineScope
    val measurementPermissionChecker: MeasurementPermissionChecker
    val batchContentClassifier: BatchContentClassifier
}

class AppContainer(context: Context) : AppDependencies {
    private val appContext = context.applicationContext
    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()
    private val localDataSource = DataStoreManager(appContext)
    private val remoteDataSource = FirebaseDataSource(firestore)
    private val functions = FirebaseFunctions.getInstance(FUNCTIONS_REGION)

    override val authRepository: AuthRepository = FirebaseAuthRepository(auth, firestore)
    override val onboardingStore: OnboardingStore = localDataSource
    override val pendingRecognitionStore: com.example.dopaminecut2.data.local.PendingRecognitionStore = localDataSource
    override val goalStore: com.example.dopaminecut2.data.local.GoalStore by lazy {
        com.example.dopaminecut2.data.repository.SyncedGoalStore(localDataSource,
            com.example.dopaminecut2.data.remote.FirestoreGoalSource(firestore), authRepository, applicationScope)
    }
    override val notificationSettingsStore: NotificationSettingsStore = localDataSource
    override val interventionLedgerStore: com.example.dopaminecut2.data.local.InterventionLedgerStore = localDataSource
    override val userRepository: UserRepositoryInterface =
        UserRepository(remoteDataSource, localDataSource)
    override val habitStore = com.example.dopaminecut2.data.local.HabitStore(appContext)
    override val habitInsights by lazy { com.example.dopaminecut2.statistics.HabitInsightsRepository(
        appContext, userRepository, localDataSource, habitStore) }
    override val dataControls by lazy { com.example.dopaminecut2.data.repository.DataControls(
        authRepository, firestore, localDataSource, habitStore, userRepository as UserRepository) }
    override val dateIdProvider: DateIdProvider = LocalDateIdProvider()
    override val monotonicClock: MonotonicClock = AndroidMonotonicClock
    override val wallClock: WallClock = SystemWallClock
    override val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override val measurementPermissionChecker = MeasurementPermissionChecker(appContext)
    override val batchContentClassifier: BatchContentClassifier = RetryingBatchContentClassifier(
        CallableBatchContentClassifier(functions, authRepository::currentUserId) { payload ->
            com.example.dopaminecut2.logic.ai.OcrRequestDiagnostic.record(appContext, payload)
        },
        currentUserId = authRepository::currentUserId
    )
    override val usageStatisticsManager = UsageStatisticsManager(
        remoteDataSource = remoteDataSource,
        snapshotStore = localDataSource,
        wallClock = wallClock
    )
    override val usageSyncSchedule by lazy { com.example.dopaminecut2.statistics.AndroidUsageSyncSchedule(appContext) }
    override val usageSync by lazy { com.example.dopaminecut2.statistics.UsageSyncCoordinator(
        usageSyncSchedule, authRepository::currentUserId,
        upload = { uid -> dataControls.withUsageSync(uid) { usageStatisticsManager.retryPending(uid) } },
        onUploaded = { (userRepository as UserRepository).clearStatisticsCache(it) }) }

    private companion object {
        const val FUNCTIONS_REGION = "asia-northeast3"
    }
}
