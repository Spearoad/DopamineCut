package com.example.dopaminecut2.data.repository

import com.example.dopaminecut2.auth.AuthRepository
import com.example.dopaminecut2.data.local.*
import com.google.firebase.firestore.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.first

/** Service and settings share this coordinator so deleted records cannot be re-uploaded by its queue. */
class DataControls(private val auth: AuthRepository, private val firestore: FirebaseFirestore,
    private val local: DataStoreManager, private val habits: HabitStore, private val repository: UserRepository) {
    var quiesceService: (suspend () -> Unit)? = null
    val finishingJobs = java.util.concurrent.CopyOnWriteArrayList<kotlinx.coroutines.Job>()
    private val mutex = Mutex()
    suspend fun <T> withUsageSync(uid: String, work: suspend () -> T): T = mutex.withLock {
        require(auth.currentUserId() == uid)
        work()
    }
    suspend fun delete(uid: String, date: String?): Result<Unit> = runCatching { kotlinx.coroutines.withTimeout(60_000) { mutex.withLock {
        require(auth.currentUserId() == uid)
        habits.setSettings(uid, habits.settings(uid).first().copy(measurement = false))
        withContext(Dispatchers.Main) { quiesceService?.invoke() }
        finishingJobs.toList().forEach { it.join() }
        val months = firestore.collection("users").document(uid).collection("months")
        if (date != null) {
            val ref = months.document(date.take(6))
            if (ref.get(Source.SERVER).await().exists()) ref.update(mapOf(
                "days.d${date.takeLast(2)}" to FieldValue.delete(), "write_day" to "d${date.takeLast(2)}",
                "updated_at" to FieldValue.serverTimestamp())).await()
        } else {
            // Bounded pages rather than loading or deleting an unbounded collection in one batch.
            while (true) {
                require(auth.currentUserId() == uid)
                val page = months.limit(100).get(Source.SERVER).await()
                if (page.isEmpty) break
                val batch = firestore.batch(); page.documents.forEach { batch.delete(it.reference) }; batch.commit().await()
            }
        }
        require(auth.currentUserId() == uid)
        local.deleteUsage(uid, date)
        habits.deleteEvidence(uid, date)
        repository.clearStatisticsCache(uid)
    } } }
}
