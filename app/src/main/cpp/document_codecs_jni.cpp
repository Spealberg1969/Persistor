#include <cstddef>
#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <vector>

#include <jni.h>
#include <tiffio.h>
#include <allheaders.h>
#include "jbig2enc.h"

#include "native_bitmap_utils.h"

namespace {

struct MemoryFile {
    std::vector<uint8_t> bytes;
    uint64_t position = 0;
};

tmsize_t memoryRead(thandle_t handle, void* destination, tmsize_t count) {
    auto* file = static_cast<MemoryFile*>(handle);
    if (count <= 0 || file->position >= file->bytes.size()) return 0;
    const uint64_t available = file->bytes.size() - file->position;
    const size_t size = static_cast<size_t>(std::min<uint64_t>(available, static_cast<uint64_t>(count)));
    std::memcpy(destination, file->bytes.data() + file->position, size);
    file->position += size;
    return static_cast<tmsize_t>(size);
}

tmsize_t memoryWrite(thandle_t handle, void* source, tmsize_t count) {
    auto* file = static_cast<MemoryFile*>(handle);
    if (count <= 0) return 0;
    const uint64_t end = file->position + static_cast<uint64_t>(count);
    if (end > static_cast<uint64_t>(std::numeric_limits<size_t>::max())) return 0;
    try {
        if (file->bytes.size() < end) file->bytes.resize(static_cast<size_t>(end));
    } catch (...) {
        return 0;
    }
    std::memcpy(file->bytes.data() + file->position, source, static_cast<size_t>(count));
    file->position = end;
    return count;
}

toff_t memorySeek(thandle_t handle, toff_t offset, int whence) {
    auto* file = static_cast<MemoryFile*>(handle);
    uint64_t next = 0;
    if (whence == SEEK_SET) {
        next = static_cast<uint64_t>(offset);
    } else if (whence == SEEK_CUR) {
        if (offset > std::numeric_limits<uint64_t>::max() - file->position) return static_cast<toff_t>(-1);
        next = file->position + static_cast<uint64_t>(offset);
    } else if (whence == SEEK_END) {
        if (offset > std::numeric_limits<uint64_t>::max() - file->bytes.size()) return static_cast<toff_t>(-1);
        next = static_cast<uint64_t>(file->bytes.size()) + static_cast<uint64_t>(offset);
    } else {
        return static_cast<toff_t>(-1);
    }
    if (next > static_cast<uint64_t>(std::numeric_limits<size_t>::max())) return static_cast<toff_t>(-1);
    file->position = next;
    return static_cast<toff_t>(next);
}

int memoryClose(thandle_t) { return 0; }
toff_t memorySize(thandle_t handle) {
    return static_cast<toff_t>(static_cast<MemoryFile*>(handle)->bytes.size());
}
int memoryMap(thandle_t, void**, toff_t*) { return 0; }
void memoryUnmap(thandle_t, void*, toff_t) {}

inline uint8_t luminance(const uint8_t* rgb) {
    return static_cast<uint8_t>((77U * rgb[0] + 150U * rgb[1] + 29U * rgb[2] + 128U) >> 8);
}

bool makeBinaryPix(const std::vector<uint8_t>& rgb, uint32_t width, uint32_t height,
                   int threshold, PIX** output) {
    PIX* pix = pixCreate(static_cast<l_int32>(width), static_cast<l_int32>(height), 1);
    if (pix == nullptr) return false;
    l_uint32* data = pixGetData(pix);
    const l_int32 wpl = pixGetWpl(pix);
    for (uint32_t y = 0; y < height; ++y) {
        l_uint32* row = data + static_cast<size_t>(y) * static_cast<size_t>(wpl);
        for (uint32_t x = 0; x < width; ++x) {
            const uint8_t* p = rgb.data() + (static_cast<size_t>(y) * width + x) * 3;
            // In Leptonica's 1-bpp Pix, a set bit denotes black ink.
            if (luminance(p) < threshold) SET_DATA_BIT(row, static_cast<l_int32>(x));
        }
    }
    pixSetResolution(pix, 300, 300);
    *output = pix;
    return true;
}

}  // namespace

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_persistor_NativeImageEncoder_nativeEncodeTiffG4(JNIEnv* env, jobject /* receiver */, jobject bitmap, jint threshold) {
    if (threshold < 1 || threshold > 254) {
        persistor_native::Throw(env, "java/lang/IllegalArgumentException", "Binarization threshold must be 1..254");
        return nullptr;
    }
    std::vector<uint8_t> rgb;
    uint32_t width = 0, height = 0;
    if (!persistor_native::ReadRgb(env, bitmap, &rgb, &width, &height)) return nullptr;

    MemoryFile output;
    TIFF* tiff = TIFFClientOpen("persistor-memory.tif", "w", &output,
        memoryRead, memoryWrite, memorySeek, memoryClose, memorySize, memoryMap, memoryUnmap);
    if (tiff == nullptr) {
        persistor_native::Throw(env, "java/lang/IllegalStateException", "Could not create in-memory TIFF writer");
        return nullptr;
    }
    const uint16_t samples = 1;
    const uint16_t bits = 1;
    const uint16_t compression = COMPRESSION_CCITTFAX4;
    const uint16_t photometric = PHOTOMETRIC_MINISWHITE;
    const uint16_t fillorder = FILLORDER_MSB2LSB;
    const uint16_t planar = PLANARCONFIG_CONTIG;
    const uint16_t resolution_unit = RESUNIT_INCH;
    bool ok = true;
    ok = ok && TIFFSetField(tiff, TIFFTAG_IMAGEWIDTH, width) == 1;
    ok = ok && TIFFSetField(tiff, TIFFTAG_IMAGELENGTH, height) == 1;
    ok = ok && TIFFSetField(tiff, TIFFTAG_SAMPLESPERPIXEL, samples) == 1;
    ok = ok && TIFFSetField(tiff, TIFFTAG_BITSPERSAMPLE, bits) == 1;
    ok = ok && TIFFSetField(tiff, TIFFTAG_COMPRESSION, compression) == 1;
    ok = ok && TIFFSetField(tiff, TIFFTAG_PHOTOMETRIC, photometric) == 1;
    ok = ok && TIFFSetField(tiff, TIFFTAG_FILLORDER, fillorder) == 1;
    ok = ok && TIFFSetField(tiff, TIFFTAG_PLANARCONFIG, planar) == 1;
    ok = ok && TIFFSetField(tiff, TIFFTAG_ROWSPERSTRIP, TIFFDefaultStripSize(tiff, 0)) == 1;
    ok = ok && TIFFSetField(tiff, TIFFTAG_XRESOLUTION, 300.0f) == 1;
    ok = ok && TIFFSetField(tiff, TIFFTAG_YRESOLUTION, 300.0f) == 1;
    ok = ok && TIFFSetField(tiff, TIFFTAG_RESOLUTIONUNIT, resolution_unit) == 1;

    const size_t row_bytes = (static_cast<size_t>(width) + 7) / 8;
    std::vector<uint8_t> row;
    try { row.resize(row_bytes); } catch (...) { ok = false; }
    for (uint32_t y = 0; ok && y < height; ++y) {
        std::fill(row.begin(), row.end(), 0);
        for (uint32_t x = 0; x < width; ++x) {
            const uint8_t* p = rgb.data() + (static_cast<size_t>(y) * width + x) * 3;
            // PHOTOMETRIC_MINISWHITE: bit 1 means black, bit 0 means white.
            if (luminance(p) < threshold) row[x >> 3] |= static_cast<uint8_t>(0x80U >> (x & 7));
        }
        if (TIFFWriteScanline(tiff, row.data(), y, 0) < 0) ok = false;
    }
    if (ok && TIFFWriteDirectory(tiff) != 1) ok = false;
    TIFFClose(tiff);
    if (!ok || output.bytes.size() < 8 || output.bytes[0] != 'I' || output.bytes[1] != 'I') {
        persistor_native::Throw(env, "java/lang/IllegalStateException", "CCITT Group 4 TIFF encoding failed");
        return nullptr;
    }
    return persistor_native::ToJavaBytes(env, output.bytes.data(), output.bytes.size());
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_persistor_NativeImageEncoder_nativeEncodeJbig2(JNIEnv* env, jobject /* receiver */, jobject bitmap, jint threshold) {
    if (threshold < 1 || threshold > 254) {
        persistor_native::Throw(env, "java/lang/IllegalArgumentException", "Binarization threshold must be 1..254");
        return nullptr;
    }
    std::vector<uint8_t> rgb;
    uint32_t width = 0, height = 0;
    if (!persistor_native::ReadRgb(env, bitmap, &rgb, &width, &height)) return nullptr;
    PIX* pix = nullptr;
    if (!makeBinaryPix(rgb, width, height, threshold, &pix)) {
        persistor_native::Throw(env, "java/lang/OutOfMemoryError", "Could not allocate 1-bit JBIG2 input");
        return nullptr;
    }
    int encoded_size = 0;
    uint8_t* encoded = jbig2_encode_generic(pix, true, 300, 300, false, &encoded_size);
    pixDestroy(&pix);
    if (encoded == nullptr || encoded_size <= 0) {
        std::free(encoded);
        persistor_native::Throw(env, "java/lang/IllegalStateException", "Lossless JBIG2 encoding failed");
        return nullptr;
    }
    jbyteArray result = persistor_native::ToJavaBytes(env, encoded, static_cast<size_t>(encoded_size));
    std::free(encoded);
    return result;
}
