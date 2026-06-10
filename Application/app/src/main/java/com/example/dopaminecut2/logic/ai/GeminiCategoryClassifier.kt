package com.example.dopaminecut2.logic.ai

class GeminiCategoryClassifier {

    fun classify(text: String): String {
        val normalized = text.trim().lowercase()

        return when {
            containsAny(normalized, listOf("게임", "game", "플레이", "공략")) -> "GAME"
            containsAny(normalized, listOf("먹방", "음식", "food", "맛집", "요리")) -> "FOOD"
            containsAny(normalized, listOf("공부", "study", "강의", "학습", "수업")) -> "STUDY"
            containsAny(normalized, listOf("뉴스", "news", "속보", "시사")) -> "NEWS"
            else -> "UNKNOWN"
        }
    }

    private fun containsAny(text: String, keywords: List<String>): Boolean {
        return keywords.any { keyword -> text.contains(keyword) }
    }
}

