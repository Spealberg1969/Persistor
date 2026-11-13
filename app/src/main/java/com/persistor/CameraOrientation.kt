package com.persistor

import android.content.pm.ActivityInfo
import android.view.Surface

internal fun captureAngle(currentAngle: Int, lockedAngle: Int?): Int =
    (lockedAngle ?: currentAngle).mod(360)

internal fun requestedOrientationForRotation(rotation: Int): Int = when (rotation) {
    Surface.ROTATION_90 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    Surface.ROTATION_180 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
    Surface.ROTATION_270 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
    else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
}

/** Camera2 JPEG_ORIENTATION for the rear-facing cameras exposed by Persistor. */
internal fun jpegOrientationDegrees(sensorOrientation: Int, deviceOrientation: Int): Int =
    (sensorOrientation + deviceOrientation).mod(360)

/**
 * Resolve a custom width/height pair to the upright orientation of the captured image.
 * Camera JPEG buffers can be landscape while JPEG_ORIENTATION rotates the displayed photo.
 */
internal fun orientedOutputDimensions(
    targetWidth: Int,
    targetHeight: Int,
    captureWidth: Int,
    captureHeight: Int,
    jpegOrientation: Int,
): Pair<Int, Int> {
    val captureIsPortrait = (captureHeight > captureWidth) xor (jpegOrientation.mod(180) != 0)
    val targetIsPortrait = targetHeight > targetWidth
    return if (captureIsPortrait == targetIsPortrait) {
        targetWidth to targetHeight
    } else {
        targetHeight to targetWidth
    }
}

internal fun CameraPlan.outputDimensionsForDeviceAngle(deviceOrientation: Int): Pair<Int, Int> {
    if (resolution !is Resolution.Custom) return outputSize.width to outputSize.height
    return orientedOutputDimensions(
        targetWidth = outputSize.width,
        targetHeight = outputSize.height,
        captureWidth = captureSize.width,
        captureHeight = captureSize.height,
        jpegOrientation = jpegOrientationDegrees(sensorOrientation, deviceOrientation),
    )
}
