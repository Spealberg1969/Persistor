#pragma once

#include <android/bitmap.h>
#include <jni.h>
#include <cstdint>
#include <cstring>
#include <limits>
#include <string>
#include <vector>

namespace persistor_native {

inline void Throw(JNIEnv* env, const char* class_name, const std::string& message) {
    jclass cls = env->FindClass(class_name);
    if (cls != nullptr) env->ThrowNew(cls, message.c_str());
}

inline bool ReadRgb(JNIEnv* env, jobject bitmap, std::vector<uint8_t>* rgb,
                    uint32_t* width_out, uint32_t* height_out) {
    AndroidBitmapInfo info{};
    int result = AndroidBitmap_getInfo(env, bitmap, &info);
    if (result != ANDROID_BITMAP_RESULT_SUCCESS || info.width == 0 || info.height == 0) {
        Throw(env, "java/lang/IllegalArgumentException", "Invalid or empty bitmap");
        return false;
    }
    const uint64_t pixels = static_cast<uint64_t>(info.width) * info.height;
    if (pixels > 100000000ULL || pixels > std::numeric_limits<size_t>::max() / 3) {
        Throw(env, "java/lang/IllegalArgumentException", "Bitmap is too large for native encoding");
        return false;
    }
    const uint32_t bytes_per_pixel = info.format == ANDROID_BITMAP_FORMAT_RGBA_8888 ? 4U :
                                     info.format == ANDROID_BITMAP_FORMAT_RGB_565 ? 2U : 0U;
    if (bytes_per_pixel == 0 || info.stride < info.width * bytes_per_pixel) {
        Throw(env, "java/lang/IllegalArgumentException", "Bitmap must use RGBA_8888 or RGB_565 format");
        return false;
    }
    try {
        rgb->resize(static_cast<size_t>(pixels) * 3);
    } catch (...) {
        Throw(env, "java/lang/OutOfMemoryError", "Unable to allocate RGB buffer");
        return false;
    }
    void* raw = nullptr;
    result = AndroidBitmap_lockPixels(env, bitmap, &raw);
    if (result != ANDROID_BITMAP_RESULT_SUCCESS || raw == nullptr) {
        Throw(env, "java/lang/IllegalStateException", "Unable to lock bitmap pixels");
        return false;
    }
    const auto* base = static_cast<const uint8_t*>(raw);
    for (uint32_t y = 0; y < info.height; ++y) {
        const uint8_t* row = base + static_cast<size_t>(y) * info.stride;
        for (uint32_t x = 0; x < info.width; ++x) {
            uint8_t r, g, b, a = 255;
            if (info.format == ANDROID_BITMAP_FORMAT_RGBA_8888) {
                // Android's RGBA_8888 bitmap is exposed as the packed ARGB
                // word 0xAARRGGBB; on little-endian Android its bytes are B,G,R,A.
                uint32_t packed;
                std::memcpy(&packed, row + static_cast<size_t>(x) * 4, sizeof(packed));
                r = static_cast<uint8_t>((packed >> 16) & 0xff);
                g = static_cast<uint8_t>((packed >> 8) & 0xff);
                b = static_cast<uint8_t>(packed & 0xff);
                a = static_cast<uint8_t>((packed >> 24) & 0xff);
            } else {
                uint16_t p;
                const uint8_t* src = row + static_cast<size_t>(x) * 2;
                p = static_cast<uint16_t>(src[0] | (static_cast<uint16_t>(src[1]) << 8));
                r = static_cast<uint8_t>(((p >> 11) & 31) * 255 / 31);
                g = static_cast<uint8_t>(((p >> 5) & 63) * 255 / 63);
                b = static_cast<uint8_t>((p & 31) * 255 / 31);
            }
            // JPEG, TIFF G4 and JBIG2 are opaque. Composite transparent pixels on white.
            const size_t o = (static_cast<size_t>(y) * info.width + x) * 3;
            rgb->at(o) = static_cast<uint8_t>((static_cast<unsigned>(r) * a + 255U * (255U - a) + 127U) / 255U);
            rgb->at(o + 1) = static_cast<uint8_t>((static_cast<unsigned>(g) * a + 255U * (255U - a) + 127U) / 255U);
            rgb->at(o + 2) = static_cast<uint8_t>((static_cast<unsigned>(b) * a + 255U * (255U - a) + 127U) / 255U);
        }
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    *width_out = info.width;
    *height_out = info.height;
    return true;
}

inline jbyteArray ToJavaBytes(JNIEnv* env, const uint8_t* data, size_t size) {
    if (size == 0 || size > static_cast<size_t>(std::numeric_limits<jsize>::max())) {
        Throw(env, "java/lang/IllegalStateException", "Encoder returned an invalid output size");
        return nullptr;
    }
    jbyteArray output = env->NewByteArray(static_cast<jsize>(size));
    if (output == nullptr) return nullptr;
    env->SetByteArrayRegion(output, 0, static_cast<jsize>(size), reinterpret_cast<const jbyte*>(data));
    return output;
}

}  // namespace persistor_native
