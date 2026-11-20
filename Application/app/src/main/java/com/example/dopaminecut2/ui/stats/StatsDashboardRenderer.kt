package com.example.dopaminecut2.ui.stats

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.example.dopaminecut2.R
import com.example.dopaminecut2.data.model.AppUsage
import com.example.dopaminecut2.data.model.DailyStatistics
import com.example.dopaminecut2.databinding.FragmentStatsBinding
import com.example.dopaminecut2.domain.ContentCategory
import com.example.dopaminecut2.domain.GoalMetric
import com.example.dopaminecut2.domain.GoalStatus
import com.example.dopaminecut2.domain.HabitInsightsPolicy
import com.example.dopaminecut2.domain.ManagedGoal
import com.example.dopaminecut2.domain.SupportedPlatform
import com.example.dopaminecut2.ui.common.PrototypeUi
import com.example.dopaminecut2.ui.common.UiFormatters
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.listener.OnChartValueSelectedListener
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** No Firebase calls: all sections and detail taps use the loaded period & month data. */
class StatsDashboardRenderer(
    private val binding: FragmentStatsBinding,
    private val selectDate: (String) -> Unit,
    private val selectHour: (Int) -> Unit = {},
    private val shiftCalendarMonth: (Long) -> Unit = {},
    private val selectCalendarDate: (String) -> Unit = {},
    private val jumpCalendarToday: () -> Unit = {}
) {
    private val context get() = binding.root.context
    private var updatingHourSpinner = false

    private enum class CalendarDayStatus(val label: String, val symbol: String, val colorHex: String) {
        ACHIEVED("달성", "✓", "#5B3FD9"),
        PARTIAL("일부 달성", "◐", "#91641B"),
        EXCEEDED("초과", "×", "#B24655"),
        INSUFFICIENT("기록 부족", "?", "#6E6A80"),
        EXCLUDED("목표 제외", "−", "#6E6A80"),
        TODAY("진행 중", "●", "#5B3FD9"),
        FUTURE("예정", "", "#B8B3C8")
    }

    private data class CalendarGoalItem(
        val label: String,
        val actual: Long,
        val target: Long,
        val unit: String
    )

    private data class CalendarDayRecord(
        val status: CalendarDayStatus,
        val reason: String,
        val items: List<CalendarGoalItem>
    )

    init {
        binding.btnContentInfo.setOnClickListener {
            MaterialAlertDialogBuilder(context).setTitle("콘텐츠 집계 기준")
                .setMessage(
                    "비중은 카테고리가 기록된 내용끼리 비교한 값이에요. 분류 대기·미확인 또는 집계 범위 차이로 전체 사용량과 다를 수 있어요.\n\n" +
                            "일반 숏폼은 시간·영상 수, 라이브·사진 게시물은 시간만 반영해요. 광고는 숏폼 집계에서 제외해요.\n\n" +
                            "형태별 시간은 별도 저장 정보가 부족해 제공하지 않아요. 콘텐츠 카테고리만으로 사용 목적을 판단하지 않아요."
                )
                .setPositiveButton("확인", null).show()
        }

        val hourRanges = (0..23).map { hourRangeLabel(it) }
        binding.spinnerHourlyDetail.adapter = ArrayAdapter(
            context,
            android.R.layout.simple_spinner_item,
            hourRanges
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.spinnerHourlyDetail.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!updatingHourSpinner) {
                    selectHour(position)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        binding.btnCalendarPrev.setOnClickListener { shiftCalendarMonth(-1L) }
        binding.btnCalendarNext.setOnClickListener { shiftCalendarMonth(1L) }
        binding.btnCalendarToday.setOnClickListener { jumpCalendarToday() }
    }

    private fun text(value: String, size: Float = 12f, color: Int = R.color.ui_text_secondary) =
        PrototypeUi.text(context, value, size, color).apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = PrototypeUi.dp(context, 3)
            setLineSpacing(0f, 1f)
        }

    private fun row(label: String, value: String) = PrototypeUi.row(context, label, value).apply {
        for (index in 0 until childCount) {
            (getChildAt(index) as TextView).apply {
                textSize = 12f
                (layoutParams as LinearLayout.LayoutParams).topMargin = PrototypeUi.dp(context, 3)
                setLineSpacing(0f, 1f)
            }
        }
    }

    private fun value(seconds: Double?) = seconds?.let { UiFormatters.duration(it.toLong()) } ?: "—"
    private fun count(number: Double?) = number?.let { String.format(Locale.KOREA, "%.1f개", it) } ?: "—"
    private fun metricValue(seconds: Long, number: Long, metric: StatsMetric) =
        if (metric == StatsMetric.TIME) UiFormatters.duration(seconds) else "${number}개"

    private fun hourRangeLabel(hour: Int): String =
        String.format(Locale.KOREA, "%02d~%02d시", hour, hour + 1)

    fun render(value: StatsDashboard, state: StatsUiState) {
        binding.panelUsage.isVisible = state.section == StatsSection.USAGE
        binding.panelContent.isVisible = state.section == StatsSection.CONTENT
        binding.panelGoals.isVisible = state.section == StatsSection.GOALS
        renderUsage(value, state)
        renderContent(value, state)
        renderGoals(state)
    }

    // =========================================================================
    // [TAB 1] 사용량 (상단 KPI + 시간대별 사용 분석 + 날짜별 변화 + 플랫폼별 사용)
    // =========================================================================
    private fun renderUsage(value: StatsDashboard, state: StatsUiState) {
        val todayOnly = state.period == StatsPeriod.TODAY ||
                (state.start == state.end && state.end?.format(HabitInsightsPolicy.format) == state.today)

        binding.tvSummaryShortformTime.text = UiFormatters.duration(value.totalShortformTimeSec)
        binding.tvSummaryShortformCount.text = "${value.totalShortformCount}개"

        binding.tvSummaryBasis.text = if (todayOnly) "오늘 사용량 · 진행 중" else "기록일 평균 · 오늘 제외 ${value.averageDays}일"
        binding.tvTotals.text =
            "합계 · 숏폼 ${UiFormatters.duration(value.totalShortformTimeSec)} · ${value.totalShortformCount}개 · 앱 ${UiFormatters.duration(value.totalAppTimeSec)}"

        val current = if (state.metric == StatsMetric.TIME) value.averageShortformTimeSec else value.averageCount
        val previous = if (state.metric == StatsMetric.TIME) value.previousTimeAverage else value.previousCountAverage
        binding.tvComparison.text = when {
            todayOnly -> "오늘은 진행 중이라 이전 완료일과 비교하지 않아요."
            current == null || previous == null -> "이전 기간과 비교할 기록이 부족해요."
            else -> {
                val difference = current - previous
                val amount = if (state.metric == StatsMetric.TIME) value(abs(difference)) else count(abs(difference))
                "이전 기간 대비 $amount ${if (difference <= 0) "감소" else "증가"} · 이전 ${value.previousAverageDays}개 기록일 평균"
            }
        }

        // 시간대별 사용 분석 카드 렌더링 (전체 및 앱별 모두 지원)
        renderHourlyCard(value, state)

        // 날짜별 변화 차트
        binding.tvDailyTitle.text = "날짜별 변화 · ${if (state.metric == StatsMetric.TIME) "분" else "개"}"
        chart(
            binding.chartDaily,
            value.days.mapIndexedNotNull { index, day ->
                day.statistics?.let {
                    BarEntry(
                        index.toFloat(),
                        if (state.metric == StatsMetric.TIME) it.totalShortformTimeSec / 60f else it.totalShortformCount.toFloat()
                    )
                }
            },
            value.days.map { "${it.date.substring(4, 6)}/${it.date.takeLast(2)}" },
            if (state.metric == StatsMetric.TIME) "분" else "개"
        )
        binding.chartDaily.setOnChartValueSelectedListener(object : OnChartValueSelectedListener {
            override fun onValueSelected(entry: Entry?, highlight: Highlight?) {
                entry?.x?.toInt()?.let { value.days.getOrNull(it)?.let { day -> selectDate(day.date) } }
            }
            override fun onNothingSelected() = Unit
        })

        binding.dayDetail.removeAllViews()
        val day = value.days.firstOrNull { it.date == state.selectedDate }
        binding.dayDetail.isVisible = day != null
        day?.let {
            binding.dayDetail.addView(
                text(
                    "${it.date.substring(4, 6)}/${it.date.takeLast(2)}${if (it.date == state.today) " · 진행 중" else ""}",
                    13f,
                    R.color.ui_text_primary
                )
            )
            if (it.statistics == null) {
                binding.dayDetail.addView(text("기록 없음"))
            } else {
                it.statistics.appUsage.forEach { (key, usage) ->
                    binding.dayDetail.addView(
                        text(
                            "${SupportedPlatform.fromStorageKey(key)?.displayName ?: key} · 숏폼 ${UiFormatters.duration(usage.shortformTimeSec)} / ${usage.shortformCount}개 · 앱 ${UiFormatters.duration(usage.runTimeSec)}",
                            12f
                        )
                    )
                }
            }
        }

        // 플랫폼별 사용
        binding.platformList.removeAllViews()
        if (value.platforms.isEmpty()) {
            binding.platformList.addView(text("선택한 앱의 기록이 없어요."))
        }
        value.platforms.forEach { platform ->
            binding.platformList.addView(
                row(
                    platform.displayName,
                    metricValue(platform.shortformTimeSec, platform.shortformCount, state.metric)
                )
            )
            binding.platformList.addView(
                text(
                    "앱 ${UiFormatters.duration(platform.appTimeSec)} · 숏폼 ${UiFormatters.duration(platform.shortformTimeSec)} · ${platform.shortformCount}개",
                    11f
                )
            )
        }
        binding.tvUsageRatio.text = if (value.totalAppTimeSec > 0 && value.totalShortformTimeSec <= value.totalAppTimeSec) {
            "앱 사용 중 숏폼 ${value.totalShortformTimeSec * 100 / value.totalAppTimeSec}% · 앱 시간에 숏폼 포함"
        } else {
            "앱 시간에 숏폼 시간이 포함돼요."
        }
    }

    private fun renderHourlyCard(value: StatsDashboard, state: StatsUiState) {
        val appScopeLabel = state.platform?.displayName ?: "전체"
        val isTimeMetric = state.metric == StatsMetric.TIME
        val hasHourlyTime = value.hourlyTimesSec.any { it > 0L }
        val hasHourlyCount = value.hourlyCounts.any { it > 0L }

        val values: List<Double> = when {
            isTimeMetric && hasHourlyTime -> value.hourlyTimesSec.map { it / 60.0 }
            isTimeMetric && !hasHourlyTime -> List(24) { 0.0 }
            else -> value.hourlyCounts.map { it.toDouble() }
        }

        binding.tvHourlyNote.text = when {
            isTimeMetric && !hasHourlyTime && hasHourlyCount ->
                "$appScopeLabel · 시간대별 시청 시간 기록이 아직 없어요. ‘영상 수’로 전환해 확인해 보세요."
            isTimeMetric && !hasHourlyTime ->
                "$appScopeLabel · 선택한 기간에 시간대별 시청 시간 기록이 없어요."
            !isTimeMetric && !hasHourlyCount ->
                "$appScopeLabel · 선택한 기간에 시간대별 영상 수 기록이 없어요."
            else ->
                "$appScopeLabel · ${if (isTimeMetric) "숏폼 시청 시간" else "숏폼 영상 수"} · 선택 기간 합계"
        }

        val maxVal = values.maxOrNull() ?: 0.0
        val ceiling = max(2, (ceil(maxVal / 2.0) * 2).toInt())
        val peaks = values.mapIndexedNotNull { hour, amount -> if (amount == maxVal && amount > 0.0) hour else null }

        if (peaks.isEmpty()) {
            binding.tvHourlyPeakRange.text = "기록 없음"
            binding.tvHourlyPeakValue.text = if (isTimeMetric && !hasHourlyTime && hasHourlyCount) {
                "영상 수 탭에서 시간대 분포를 확인할 수 있어요."
            } else {
                ""
            }
        } else {
            val firstPeak = peaks.first()
            binding.tvHourlyPeakRange.text = hourRangeLabel(firstPeak) +
                    if (peaks.size > 1) " 외 ${peaks.size - 1}개 구간" else ""
            val peakAmountText = if (isTimeMetric && hasHourlyTime) {
                UiFormatters.duration(value.hourlyTimesSec.getOrElse(firstPeak) { 0L })
            } else {
                "${value.hourlyCounts.getOrElse(firstPeak) { 0L }}개"
            }
            binding.tvHourlyPeakValue.text = peakAmountText + if (peaks.size > 1) " · 공동 최다" else ""
        }

        binding.tvHourlyYMax.text = ceiling.toString()
        binding.tvHourlyYHalf.text = (ceiling / 2).toString()

        val selectedHour = (state.selectedHour ?: peaks.firstOrNull() ?: 21).coerceIn(0, 23)
        if (binding.spinnerHourlyDetail.selectedItemPosition != selectedHour) {
            updatingHourSpinner = true
            binding.spinnerHourlyDetail.setSelection(selectedHour, false)
            updatingHourSpinner = false
        }

        // 24시간 막대 그래프 빌드
        binding.hourlyBarsContainer.removeAllViews()
        val chartHeightPx = PrototypeUi.dp(context, 140)
        val barGapPx = PrototypeUi.dp(context, 2)
        val barRadiusPx = PrototypeUi.dp(context, 3).toFloat()

        for (hour in 0..23) {
            val slotWrapper = LinearLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                    marginStart = barGapPx / 2
                    marginEnd = barGapPx / 2
                }
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                orientation = LinearLayout.VERTICAL
                contentDescription = "${hourRangeLabel(hour)}, ${values[hour].toInt()}"
                setOnClickListener { selectHour(hour) }
            }

            val ratio = if (ceiling > 0) (values[hour] / ceiling.toDouble()).coerceIn(0.0, 1.0) else 0.0
            val barHeightPx = if (values[hour] > 0.0) max(PrototypeUi.dp(context, 4), (chartHeightPx * ratio).toInt()) else 0
            val isSelected = hour == selectedHour

            val barView = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, barHeightPx)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(Color.parseColor("#5B3FD9"))
                    cornerRadii = floatArrayOf(barRadiusPx, barRadiusPx, barRadiusPx, barRadiusPx, 0f, 0f, 0f, 0f)
                }
                alpha = if (isSelected) 1.0f else 0.32f
            }
            slotWrapper.addView(barView)
            binding.hourlyBarsContainer.addView(slotWrapper)
        }

        // 선택된 시간대 상세 내역
        binding.tvDetailHourTitle.text = "${hourRangeLabel(selectedHour)} · $appScopeLabel"
        val hourCount = value.hourlyCounts.getOrElse(selectedHour) { 0L }
        val hourTimeSec = value.hourlyTimesSec.getOrElse(selectedHour) { 0L }

        binding.tvDetailHourTotal.text = when {
            isTimeMetric && hasHourlyTime -> UiFormatters.duration(hourTimeSec)
            isTimeMetric && !hasHourlyTime -> "${hourCount}개"
            else -> "${hourCount}개"
        }

        binding.hourlyDetailRows.removeAllViews()
        binding.hourlyDetailRows.addView(row("숏폼 영상 수", "${hourCount}개"))
        binding.hourlyDetailRows.addView(
            row(
                "숏폼 시청 시간",
                if (hasHourlyTime) UiFormatters.duration(hourTimeSec) else "시간 미수집"
            )
        )
    }

    // =========================================================================
    // [TAB 2] 콘텐츠 패널
    // =========================================================================
    private fun renderContent(value: StatsDashboard, state: StatsUiState) {
        binding.categoryList.removeAllViews()
        binding.tvCategoryScope.text = if (state.platform == null) {
            "전체 앱 · ${if (state.metric == StatsMetric.TIME) "시청 시간" else "영상 수"} 기준 · 선택하면 날짜별 기록"
        } else {
            "앱별 카테고리는 아직 저장하지 않아요. ‘전체’를 선택해 주세요."
        }
        if (state.platform != null) {
            binding.tvQualityNotice.text = ""
            return
        }
        val rows = value.categories
            .filter { if (state.metric == StatsMetric.TIME) it.durationSec > 0 else it.count > 0 }
            .sortedByDescending { if (state.metric == StatsMetric.TIME) it.durationSec else it.count }
        val total = rows.sumOf { if (state.metric == StatsMetric.TIME) it.durationSec else it.count }
        if (rows.isEmpty()) {
            binding.categoryList.addView(text("아직 콘텐츠 분류 기록이 없어요."))
        }
        rows.forEach { category ->
            val amount = if (state.metric == StatsMetric.TIME) category.durationSec else category.count
            val item = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                minimumHeight = PrototypeUi.dp(context, 48)
            }
            item.addView(
                row(
                    category.label,
                    "${metricValue(category.durationSec, category.count, state.metric)} · ${if (total > 0) amount * 100 / total else 0}%"
                )
            )
            item.addView(
                ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 1000
                    progress = if (total > 0) (amount.toDouble() / total * 1000).toInt() else 0
                    progressTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.ui_primary))
                    progressBackgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.ui_soft))
                    layoutParams = LinearLayout.LayoutParams(-1, PrototypeUi.dp(context, 4)).apply {
                        topMargin = PrototypeUi.dp(context, 4)
                    }
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
            )
            item.isFocusable = true
            item.setOnClickListener {
                val lines = value.days.map { day ->
                    val matching = day.statistics?.categoryUsage
                        ?.filterKeys { ContentCategory.fromStored(it).id == category.id }
                        ?.values
                    val amountText = if (day.statistics == null) {
                        "기록 없음"
                    } else {
                        metricValue(
                            matching.orEmpty().sumOf { it.durationSec },
                            matching.orEmpty().sumOf { it.count },
                            state.metric
                        )
                    }
                    "${day.date.substring(4, 6)}/${day.date.takeLast(2)} · $amountText"
                }
                MaterialAlertDialogBuilder(context).setTitle(category.label)
                    .setItems(lines.toTypedArray(), null)
                    .setPositiveButton("닫기", null)
                    .show()
            }
            binding.categoryList.addView(item)
        }
        binding.tvQualityNotice.text = if (value.categorySampleLimited) {
            "미확인·분류 대기 등으로 전체 사용량과 다를 수 있어요."
        } else {
            "비중은 카테고리 기록 기준이에요."
        }
    }

    // =========================================================================
    // [TAB 3] 목표 패널 (목표 달성 달력 + 선택 날짜 상세 + 목표별 변화)
    // =========================================================================
    fun renderGoals(state: StatsUiState) {
        val month = state.calendarMonth
        val format = HabitInsightsPolicy.format
        val todayStr = state.today.ifEmpty { LocalDate.now().format(format) }
        val todayDate = LocalDate.parse(todayStr, format)
        val todayMonth = YearMonth.from(todayDate)
        val earliestMonth = todayMonth.minusMonths(11)

        binding.tvCalendarMonth.text = "${month.year}년 ${month.monthValue}월"
        binding.btnCalendarPrev.isEnabled = month.isAfter(earliestMonth)
        binding.btnCalendarPrev.alpha = if (month.isAfter(earliestMonth)) 1.0f else 0.3f
        binding.btnCalendarNext.isEnabled = month.isBefore(todayMonth)
        binding.btnCalendarNext.alpha = if (month.isBefore(todayMonth)) 1.0f else 0.3f

        val statsByDate = (state.calendarDays + state.goalDays).associateBy { it.date }
        val validDates = state.quality.filter { it.valid }.map { it.date }.toSet()
        val hasQualityRecords = state.quality.isNotEmpty()

        val daysInMonth = month.lengthOfMonth()
        val firstDayOfWeek = month.atDay(1).dayOfWeek.value % 7
        val selectedDateStr = state.calendarSelectedDate.ifEmpty { todayStr }

        val monthRecords = LinkedHashMap<String, CalendarDayRecord>()
        for (day in 1..daysInMonth) {
            val dateStr = month.atDay(day).format(format)
            monthRecords[dateStr] = evaluateCalendarDay(
                dateStr = dateStr,
                todayStr = todayStr,
                goals = state.goals,
                plans = state.plans,
                stat = statsByDate[dateStr],
                isValidQuality = !hasQualityRecords || dateStr in validDates
            )
        }

        binding.gridCalendarDays.removeAllViews()
        val cellHeightPx = PrototypeUi.dp(context, 48)
        val cellMarginPx = PrototypeUi.dp(context, 1)

        for (blank in 0 until firstDayOfWeek) {
            val emptyCell = View(context).apply {
                layoutParams = GridLayout.LayoutParams(
                    GridLayout.spec(GridLayout.UNDEFINED),
                    GridLayout.spec(blank, 1f)
                ).apply {
                    width = 0
                    height = cellHeightPx
                    setMargins(cellMarginPx, cellMarginPx, cellMarginPx, cellMarginPx)
                }
            }
            binding.gridCalendarDays.addView(emptyCell)
        }

        for (day in 1..daysInMonth) {
            val col = (firstDayOfWeek + day - 1) % 7
            val dateStr = month.atDay(day).format(format)
            val record = monthRecords[dateStr] ?: continue
            val isSelected = dateStr == selectedDateStr
            val isToday = dateStr == todayStr
            val isFuture = record.status == CalendarDayStatus.FUTURE

            val cell = LinearLayout(context).apply {
                layoutParams = GridLayout.LayoutParams(
                    GridLayout.spec(GridLayout.UNDEFINED),
                    GridLayout.spec(col, 1f)
                ).apply {
                    width = 0
                    height = cellHeightPx
                    setMargins(cellMarginPx, cellMarginPx, cellMarginPx, cellMarginPx)
                }
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isEnabled = !isFuture
                contentDescription = "${month.monthValue}월 ${day}일, ${record.status.label}"
                if (isSelected) {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = PrototypeUi.dp(context, 10).toFloat()
                        setColor(Color.parseColor("#EEE9FF"))
                        setStroke(PrototypeUi.dp(context, 1), Color.parseColor("#5B3FD9"))
                    }
                }
                if (!isFuture) {
                    setOnClickListener { selectCalendarDate(dateStr) }
                }
            }

            val dayNumberView = TextView(context).apply {
                text = day.toString()
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                if (isToday) {
                    val badgeSize = PrototypeUi.dp(context, 22)
                    layoutParams = LinearLayout.LayoutParams(badgeSize, badgeSize)
                    setTextColor(Color.WHITE)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.parseColor("#5B3FD9"))
                    }
                } else {
                    setTextColor(
                        if (isFuture) Color.parseColor("#B8B3C8") else Color.parseColor(record.status.colorHex)
                    )
                }
            }

            val symbolView = TextView(context).apply {
                text = record.status.symbol
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor(record.status.colorHex))
            }

            cell.addView(dayNumberView)
            cell.addView(symbolView)
            binding.gridCalendarDays.addView(cell)
        }

        val validCompleted = monthRecords.values.filter {
            it.status == CalendarDayStatus.ACHIEVED ||
                    it.status == CalendarDayStatus.PARTIAL ||
                    it.status == CalendarDayStatus.EXCEEDED
        }
        val achievedCount = validCompleted.count { it.status == CalendarDayStatus.ACHIEVED }
        val partialCount = validCompleted.count { it.status == CalendarDayStatus.PARTIAL }
        binding.tvCalendarSummary.text =
            "모든 목표 달성 ${achievedCount}일 · 일부 달성 ${partialCount}일 / 평가 ${validCompleted.size}일"

        val selectedRecord = monthRecords[selectedDateStr]
            ?: evaluateCalendarDay(
                dateStr = selectedDateStr,
                todayStr = todayStr,
                goals = state.goals,
                plans = state.plans,
                stat = statsByDate[selectedDateStr],
                isValidQuality = !hasQualityRecords || selectedDateStr in validDates
            )
        val selMonth = selectedDateStr.substring(4, 6).toIntOrNull() ?: month.monthValue
        val selDay = selectedDateStr.takeLast(2).toIntOrNull() ?: 1
        binding.tvCalendarSelectedDate.text = "${selMonth}월 ${selDay}일"
        binding.tvCalendarSelectedBadge.text = selectedRecord.status.label
        binding.tvCalendarSelectedDesc.text = selectedRecord.reason

        binding.calendarSelectedGoalsContainer.removeAllViews()
        if (selectedRecord.items.isNotEmpty() &&
            selectedRecord.status != CalendarDayStatus.EXCLUDED &&
            selectedRecord.status != CalendarDayStatus.INSUFFICIENT
        ) {
            selectedRecord.items.forEach { item ->
                val exceeded = item.actual > item.target
                val rowContainer = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, PrototypeUi.dp(context, 10), 0, PrototypeUi.dp(context, 2))
                }
                val topRow = row(
                    item.label,
                    "${item.actual}${item.unit} / ${item.target}${item.unit}"
                )
                (topRow.getChildAt(0) as? TextView)?.setTextColor(Color.parseColor("#1F1D2B"))
                (topRow.getChildAt(1) as? TextView)?.apply {
                    setTextColor(Color.parseColor("#1F1D2B"))
                    setTypeface(null, Typeface.BOLD)
                }
                rowContainer.addView(topRow)

                val bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 1000
                    val ratio = if (item.target > 0) min(1.0, item.actual.toDouble() / item.target.toDouble()) else 1.0
                    progress = (ratio * 1000).toInt()
                    progressTintList = ColorStateList.valueOf(
                        Color.parseColor(if (exceeded) "#B24655" else "#5B3FD9")
                    )
                    progressBackgroundTintList = ColorStateList.valueOf(Color.parseColor("#EFEBF8"))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        PrototypeUi.dp(context, 5)
                    ).apply {
                        topMargin = PrototypeUi.dp(context, 6)
                    }
                }
                rowContainer.addView(bar)

                val stateLabel = TextView(context).apply {
                    text = when {
                        selectedRecord.status == CalendarDayStatus.TODAY -> if (exceeded) "현재 초과" else "진행 중"
                        exceeded -> "초과"
                        else -> "✓ 달성"
                    }
                    textSize = 11f
                    gravity = Gravity.END
                    setTextColor(Color.parseColor(if (exceeded) "#B24655" else "#6E6A80"))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = PrototypeUi.dp(context, 3)
                    }
                }
                rowContainer.addView(stateLabel)
                binding.calendarSelectedGoalsContainer.addView(rowContainer)
            }
        }

        binding.tvGoalsNote.text = "${month.monthValue}월 · 평가 가능한 완료일 기준 (${validCompleted.size}일)"
        binding.calendarGoalComparisonsContainer.removeAllViews()
        if (state.goals.isEmpty()) {
            binding.calendarGoalComparisonsContainer.addView(text("설정된 목표가 없어요."))
        } else {
            state.goals.forEach { goal ->
                val label = metricLabel(goal.metric)
                val achievedDays = validCompleted.count { dayRec ->
                    dayRec.items.firstOrNull { it.label == label }?.let { it.actual <= it.target } == true
                }
                val compRow = row(label, "$achievedDays / ${validCompleted.size}일 달성")
                (compRow.getChildAt(0) as? TextView)?.setTextColor(Color.parseColor("#1F1D2B"))
                (compRow.getChildAt(1) as? TextView)?.apply {
                    setTextColor(Color.parseColor("#5B3FD9"))
                    setTypeface(null, Typeface.BOLD)
                }
                binding.calendarGoalComparisonsContainer.addView(compRow)
            }
        }

        binding.goalList.removeAllViews()
        if (state.goalEvidenceError != null || !state.goalEvidenceLoaded) {
            binding.goalList.addView(text(state.goalEvidenceError ?: "목표 비교 기록 확인 중"))
            return
        }
        state.goals.forEach { goal ->
            val result = StatsGoalAnalysis.compare(goal, state.plans[goal.metric], state.goalDays, state.quality, state.today)
            val unit = if (goal.metric == GoalMetric.DAILY_COUNT) "개" else "분"
            val label = metricLabel(goal.metric)
            fun amount(number: Double?) = number?.let { String.format(Locale.KOREA, "%.1f%s", it, unit) } ?: "—"
            val card = PrototypeUi.column(context).apply {
                setPadding(
                    PrototypeUi.dp(context, 10),
                    PrototypeUi.dp(context, 8),
                    PrototypeUi.dp(context, 10),
                    PrototypeUi.dp(context, 8)
                )
            }
            card.addView(text(label, 14f, R.color.ui_text_primary))
            card.addView(
                text(
                    "${if (goal.status == GoalStatus.ACTIVE) "진행 중" else "중지됨"} · 하루 ${goal.target}$unit · ${goal.platforms.joinToString { it.displayName }}",
                    11f
                )
            )
            card.addView(row("기준 → 현재 평균", "${amount(result.baseline)} → ${amount(result.average)}"))
            if (result.average != null && result.baseline != null) {
                val difference = result.average - result.baseline
                card.addView(
                    text(
                        "기준 대비 ${amount(abs(difference))} ${if (difference <= 0) "감소" else "증가"}",
                        12f,
                        R.color.ui_primary
                    )
                )
            }
            card.addView(text(result.notice, 11f))
            binding.goalList.addView(card)
        }
    }

    private fun evaluateCalendarDay(
        dateStr: String,
        todayStr: String,
        goals: List<ManagedGoal>,
        plans: Map<GoalMetric, com.example.dopaminecut2.data.local.GoalPlan>,
        stat: DailyStatistics?,
        isValidQuality: Boolean
    ): CalendarDayRecord {
        if (dateStr > todayStr) {
            return CalendarDayRecord(CalendarDayStatus.FUTURE, "아직 오지 않은 날짜예요.", emptyList())
        }
        val activeGoals = goals.filter { it.status == GoalStatus.ACTIVE }
        if (activeGoals.isEmpty()) {
            return if (dateStr == todayStr) {
                CalendarDayRecord(CalendarDayStatus.TODAY, "적용 중인 목표가 없어요.", emptyList())
            } else {
                CalendarDayRecord(CalendarDayStatus.EXCLUDED, "적용 중인 목표가 없어 평가에서 제외됐어요.", emptyList())
            }
        }

        val eligibleGoals = mutableListOf<ManagedGoal>()
        var excludedCount = 0
        var missingPlanCount = 0

        for (goal in activeGoals) {
            val plan = plans[goal.metric]
            when {
                plan == null || plan.revision != goal.revision || plan.metric != goal.metric -> missingPlanCount++
                !plan.eligible(dateStr) -> excludedCount++
                else -> eligibleGoals.add(goal)
            }
        }

        if (eligibleGoals.isEmpty()) {
            return when {
                excludedCount > 0 -> CalendarDayRecord(
                    CalendarDayStatus.EXCLUDED,
                    "목표 적용 제외일이에요. 사용 기록은 계속 수집해요.",
                    emptyList()
                )
                missingPlanCount > 0 -> CalendarDayRecord(
                    CalendarDayStatus.INSUFFICIENT,
                    "해당 날짜에 일치하는 목표 이력이 없어 달성 여부를 확정하지 않아요.",
                    emptyList()
                )
                else -> CalendarDayRecord(
                    CalendarDayStatus.EXCLUDED,
                    "적용된 목표가 없어요.",
                    emptyList()
                )
            }
        }

        if (dateStr < todayStr && (stat == null || !isValidQuality)) {
            return CalendarDayRecord(
                CalendarDayStatus.INSUFFICIENT,
                "측정 연결이 끊겼거나 기록이 부족해 달성 여부를 확정하지 않아요.",
                emptyList()
            )
        }

        val items = eligibleGoals.map { goal ->
            val actual = goal.platforms.sumOf { platform ->
                HabitInsightsPolicy.value(stat?.appUsage?.get(platform.storageKey) ?: AppUsage(), goal.metric)
            }.toLong()
            CalendarGoalItem(
                label = metricLabel(goal.metric),
                actual = actual,
                target = goal.target.toLong(),
                unit = if (goal.metric == GoalMetric.DAILY_COUNT) "개" else "분"
            )
        }

        if (dateStr == todayStr) {
            return CalendarDayRecord(
                CalendarDayStatus.TODAY,
                "오늘은 진행 중이에요. 최종 결과는 하루가 끝난 뒤 확정해요.",
                items
            )
        }

        val metCount = items.count { it.actual <= it.target }
        val status = when {
            metCount == items.size -> CalendarDayStatus.ACHIEVED
            metCount == 0 -> CalendarDayStatus.EXCEEDED
            else -> CalendarDayStatus.PARTIAL
        }
        return CalendarDayRecord(
            status = status,
            reason = "적용 목표 ${items.size}개 중 ${metCount}개 충족 · 해당 날짜에 저장된 목표 기준",
            items = items
        )
    }

    private fun metricLabel(metric: GoalMetric): String = when (metric) {
        GoalMetric.DAILY_TIME -> "숏폼 시청 시간"
        GoalMetric.DAILY_COUNT -> "숏폼 시청 영상 수"
        GoalMetric.APP_TIME -> "앱 사용 시간"
    }

    private fun chart(chart: BarChart, entries: List<BarEntry>, labels: List<String>, unit: String) {
        val textColor = ContextCompat.getColor(context, R.color.ui_text_secondary)
        chart.setNoDataText("기록 없음")
        chart.setNoDataTextColor(textColor)
        if (entries.isEmpty()) {
            chart.clear()
            return
        }
        chart.data = BarData(
            BarDataSet(entries, unit).apply {
                color = ContextCompat.getColor(context, R.color.ui_primary)
                setDrawValues(false)
            }
        )
        chart.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            valueFormatter = IndexAxisValueFormatter(labels)
            granularity = 1f
            setLabelCount(4, false)
            setDrawGridLines(false)
            this.textColor = textColor
            textSize = 10f
            axisMinimum = -0.5f
            axisMaximum = labels.lastIndex + 0.5f
        }
        chart.axisLeft.apply {
            axisMinimum = 0f
            this.textColor = textColor
            textSize = 10f
        }
        chart.axisRight.isEnabled = false
        chart.description.isEnabled = false
        chart.legend.isEnabled = false
        chart.setScaleEnabled(false)
        chart.invalidate()
        chart.contentDescription = entries.joinToString { "${labels.getOrNull(it.x.toInt()).orEmpty()} ${it.y}$unit" }
    }

    fun clear() {
        binding.chartDaily.clear()
        binding.chartHourly.clear()
    }
}