package com.example.dopaminecut2.ui.common

import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.example.dopaminecut2.R

/** Render both states every time so granting a permission also clears the warning styling. */
fun TextView.renderPermissionStatus(granted: Boolean) {
    setText(if (granted) R.string.permission_connected else R.string.permission_required)
    setTextColor(ContextCompat.getColor(context, if (granted) R.color.ui_success else R.color.ui_warning))
    // Choose the real font face, rather than deriving NORMAL from a previously bold custom face.
    typeface = ResourcesCompat.getFont(context,
        if (granted) R.font.pretendard_regular else R.font.pretendard_bold)
}
