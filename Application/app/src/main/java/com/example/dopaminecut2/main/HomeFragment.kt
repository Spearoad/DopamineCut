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
import com.example.dopaminecut2.data.model.AppUsage
import com.example.dopaminecut2.data.model.DopamineLog
import com.example.dopaminecut2.databinding.FragmentHomeBinding
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.data.PieEntry
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.github.mikephil.charting.utils.ColorTemplate
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {

    // binding 객체
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    // viewModel 객체 (Activity와 공유)
    private val viewModel: MainViewModel by activityViewModels()

    // 화면 생성 시 UI 초기화
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    // 뷰 생성 직후 UI 세팅 및 데이터 감시 호출
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 버튼 클릭 (60% [hard lock] : 시간이나 횟수 중 하나라도 60% 넘기면 잠김)
        binding.btnOpenTargetSetting.setOnClickListener {
            val stats = viewModel.dailyStats.value
            val targetMin = viewModel.currentTargetMin.value
            val targetCount = viewModel.currentTargetCount.value

            var totalUsedSec = 0L
            var currentCount = 0L
            stats?.appUsage?.values?.forEach {
                totalUsedSec += it.runTimeSec
                currentCount += it.shortformCount
            }

            val maxAllowedSec = (targetMin * 60) * 0.6
            val maxAllowedCount = targetCount * 0.6

            val isTimeLocked = targetMin > 0 && totalUsedSec > maxAllowedSec
            val isCountLocked = targetCount > 0 && currentCount > maxAllowedCount

            if (isTimeLocked || isCountLocked) {
                android.widget.Toast.makeText(
                    requireContext(),
                    "🔒 이미 오늘 목표의 60% 이상을 사용했습니다.\n오늘은 더 이상 목표를 수정할 수 없습니다!",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            } else {
                val dialog = TargetSettingDialog()
                dialog.show(childFragmentManager, "TargetSettingDialog")
            }
        }

        observeDashboardData()
    }

    // 데이터 감시하여 점수 갱신 및 차트 함수 호출
    private fun observeDashboardData() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {

                // 도파민 로그 관찰 (파이 차트)
                launch {
                    viewModel.dopamineLogs.collect { logs ->
                        if (logs.isNotEmpty()) {
                            binding.pieChartCategory.post { initPieChart(logs) }
                        }
                    }
                }

                // 통계 데이터가 바뀔 때마다 텍스트, 차트 업데이트
                launch {
                    viewModel.dailyStats.collect { stats ->
                        updateSummaryUI() // 텍스트 갱신 (초 단위까지)
                        if (stats != null && stats.appUsage.isNotEmpty()) {
                            binding.barChartAppRanking.post {
                                initHorizontalBarChart(stats.appUsage)
                            }
                        }
                    }
                }

                // 설정한 목표시간이 바뀔 때마다 텍스트 업데이트
                launch {
                    viewModel.currentTargetMin.collect {
                        updateSummaryUI()
                    }
                }
            }
        }
    }

    // 초(second) 단위로 UI 텍스트 설정
    private fun updateSummaryUI() {
        val stats = viewModel.dailyStats.value
        val targetMin = viewModel.currentTargetMin.value
        val targetCount = viewModel.currentTargetCount.value

        var totalUsedSec = 0L
        var currentCount = 0L
        stats?.appUsage?.values?.forEach {
            totalUsedSec += it.runTimeSec
            currentCount += it.shortformCount
        }

        // --- 시간 프로그레스 바 세팅 ---
        val usedMin = totalUsedSec / 60
        if (targetMin == 0) { // 0이면 무제한 모드
            binding.tvTimeStatus.text = "${usedMin}분 / 무제한"
            binding.pbTime.progress = 0
        } else {
            binding.tvTimeStatus.text = "${usedMin}분 / ${targetMin}분"
            val timePercent = ((totalUsedSec.toFloat() / (targetMin * 60)) * 100).toInt()
            binding.pbTime.progress = minOf(timePercent, 100) // 최대 100%까지만 차오름
        }

        // 횟수 프로그레스 바 세팅
        if (targetCount == 0) { // 0이면 무제한 모드
            binding.tvCountStatus.text = "${currentCount}회 / 무제한"
            binding.pbCount.progress = 0
        } else {
            binding.tvCountStatus.text = "${currentCount}회 / ${targetCount}회"
            val countPercent = ((currentCount.toFloat() / targetCount) * 100).toInt()
            binding.pbCount.progress = minOf(countPercent, 100)
        }
    }

    // 카테고리 비율 렌더링 (Pie Chart)
    private fun initPieChart(logs: List<DopamineLog>) {
        val categoryCountMap = logs.groupingBy { it.category }.eachCount()

        // 파이 차트에 들어갈 엔트리 만들기
        val entries = ArrayList<PieEntry>()
        for ((category, count) in categoryCountMap) {
            // 알 수 없는 카테고리의 이름 변경
            val labelName = if (category == "UNKNOWN") "기타" else category
            entries.add(PieEntry(count.toFloat(), labelName))
        }

        // 차트 색상 및 글씨 크기 세팅
        val dataSet = PieDataSet(entries, "")

        // color 리스트
        val pieColors = listOf(
            "#FF9999".toColorInt(), // 빨강
            "#FFCC99".toColorInt(), // 주황
            "#FFD54F".toColorInt(), // 노랑 (가독성 추가를 위한 개나리색)
            "#99FF99".toColorInt(), // 초록
            "#99CCFF".toColorInt(), // 파랑
            "#9999FF".toColorInt(), // 남색
            "#CC99FF".toColorInt(), // 보라
            "#FF99CC".toColorInt(), // 핑크
            "#B3B3B3".toColorInt()  // 회색 (기타 등)
        )

        dataSet.colors = pieColors
        dataSet.valueTextSize = 14f
        dataSet.valueTextColor = android.graphics.Color.WHITE // 흰색 글씨

        // 차트에 데이터 적용/그리기
        val data = PieData(dataSet)

        data.setValueFormatter(object : com.github.mikephil.charting.formatter.ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                return "${value.toInt()}회"
            }
        })

        binding.pieChartCategory.data = data
        binding.pieChartCategory.description.isEnabled = false // 보기 싫은 설명 텍스트 숨기기
        binding.pieChartCategory.setEntryLabelColor(android.graphics.Color.BLACK) // 검정색 라벨
        binding.pieChartCategory.animateY(1000) // 1초를 두고 나타나는 애니메이션
        binding.pieChartCategory.invalidate() // 화면 갱신
    }

    // 앱 사용 시간 랭킹 렌더링 (Horizontal Bar)
    private fun initHorizontalBarChart(appUsage: Map<String, AppUsage>) {
        val sortedUsage = appUsage.entries.sortedByDescending { it.value.runTimeSec }
        val entries = ArrayList<BarEntry>()
        val labels = ArrayList<String>()

        sortedUsage.forEachIndexed { index, entry ->
            val platformName = entry.key
            val minutesUsed = entry.value.runTimeSec / 60f
            entries.add(BarEntry(index.toFloat(), minutesUsed))
            labels.add(platformName.replaceFirstChar { it.uppercase() })
        }

        val dataSet = BarDataSet(entries, "사용 시간")
        dataSet.colors = ColorTemplate.PASTEL_COLORS.toList()
        dataSet.valueTextSize = 12f

        // 0.833과 같은 소수 표현을 초 / 분 단위로 표ㅕ현
        dataSet.valueFormatter = object : com.github.mikephil.charting.formatter.ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                val totalSeconds = (value * 60).toInt()
                val min = totalSeconds / 60
                val sec = totalSeconds % 60
                return if (min > 0) "${min}분 ${sec}초" else "${sec}초"
            }
        }

        val data = BarData(dataSet)
        binding.barChartAppRanking.data = data

        val xAxis = binding.barChartAppRanking.xAxis
        xAxis.position = XAxis.XAxisPosition.BOTTOM
        xAxis.valueFormatter = IndexAxisValueFormatter(labels)
        xAxis.setDrawGridLines(false)
        xAxis.granularity = 1f

        // 하단 축(시간 축)도 소수점 없애고 정수(분)로만 표시
        val yAxis = binding.barChartAppRanking.axisLeft
        yAxis.granularity = 1f // 1분 단위로만 끊기
        yAxis.valueFormatter = object : com.github.mikephil.charting.formatter.ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                return "${value.toInt()}분"
            }
        }

        binding.barChartAppRanking.isDoubleTapToZoomEnabled = false
        binding.barChartAppRanking.setScaleEnabled(false)
        binding.barChartAppRanking.description.isEnabled = false
        binding.barChartAppRanking.axisRight.isEnabled = false
        binding.barChartAppRanking.extraLeftOffset = 30f
        binding.barChartAppRanking.animateY(1000)
        binding.barChartAppRanking.invalidate()
    }

    // 화면 꺼질 때 메모리 누수 방지용
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}