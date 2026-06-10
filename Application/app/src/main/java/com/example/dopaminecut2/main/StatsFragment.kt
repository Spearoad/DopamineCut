package com.example.dopaminecut2.main

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.toColorInt
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.dopaminecut2.data.model.DailyStatistics
import com.example.dopaminecut2.data.model.DopamineLog
import com.example.dopaminecut2.databinding.FragmentStatsBinding
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.*
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.github.mikephil.charting.utils.ColorTemplate
import kotlinx.coroutines.launch
import java.util.Calendar
import com.github.mikephil.charting.formatter.ValueFormatter

class StatsFragment : Fragment() {

    // binding
    private var _binding: FragmentStatsBinding? = null
    private val binding get() = _binding!!

    // viewModel (HomeFragment와 같은 데이터 공유)
    private val viewModel: MainViewModel by activityViewModels()

    // 화면 생성 시 UI 초기화
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStatsBinding.inflate(inflater, container, false)
        return binding.root
    }

    // 뷰 생성 직후 초기 세팅 및 데이터 감시 호출
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        observeStatisticsData()
    }

    // 누적 데이터 수신 완료 시 하위 차트 및 UI 렌더링 함수 순차적 호출
    private fun observeStatisticsData() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {

                // DailyStatistics 관찰 (달력, 바 차트, 도넛 차트용)
                launch {
                    viewModel.dailyStats.collect { stats ->
                        if (stats != null) {
                            binding.root.post {
                                initStreakUI(stats)
                                if(stats.appUsage.isNotEmpty()) {
                                    initBarChart(stats)
                                    initDoughnutChart(stats)
                                }
                            }
                        }
                    }
                }

                // 도파민 로그 관찰 (시간대 분석 라인 차트용)
                launch {
                    viewModel.dopamineLogs.collect { logs ->
                        if (logs.isNotEmpty()) {
                            binding.root.post {
                                initLineChart(logs)
                            }
                        }
                    }
                }
            }
        }
    }

    // 달성일 확인 캘린더 아이콘 표기 (Streak)
    // stats 사용 예정
    private fun initStreakUI(stats: DailyStatistics) {
        // ※ 참고 : 기본 CalendarView는 UI 커스텀이 제한적
        // 현재 날짜를 선택해주고, 점수에 따른 알림을 세팅.
        val today = Calendar.getInstance().timeInMillis
        binding.calendarStreak.date = today

        // 달력을 누르면 나오는 이벤트
        binding.calendarStreak.setOnDateChangeListener { _, year, month, dayOfMonth ->
            // TODO : 추후 달력 DB를 짜면 여기에 연동
        }
    }

    // 주간 숏폼 시청 추이 렌더링 (Bar Chart)
    private fun initBarChart(stats: DailyStatistics) {
        val entries = ArrayList<BarEntry>()
        val labels = ArrayList<String>()

        var index = 0f
        for ((platform, usage) in stats.appUsage) {
            entries.add(BarEntry(index, usage.shortformCount.toFloat()))
            labels.add(platform.replaceFirstChar { it.uppercase() })
            index += 1f
        }

        val dataSet = BarDataSet(entries, "숏폼 시청 횟수(회)")
        dataSet.colors = ColorTemplate.COLORFUL_COLORS.toList()
        dataSet.valueTextSize = 12f

        // 차트 위 숫자 소수점 제거
        dataSet.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                return "${value.toInt()}회"
            }
        }

        binding.barChartWeekly.data = BarData(dataSet)

        val xAxis = binding.barChartWeekly.xAxis
        xAxis.position = XAxis.XAxisPosition.BOTTOM
        xAxis.valueFormatter = IndexAxisValueFormatter(labels)
        xAxis.granularity = 1f
        xAxis.setDrawGridLines(false)

        // 왼쪽 Y축 소수점 제거 및 1단위로 끊음
        val yAxisLeft = binding.barChartWeekly.axisLeft
        yAxisLeft.granularity = 1f
        yAxisLeft.axisMinimum = 0f
        yAxisLeft.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                return "${value.toInt()}회"
            }
        }
        binding.barChartWeekly.axisRight.isEnabled = false

        binding.barChartWeekly.isDoubleTapToZoomEnabled = false
        binding.barChartWeekly.setScaleEnabled(false)
        binding.barChartWeekly.description.isEnabled = false
        binding.barChartWeekly.extraBottomOffset = 15f
        binding.barChartWeekly.animateY(1000)
        binding.barChartWeekly.invalidate()
    }

    // 취약 시간대 렌더링 (Line Chart)
    private fun initLineChart(logs: List<DopamineLog>) {
        // 0시부터 23시까지 시간대별로 숏폼을 몇 번 봤는지 계산.
        val hourCounts = IntArray(24) { 0 }
        val calendar = Calendar.getInstance()

        logs.forEach { log ->
            calendar.time = log.createdAt
            val hour = calendar.get(Calendar.HOUR_OF_DAY)
            hourCounts[hour]++
        }

        val entries = ArrayList<Entry>()
        for (i in 0..23) {
            entries.add(Entry(i.toFloat(), hourCounts[i].toFloat()))
        }

        val dataSet = LineDataSet(entries, "시간대별 숏폼 시청량")
        dataSet.color = android.graphics.Color.RED // 취약 시간대는 빨간색.
        dataSet.setCircleColor(android.graphics.Color.RED)
        dataSet.lineWidth = 2f
        dataSet.circleRadius = 4f
        dataSet.valueTextSize = 10f
        dataSet.setDrawValues(false)

        dataSet.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String { // 소수점 버리기
                return if (value == 0f) {
                    "0회"
                } else {
                    "${value.toInt()}회"
                }
            }
        }

        binding.lineChartVulnerableTime.data = LineData(dataSet)
        binding.lineChartVulnerableTime.extraBottomOffset = 15f

        // X축 설정 - 0시, 1시로 포맷
        val xAxis = binding.lineChartVulnerableTime.xAxis
        xAxis.position = XAxis.XAxisPosition.BOTTOM
        xAxis.granularity = 1f // 1 단위로 끊기
        xAxis.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                return "${value.toInt()}시"
            }
        }

        // Y축 설정 - 소수점 제거 및 0부터
        val yAxisLeft = binding.lineChartVulnerableTime.axisLeft
        yAxisLeft.granularity = 1f
        yAxisLeft.axisMinimum = 0f // 그래프가 0부터 시작하도록
        yAxisLeft.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                return "${value.toInt()}회"
            }
        }

        binding.lineChartVulnerableTime.axisRight.isEnabled = false

        binding.lineChartVulnerableTime.data = LineData(dataSet)
        binding.lineChartVulnerableTime.xAxis.position = XAxis.XAxisPosition.BOTTOM

        binding.lineChartVulnerableTime.isDoubleTapToZoomEnabled = false
        binding.lineChartVulnerableTime.setScaleEnabled(false)

        binding.lineChartVulnerableTime.description.isEnabled = false
        binding.lineChartVulnerableTime.animateX(1500) // 선이 왼쪽에서 오른쪽으로 그려지도록.
        binding.lineChartVulnerableTime.invalidate()

    }

    // 앱 사용 목적 분석 렌더링 (Doughnut Chart)
    private fun initDoughnutChart(stats: DailyStatistics) {
        var totalRunTime = 0L
        var totalShortformTime = 0L

        // 모든 앱의 사용 시간, 숏폼 시청 시간 합산.

        stats.appUsage.values.forEach {
            totalRunTime += it.runTimeSec
            totalShortformTime += it.shortformTimeSec
        }

        // 일반 시청 시간 = 전체 시간 - 숏폼 시간
        val generalTime = totalRunTime - totalShortformTime

        val entries = ArrayList<PieEntry>()
        if (totalRunTime > 0) {
            entries.add(PieEntry(generalTime.toFloat(), "일반 목적"))
            entries.add(PieEntry(totalShortformTime.toFloat(), "숏폼 시청"))
        }

        val dataSet = PieDataSet(entries, "")
        // 색상 : 일반(BLue 계열), 숏폼(Red 계열)
        dataSet.colors = listOf(
            "#4287f5".toColorInt(),
            "#f54242".toColorInt()
        )
        dataSet.valueTextSize = 12f
        dataSet.valueTextColor = android.graphics.Color.WHITE

        dataSet.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                val minutes = (value / 60).toInt()
                return "${minutes}분"
            }
        }

        binding.doughnutChartUsage.data = PieData(dataSet)

        // 파이 차트의 가운데를 뚫기 (도넛 차트처럼 보이도록)
        binding.doughnutChartUsage.isDrawHoleEnabled = true
        binding.doughnutChartUsage.holeRadius = 40f
        binding.doughnutChartUsage.transparentCircleRadius = 45f
        binding.doughnutChartUsage.centerText = "사용 목적 비율"

        binding.doughnutChartUsage.description.isEnabled = false
        binding.doughnutChartUsage.animateY(1000)
        binding.doughnutChartUsage.invalidate()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}