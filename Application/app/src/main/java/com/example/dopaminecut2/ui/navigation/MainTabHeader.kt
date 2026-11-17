package com.example.dopaminecut2.ui.navigation

import androidx.core.view.isVisible
import com.example.dopaminecut2.R
import com.example.dopaminecut2.databinding.ViewTabHeaderBinding

/** A single, non-scrolling header for the four main tabs. Detail screens keep their own headings. */
class MainTabHeader(private val binding: ViewTabHeaderBinding) {
    fun render(destinationId: Int?, contentVisible: Boolean) {
        val title = when (destinationId) {
            R.id.nav_home -> R.string.tab_home
            R.id.nav_goal -> R.string.tab_goal
            R.id.nav_stats -> R.string.tab_stats
            R.id.nav_settings -> R.string.tab_settings
            else -> null
        }
        binding.root.isVisible = contentVisible && title != null
        if (title != null) binding.tabTitle.setText(title) else binding.tabTitle.text = ""
    }
}
