// JNI bridge from Novelfa to PDFium's FPDFText API (rendering itself
// goes through PdfiumCore from the pdfium-android AAR; that library
// exposes no text API, so this bridge fills exactly that gap).
//
// Links against libmodpdfium.so from the SAME AAR, so the FPDF
// library instance and its InitLibrary state are shared with
// PdfiumCore. No PDFium headers are shipped in the AAR, so only the
// FPDF functions used here are declared below; their signatures are
// the stable public FPDF API (fpdfview.h / fpdf_text.h).
//
// Prebuilt like the AVIF engine: this file is the source of truth,
// the .so files in src/main/jniLibs/<abi>/ are built from it with
// NDK 28.2.13676358 (android-29). If the .so files are absent, the
// Kotlin side degrades gracefully (PdfiumText.available == false).
//
// Coordinate note (matters for the text layer): FPDFText rects and
// positions are in PDF page points with y measured UP from the
// bottom-left of the page. Rendered bitmaps have y DOWN from the
// top-left, so the Kotlin mapping flips y against the page height.
// Verified on device, not assumed: see PdfiumText.mapRectToBitmap.

#include <jni.h>
#include <mutex>
#include <string>
#include <vector>

// -- Minimal FPDF declarations (public API, stable) --------------------
typedef void* FPDF_DOCUMENT;
typedef void* FPDF_PAGE;
typedef void* FPDF_TEXTPAGE;
typedef void* FPDF_SCHHANDLE;

extern "C" {
void FPDF_InitLibrary();
FPDF_DOCUMENT FPDF_LoadDocument(const char* file_path,
                                const char* password);
void FPDF_CloseDocument(FPDF_DOCUMENT document);
int FPDF_GetPageCount(FPDF_DOCUMENT document);
FPDF_PAGE FPDF_LoadPage(FPDF_DOCUMENT document, int page_index);
void FPDF_ClosePage(FPDF_PAGE page);
double FPDF_GetPageWidth(FPDF_PAGE page);
double FPDF_GetPageHeight(FPDF_PAGE page);
int FPDF_GetLastError();
FPDF_TEXTPAGE FPDFText_LoadPage(FPDF_PAGE page);
void FPDFText_ClosePage(FPDF_TEXTPAGE text_page);
int FPDFText_CountChars(FPDF_TEXTPAGE text_page);
int FPDFText_GetText(FPDF_TEXTPAGE text_page, int start, int count,
                     unsigned short* result);
int FPDFText_CountRects(FPDF_TEXTPAGE text_page, int start, int count);
int FPDFText_GetRect(FPDF_TEXTPAGE text_page, int rect_index, double* left,
                     double* top, double* right, double* bottom);
int FPDFText_GetCharIndexAtPos(FPDF_TEXTPAGE text_page, double x, double y,
                               double x_tolerance, double y_tolerance);
FPDF_SCHHANDLE FPDFText_FindStart(FPDF_TEXTPAGE text_page,
                                  const unsigned short* find,
                                  unsigned long flags, int start_index);
int FPDFText_FindNext(FPDF_SCHHANDLE handle);
int FPDFText_GetSchResultIndex(FPDF_SCHHANDLE handle, int* index,
                               int* count);
void FPDFText_FindClose(FPDF_SCHHANDLE handle);
}  // extern "C"

namespace {

std::once_flag g_init_once;

void ensureInit() {
    std::call_once(g_init_once, [] { FPDF_InitLibrary(); });
}

// Loads page + text page; returns false (with both closed as needed)
// when anything fails. Callers must close both on success.
bool loadTextPage(FPDF_DOCUMENT doc, int page_index, FPDF_PAGE* out_page,
                  FPDF_TEXTPAGE* out_text) {
    *out_page = nullptr;
    *out_text = nullptr;
    if (doc == nullptr || page_index < 0) return false;
    FPDF_PAGE page = FPDF_LoadPage(doc, page_index);
    if (page == nullptr) return false;
    FPDF_TEXTPAGE text = FPDFText_LoadPage(page);
    if (text == nullptr) {
        FPDF_ClosePage(page);
        return false;
    }
    *out_page = page;
    *out_text = text;
    return true;
}

void closeTextPage(FPDF_PAGE page, FPDF_TEXTPAGE text) {
    if (text != nullptr) FPDFText_ClosePage(text);
    if (page != nullptr) FPDF_ClosePage(page);
}

}  // namespace

extern "C" {

// Error codes from FPDF_GetLastError: 0 ok, 1 unknown, 2 file,
// 3 format, 4 password, 5 security, 6 page.
JNIEXPORT jint JNICALL
Java_com_novelfa_reader_reader_PdfiumText_nGetLastError(JNIEnv*, jclass) {
    ensureInit();
    return static_cast<jint>(FPDF_GetLastError());
}

JNIEXPORT jlong JNICALL
Java_com_novelfa_reader_reader_PdfiumText_nOpenDocument(JNIEnv* env, jclass,
                                                       jstring path,
                                                       jstring password) {
    ensureInit();
    if (path == nullptr) return 0;
    const char* c_path = env->GetStringUTFChars(path, nullptr);
    if (c_path == nullptr) return 0;
    const char* c_password = nullptr;
    if (password != nullptr) {
        c_password = env->GetStringUTFChars(password, nullptr);
    }
    FPDF_DOCUMENT doc = FPDF_LoadDocument(c_path, c_password);
    if (c_password != nullptr) env->ReleaseStringUTFChars(password, c_password);
    env->ReleaseStringUTFChars(path, c_path);
    return reinterpret_cast<jlong>(doc);
}

JNIEXPORT void JNICALL
Java_com_novelfa_reader_reader_PdfiumText_nCloseDocument(JNIEnv*, jclass,
                                                        jlong doc_handle) {
    auto* doc = reinterpret_cast<FPDF_DOCUMENT>(doc_handle);
    if (doc != nullptr) FPDF_CloseDocument(doc);
}

JNIEXPORT jint JNICALL
Java_com_novelfa_reader_reader_PdfiumText_nGetPageCount(JNIEnv*, jclass,
                                                       jlong doc_handle) {
    auto* doc = reinterpret_cast<FPDF_DOCUMENT>(doc_handle);
    if (doc == nullptr) return 0;
    return static_cast<jint>(FPDF_GetPageCount(doc));
}

JNIEXPORT jfloatArray JNICALL
Java_com_novelfa_reader_reader_PdfiumText_nGetPageSize(JNIEnv* env, jclass,
                                                      jlong doc_handle,
                                                      jint page_index) {
    auto* doc = reinterpret_cast<FPDF_DOCUMENT>(doc_handle);
    jfloatArray empty = env->NewFloatArray(0);
    if (doc == nullptr || page_index < 0) return empty;
    FPDF_PAGE page = FPDF_LoadPage(doc, static_cast<int>(page_index));
    if (page == nullptr) return empty;
    double w = FPDF_GetPageWidth(page);
    double h = FPDF_GetPageHeight(page);
    FPDF_ClosePage(page);
    if (w <= 0 || h <= 0) return empty;
    jfloatArray out = env->NewFloatArray(2);
    if (out == nullptr) return empty;
    const float wh[2] = {static_cast<float>(w), static_cast<float>(h)};
    env->SetFloatArrayRegion(out, 0, 2, wh);
    return out;
}

JNIEXPORT jstring JNICALL
Java_com_novelfa_reader_reader_PdfiumText_nGetPageText(JNIEnv* env, jclass,
                                                      jlong doc_handle,
                                                      jint page_index) {
    auto* doc = reinterpret_cast<FPDF_DOCUMENT>(doc_handle);
    if (doc == nullptr) return nullptr;
    FPDF_PAGE page = nullptr;
    FPDF_TEXTPAGE text = nullptr;
    if (!loadTextPage(doc, static_cast<int>(page_index), &page, &text)) {
        return nullptr;
    }
    jstring result = nullptr;
    const int count = FPDFText_CountChars(text);
    if (count > 0) {
        // +1 for the terminator PDFium appends when there is room.
        std::vector<unsigned short> buf(static_cast<size_t>(count) + 1, 0);
        const int written =
            FPDFText_GetText(text, 0, count + 1, buf.data());
        int len = written;
        if (len > count) len = count;
        if (len > 0 && buf[static_cast<size_t>(len) - 1] == 0) len--;
        if (len > 0) {
            result = env->NewString(
                reinterpret_cast<const jchar*>(buf.data()), len);
        }
    }
    closeTextPage(page, text);
    return result;
}

JNIEXPORT jfloatArray JNICALL
Java_com_novelfa_reader_reader_PdfiumText_nGetRangeRects(JNIEnv* env, jclass,
                                                        jlong doc_handle,
                                                        jint page_index,
                                                        jint start,
                                                        jint count) {
    auto* doc = reinterpret_cast<FPDF_DOCUMENT>(doc_handle);
    jfloatArray empty = env->NewFloatArray(0);
    if (doc == nullptr) return empty;
    FPDF_PAGE page = nullptr;
    FPDF_TEXTPAGE text = nullptr;
    if (!loadTextPage(doc, static_cast<int>(page_index), &page, &text)) {
        return empty;
    }
    // Cap: a pathological range must not build a giant array.
    const int rect_count = FPDFText_CountRects(
        text, static_cast<int>(start), static_cast<int>(count));
    std::vector<float> rects;
    const int capped = rect_count > 10000 ? 10000 : rect_count;
    for (int i = 0; i < capped; i++) {
        double left = 0, top = 0, right = 0, bottom = 0;
        if (FPDFText_GetRect(text, i, &left, &top, &right, &bottom) == 0) {
            continue;
        }
        rects.push_back(static_cast<float>(left));
        rects.push_back(static_cast<float>(top));
        rects.push_back(static_cast<float>(right));
        rects.push_back(static_cast<float>(bottom));
    }
    closeTextPage(page, text);
    jfloatArray out = env->NewFloatArray(static_cast<jsize>(rects.size()));
    if (out == nullptr) return empty;
    if (!rects.empty()) {
        env->SetFloatArrayRegion(out, 0, static_cast<jsize>(rects.size()),
                                 rects.data());
    }
    return out;
}

JNIEXPORT jintArray JNICALL
Java_com_novelfa_reader_reader_PdfiumText_nFindAll(JNIEnv* env, jclass,
                                                  jlong doc_handle,
                                                  jint page_index,
                                                  jstring query,
                                                  jboolean match_case,
                                                  jboolean whole_word) {
    auto* doc = reinterpret_cast<FPDF_DOCUMENT>(doc_handle);
    jintArray empty = env->NewIntArray(0);
    if (doc == nullptr || query == nullptr) return empty;
    FPDF_PAGE page = nullptr;
    FPDF_TEXTPAGE text = nullptr;
    if (!loadTextPage(doc, static_cast<int>(page_index), &page, &text)) {
        return empty;
    }
    std::vector<jint> hits;
    const jsize query_len = env->GetStringLength(query);
    const jchar* query_chars = env->GetStringChars(query, nullptr);
    if (query_chars != nullptr && query_len > 0) {
        unsigned long flags = 0;
        if (match_case == JNI_TRUE) flags |= 0x00000001;   // FPDF_MATCHCASE
        if (whole_word == JNI_TRUE) flags |= 0x00000002;  // FPDF_MATCHWHOLEWORD
        FPDF_SCHHANDLE search = FPDFText_FindStart(
            text, reinterpret_cast<const unsigned short*>(query_chars),
            flags, 0);
        if (search != nullptr) {
            while (hits.size() < 20000) {
                int index = -1;
                int match_count = 0;
                if (FPDFText_GetSchResultIndex(search, &index,
                                               &match_count) == 0) {
                    break;
                }
                hits.push_back(index);
                hits.push_back(match_count);
                if (FPDFText_FindNext(search) == 0) break;
            }
            FPDFText_FindClose(search);
        }
        env->ReleaseStringChars(query, query_chars);
    }
    closeTextPage(page, text);
    jintArray out = env->NewIntArray(static_cast<jsize>(hits.size()));
    if (out == nullptr) return empty;
    if (!hits.empty()) {
        env->SetIntArrayRegion(out, 0, static_cast<jsize>(hits.size()),
                               hits.data());
    }
    return out;
}

JNIEXPORT jint JNICALL
Java_com_novelfa_reader_reader_PdfiumText_nGetCharIndexAtPos(
    JNIEnv* env, jclass, jlong doc_handle, jint page_index, jdouble x,
    jdouble y) {
    auto* doc = reinterpret_cast<FPDF_DOCUMENT>(doc_handle);
    if (doc == nullptr) return -1;
    FPDF_PAGE page = nullptr;
    FPDF_TEXTPAGE text = nullptr;
    if (!loadTextPage(doc, static_cast<int>(page_index), &page, &text)) {
        return -1;
    }
    // Tolerances in page points; tuned on device, not assumed here.
    const int index = FPDFText_GetCharIndexAtPos(
        text, static_cast<double>(x), static_cast<double>(y), 2.0, 2.0);
    closeTextPage(page, text);
    return static_cast<jint>(index);
}

}  // extern "C"
