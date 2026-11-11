package com.persistor

/** Left swipe opens the next (older) image; right swipe opens the previous image. */
internal fun reviewIndexAfterHorizontalSwipe(currentIndex: Int, itemCount: Int, deltaX: Float): Int? {
    if (itemCount <= 0 || currentIndex !in 0 until itemCount || deltaX == 0f) return null
    val nextIndex = if (deltaX < 0f) currentIndex + 1 else currentIndex - 1
    return nextIndex.takeIf { it in 0 until itemCount }
}
