package com.example.dopaminecut2.data.remote

object FirestoreCollections {
    const val USERS = "users"
    const val MONTHS = "months"
}

object FirestoreFields {
    const val SCHEMA_VERSION = "schema_version"
    const val PROFILE = "profile"
    const val NICKNAME = "nickname"
    const val GOAL = "goal"
    const val APP_TIME_LIMIT_MIN = "app_time_limit_min"
    const val SHORTFORM_LIMIT_COUNT = "shortform_limit_count"
    const val RESTRICTED_CATEGORIES = "restricted_categories"
    const val CREATED_AT = "created_at"
    const val UPDATED_AT = "updated_at"
    const val DAYS = "days"

    const val APP_USAGE = "app_usage"
    const val APP_TIME_SEC = "app_time_sec"
    const val SHORTFORM_TIME_SEC = "shortform_time_sec"
    const val SHORTFORM_COUNT = "shortform_count"
    const val HOURLY_SHORTFORM_TIME_SEC = "hourly_shortform_time_sec"
    const val CATEGORY_USAGE = "category_usage"
    const val CATEGORY_COUNT = "count"
    const val CATEGORY_DURATION_SEC = "duration_sec"
    const val HOURLY_SHORTFORM_COUNT = "hourly_shortform_count"
    const val DEDUCTED_SCORE = "deducted_score"
}

const val CURRENT_FIRESTORE_SCHEMA_VERSION = 2L