#include <cstddef>
#include <cstdio>
#include <jpeglib.h>

#include <android/bitmap.h>
#include <jni.h>

#include <csetjmp>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <vector>

#include "native_bitmap_utils.h"

namespace {

struct JpegliErrorManager {
    jpeg_error_mgr base;
    jmp_buf jump;
    char* message;
};

thread_local char g_jpegli_error[JMSG_LENGTH_MAX];

void jpegliErrorExit(j_common_ptr cinfo) {
    auto* error = reinterpret_cast<JpegliErrorManager*>(cinfo->err);
    error->message[0] = '\0';
    (*cinfo->err->format_message)(cinfo, error->message);
    longjmp(error->jump, 1);
}

}  // namespace

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_persistor_JpegliEncoder_nativeEncode(JNIEnv* env, jobject /* receiver */, jobject bitmap, jint quality) {
    if (quality < 1 || quality > 100) {
        persistor_native::Throw(env, "java/lang/IllegalArgumentException", "JPEGli quality must be between 1 and 100");
        return nullptr;
    }

    std::vector<uint8_t> rgb;
    uint32_t width = 0, height = 0;
    if (!persistor_native::ReadRgb(env, bitmap, &rgb, &width, &height)) return nullptr;

    jpeg_compress_struct compressor{};
    JpegliErrorManager error{};
    error.message = g_jpegli_error;
    error.message[0] = '\0';
    compressor.err = jpeg_std_error(&error.base);
    error.base.error_exit = jpegliErrorExit;

    volatile bool compressor_created = false;
    unsigned char* volatile encoded = nullptr;
    unsigned long encoded_size = 0;
    if (setjmp(error.jump) != 0) {
        if (compressor_created) jpeg_destroy_compress(&compressor);
        std::free(const_cast<unsigned char*>(encoded));
        persistor_native::Throw(env, "java/lang/IllegalStateException",
            error.message[0] != '\0' ? error.message : "JPEGli encoding failed");
        return nullptr;
    }

    jpeg_create_compress(&compressor);
    compressor_created = true;
    jpeg_mem_dest(&compressor, const_cast<unsigned char**>(&encoded), &encoded_size);
    compressor.image_width = width;
    compressor.image_height = height;
    compressor.input_components = 3;
    compressor.in_color_space = JCS_RGB;
    jpeg_set_defaults(&compressor);
    jpeg_set_quality(&compressor, quality, TRUE);
    compressor.optimize_coding = TRUE;
    jpeg_start_compress(&compressor, TRUE);

    while (compressor.next_scanline < compressor.image_height) {
        JSAMPROW row = rgb.data() + static_cast<size_t>(compressor.next_scanline) * width * 3;
        jpeg_write_scanlines(&compressor, &row, 1);
    }
    jpeg_finish_compress(&compressor);
    jpeg_destroy_compress(&compressor);
    compressor_created = false;

    if (encoded == nullptr || encoded_size == 0 || encoded_size > 0x7fffffffUL) {
        std::free(const_cast<unsigned char*>(encoded));
        persistor_native::Throw(env, "java/lang/IllegalStateException", "JPEGli returned an invalid or oversized JPEG buffer");
        return nullptr;
    }
    jbyteArray result = persistor_native::ToJavaBytes(env, encoded, static_cast<size_t>(encoded_size));
    std::free(const_cast<unsigned char*>(encoded));
    return result;
}
