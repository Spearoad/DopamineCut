package com.example.dopaminecut2.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// 파일 최상단에 DataStore 인스턴스 생성
private val Context.dataStore by preferencesDataStore(name = "dopamine_settings")

class DataStoreManager(private val context: Context) {

    // 저장할 Key 값 정의 (차단 카테고리)
    private val RESTRICTIONS_KEY = stringSetPreferencesKey("user_restrictions")

    /**
     * 로컬 기기에 차단 카테고리 목록을 저장 (백그라운드 서비스에서 즉각 참조하기 위함)
     */
    suspend fun saveRestrictionsLocally(restrictions: List<String>) {
        context.dataStore.edit { preferences ->
            // DataStore는 Set 형태를 지원하므로 List를 Set으로 변환하여 저장
            preferences[RESTRICTIONS_KEY] = restrictions.toSet()
        }
    }

    /**
     * 저장된 차단 카테고리 목록을 실시간으로 읽어오는 Flow
     */
    fun getRestrictionsFlow(): Flow<List<String>> {
        return context.dataStore.data.map { preferences ->
            // 저장된 값이 없으면 빈 리스트 반환
            preferences[RESTRICTIONS_KEY]?.toList() ?: emptyList()
        }
    }
}