package com.persistor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraReviewNavigationTest {
    @Test
    fun leftAndRightSwipesMoveBetweenPhotos() {
        assertEquals(1, reviewIndexAfterHorizontalSwipe(0, 3, -100f))
        assertEquals(0, reviewIndexAfterHorizontalSwipe(1, 3, 100f))
        assertEquals(2, reviewIndexAfterHorizontalSwipe(1, 3, -100f))
    }

    @Test
    fun swipesRespectFirstLastAndSinglePhotoBoundaries() {
        assertNull(reviewIndexAfterHorizontalSwipe(0, 1, -100f))
        assertNull(reviewIndexAfterHorizontalSwipe(0, 1, 100f))
        assertNull(reviewIndexAfterHorizontalSwipe(0, 3, 100f))
        assertNull(reviewIndexAfterHorizontalSwipe(2, 3, -100f))
        assertNull(reviewIndexAfterHorizontalSwipe(1, 0, -100f))
    }
}
