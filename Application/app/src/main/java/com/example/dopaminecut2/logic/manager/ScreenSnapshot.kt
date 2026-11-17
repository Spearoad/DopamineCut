package com.example.dopaminecut2.logic.manager

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.security.MessageDigest
import java.util.ArrayDeque

data class NormalizedBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
    val area: Float get() = width * height
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    fun centerIsInside(region: NormalizedBounds): Boolean =
        centerX in region.left..region.right && centerY in region.top..region.bottom
}

data class ScreenElement(
    val text: String?,
    val contentDescription: String?,
    val viewId: String?,
    val className: String?,
    val bounds: NormalizedBounds?,
    val isSelected: Boolean
) {
    fun visibleTexts(): List<String> = listOfNotNull(text, contentDescription)
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
}

/** 캡처와 접근성 좌표를 연결하기 위한 실제 디스플레이상의 앱 창. */
data class ScreenWindow(val left: Int, val top: Int, val width: Int, val height: Int) {
    fun toCapture(bounds: NormalizedBounds, captureWidth: Int, captureHeight: Int): NormalizedBounds? {
        if (width <= 0 || height <= 0 || captureWidth <= 0 || captureHeight <= 0) return null
        val mapped = NormalizedBounds(
            (left + bounds.left * width) / captureWidth,
            (top + bounds.top * height) / captureHeight,
            (left + bounds.right * width) / captureWidth,
            (top + bounds.bottom * height) / captureHeight
        )
        // 화면 밖 좌표를 잘라서 유효한 영상 영역인 것처럼 취급하지 않는다.
        return mapped.takeIf { it.left >= 0f && it.top >= 0f && it.right <= 1f && it.bottom <= 1f &&
            it.width > 0f && it.height > 0f }
    }
}

/** 접근성 노드 트리를 테스트 가능한 값 객체로 변환한 화면 스냅샷. */
data class ScreenSnapshot(
    val texts: List<String>,
    val viewIds: List<String>,
    val elements: List<ScreenElement> = emptyList(),
    val window: ScreenWindow? = null,
    val displayId: Int = 0,
    val complete: Boolean = true
) {
    fun contains(keyword: String): Boolean {
        return texts.any { it.contains(keyword, ignoreCase = true) } ||
            viewIds.any { it.contains(keyword, ignoreCase = true) }
    }

    fun containsAny(keywords: Collection<String>): Boolean = keywords.any(::contains)

    /** 전체 리소스 이름 또는 테스트용 ID의 정확한 일치만 허용한다. */
    fun hasResourceId(packageName: String, entryName: String): Boolean = viewIds.any {
        it == "$packageName:id/$entryName" || it == entryName
    }

    fun elementsIn(region: NormalizedBounds): List<ScreenElement> = elements.filter { element ->
        element.bounds?.centerIsInside(region) == true
    }

    fun matchedGroupCount(groups: Collection<Collection<String>>): Int {
        return groups.count(::containsAny)
    }

    fun contentFingerprint(excludedKeywords: Collection<String>): String? {
        val meaningful = texts.asSequence()
            .map(String::trim)
            .filter { it.length >= 3 }
            .filterNot { text ->
                excludedKeywords.any { keyword -> text.equals(keyword, ignoreCase = true) }
            }
            .distinct()
            .take(12)
            .toList()

        if (meaningful.isEmpty()) return null
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(meaningful.joinToString("|").toByteArray(Charsets.UTF_8))
        return bytes.take(12).joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val MAX_NODES = 300

        fun from(rootNode: AccessibilityNodeInfo?, canContinue: () -> Boolean = { true }): ScreenSnapshot {
            if (rootNode == null) return ScreenSnapshot(emptyList(), emptyList())

            val texts = linkedSetOf<String>()
            val viewIds = linkedSetOf<String>()
            val elements = mutableListOf<ScreenElement>()
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(rootNode)

            val rootBounds = Rect().also(rootNode::getBoundsInScreen)
            val rootWidth = rootBounds.width().coerceAtLeast(1)
            val rootHeight = rootBounds.height().coerceAtLeast(1)

            var visited = 0
            var complete = true
            while (queue.isNotEmpty() && visited < MAX_NODES && canContinue()) {
                val node = queue.removeFirst()
                visited++

                // 부모가 숨겨져 있어도 자식의 visibility는 개별 확인한다.
                for (index in 0 until node.childCount) {
                    if (!canContinue()) { complete = false; break }
                    node.getChild(index)?.let(queue::addLast)
                }
                if (!node.isVisibleToUser) {
                    if (node !== rootNode) { @Suppress("DEPRECATION") node.recycle() }
                    continue
                }

                node.text?.toString()?.trim()?.takeIf(String::isNotEmpty)?.let(texts::add)
                node.contentDescription?.toString()?.trim()?.takeIf(String::isNotEmpty)?.let(texts::add)
                node.viewIdResourceName?.trim()?.takeIf(String::isNotEmpty)?.let(viewIds::add)

                val nodeBounds = Rect().also(node::getBoundsInScreen)
                val normalizedBounds = if (nodeBounds.isEmpty) {
                    null
                } else {
                    NormalizedBounds(
                        left = ((nodeBounds.left - rootBounds.left).toFloat() / rootWidth).coerceIn(0f, 1f),
                        top = ((nodeBounds.top - rootBounds.top).toFloat() / rootHeight).coerceIn(0f, 1f),
                        right = ((nodeBounds.right - rootBounds.left).toFloat() / rootWidth).coerceIn(0f, 1f),
                        bottom = ((nodeBounds.bottom - rootBounds.top).toFloat() / rootHeight).coerceIn(0f, 1f)
                    )
                }
                elements += ScreenElement(
                    text = node.text?.toString(),
                    contentDescription = node.contentDescription?.toString(),
                    viewId = node.viewIdResourceName,
                    className = node.className?.toString(),
                    bounds = normalizedBounds,
                    isSelected = node.isSelected
                )
                if (node !== rootNode) { @Suppress("DEPRECATION") node.recycle() }

            }

            if (queue.isNotEmpty()) complete = false
            queue.forEach { if (it !== rootNode) { @Suppress("DEPRECATION") it.recycle() } }
            return ScreenSnapshot(texts.toList(), viewIds.toList(), elements,
                ScreenWindow(rootBounds.left, rootBounds.top, rootBounds.width(), rootBounds.height()),
                if (android.os.Build.VERSION.SDK_INT >= 30) rootNode.window?.displayId ?: 0 else 0,
                complete = complete)
        }
    }
}
