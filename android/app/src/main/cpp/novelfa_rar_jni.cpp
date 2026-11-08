// JNI bridge from Novelfa to the RARLAB unrar library (decode only):
// lists and extracts entries from RAR4/RAR5 archives (.cbr comics).
//
// Unrar source: vendored in unrar/ from the official RARLAB source
// tarball unrarsrc-7.3.2.tar.gz
// (https://www.rarlab.com/rar/unrarsrc-7.3.2.tar.gz, SHA-256
// f61a1160d6e2ebbda2ad353c8e82f62ab4312f97fde23c6c2c982d25e0014f99).
// The vendored set is the unrar makefile's RARDLL build (OBJECTS +
// LIB_OBJ with -DRARDLL), minus Windows-only and CLI-only files.
// See unrar/license.txt.
//
// LICENSE NOTE: the unrar license (unrar/license.txt) permits free
// use of this code for unarchiving, but FORBIDS using it to create
// a RAR archiver or to re-create RAR compression. This bridge only
// READS archives (list / extract); it never writes RAR data.
//
// Prebuilt like the AVIF engine: this file is the source of truth,
// the .so files in src/main/jniLibs/<abi>/ are built from it with
// NDK 28.2.13676358 (android-29, Release). If the .so files are
// absent, the Kotlin side degrades gracefully (RarNative.available
// == false) and .cbr books stay unsupported. Every other format
// is unaffected.
//
// API shape (deliberately stateless, unlike ComicScreen's
// handle-based NdkStaticUtil): every call opens the archive
// itself, does its work, and closes it. No handles cross JNI.
//
// Solid archives: the unrar DLL API has no seek-to-header, so
// every extraction scans from the first header and RAR_SKIPs the
// entries before the target. For a solid archive that skip still
// decompresses-and-discards the earlier files (unrar keeps the
// solid dictionary state), which is exactly the sequential
// extraction a solid archive requires; for a non-solid archive
// the skip is a cheap seek. The `solid` parameter is therefore
// accepted for API compatibility and does not change the code
// path — the scan is sequential either way. The `headerOffset`
// parameter is likewise accepted but unused: the DLL API exposes
// no header offset, so entries are identified by name (first
// match wins); callers must pass 0.
//
// Passwords: session-only, never stored. The nullable password
// is converted UTF-16 -> UTF-8 here and passed to
// RARSetPassword, which on Android decodes it with mbsrtowcs in
// Bionic's UTF-8 C locale. ASCII passwords are exact; non-ASCII
// passwords rely on that locale behaviour (true on Android).
//
// Filenames: unrar reports them as wchar_t (UTF-32 on Android).
// They are converted to UTF-16 here for NewString, so non-ASCII
// names (Persian, CJK, emoji) survive. Long names (>1023 wchar)
// come through RARHeaderDataEx.FileNameEx.

#include <jni.h>

#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

// raros.hpp first: it defines _UNIX / _ANDROID, which dll.hpp
// needs for CALLBACK / PASCAL / HANDLE.
#include "unrar/raros.hpp"
#include "unrar/dll.hpp"
#include "unrar/version.hpp"

// RAR signatures: "Rar!\x1a\x07\x00" (RAR4), "Rar!\x1a\x07\x01\x00" (RAR5).
// nGetRarVersion reads the file directly instead of opening it with
// unrar: cheaper, and it works even on archives unrar would reject.

namespace {

// -- UTF conversions -------------------------------------------------------
// Java strings are UTF-16; unrar wants UTF-8 (paths, passwords) and
// gives UTF-32 wchar_t (entry names). GetStringUTFChars is NOT used:
// it returns modified UTF-8, which mangles supplementary characters.

void appendUtf8(std::string& out, uint32_t cp) {
    if (cp < 0x80) {
        out.push_back(static_cast<char>(cp));
    } else if (cp < 0x800) {
        out.push_back(static_cast<char>(0xC0 | (cp >> 6)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    } else if (cp < 0x10000) {
        out.push_back(static_cast<char>(0xE0 | (cp >> 12)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    } else {
        out.push_back(static_cast<char>(0xF0 | (cp >> 18)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    }
}

// jstring -> UTF-8 std::string. Lone surrogates become U+FFFD.
std::string jstringToUtf8(JNIEnv* env, jstring s) {
    if (s == nullptr) return std::string();
    const jsize len = env->GetStringLength(s);
    if (len <= 0) return std::string();
    const jchar* chars = env->GetStringChars(s, nullptr);
    if (chars == nullptr) return std::string();
    std::string out;
    out.reserve(static_cast<size_t>(len));
    for (jsize i = 0; i < len; i++) {
        uint32_t cp = chars[i];
        if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < len) {
            const uint32_t lo = chars[i + 1];
            if (lo >= 0xDC00 && lo <= 0xDFFF) {
                cp = 0x10000 + ((cp - 0xD800) << 10) + (lo - 0xDC00);
                i++;
            } else {
                cp = 0xFFFD;
            }
        } else if (cp >= 0xD800 && cp <= 0xDFFF) {
            cp = 0xFFFD;
        }
        appendUtf8(out, cp);
    }
    env->ReleaseStringChars(s, chars);
    return out;
}

// UTF-8 std::string -> UTF-32 std::wstring (wchar_t is 32-bit on
// Android). Invalid bytes become U+FFFD; never fails.
std::wstring utf8ToWide(const std::string& in) {
    std::wstring out;
    out.reserve(in.size());
    size_t i = 0;
    while (i < in.size()) {
        const unsigned char c = static_cast<unsigned char>(in[i]);
        uint32_t cp = 0xFFFD;
        size_t extra = 0;
        if (c < 0x80) {
            cp = c;
        } else if ((c >> 5) == 6 && i + 1 < in.size() &&
                   (static_cast<unsigned char>(in[i + 1]) & 0xC0) == 0x80) {
            cp = ((c & 0x1F) << 6) |
                 (static_cast<unsigned char>(in[i + 1]) & 0x3F);
            extra = 1;
        } else if ((c >> 4) == 14 && i + 2 < in.size() &&
                   (static_cast<unsigned char>(in[i + 1]) & 0xC0) == 0x80 &&
                   (static_cast<unsigned char>(in[i + 2]) & 0xC0) == 0x80) {
            cp = ((c & 0x0F) << 12) |
                 ((static_cast<unsigned char>(in[i + 1]) & 0x3F) << 6) |
                 (static_cast<unsigned char>(in[i + 2]) & 0x3F);
            extra = 2;
        } else if ((c >> 3) == 30 && i + 3 < in.size() &&
                   (static_cast<unsigned char>(in[i + 1]) & 0xC0) == 0x80 &&
                   (static_cast<unsigned char>(in[i + 2]) & 0xC0) == 0x80 &&
                   (static_cast<unsigned char>(in[i + 3]) & 0xC0) == 0x80) {
            cp = ((c & 0x07) << 18) |
                 ((static_cast<unsigned char>(in[i + 1]) & 0x3F) << 12) |
                 ((static_cast<unsigned char>(in[i + 2]) & 0x3F) << 6) |
                 (static_cast<unsigned char>(in[i + 3]) & 0x3F);
            extra = 3;
        }
        out.push_back(static_cast<wchar_t>(cp));
        i += 1 + extra;
    }
    return out;
}

// wchar_t (UTF-32) -> Java UTF-16 string. Returns nullptr on OOM.
jstring wideToJString(JNIEnv* env, const wchar_t* s) {
    if (s == nullptr) return nullptr;
    std::vector<jchar> utf16;
    for (const wchar_t* p = s; *p != 0; p++) {
        const uint32_t cp = static_cast<uint32_t>(*p);
        if (cp < 0x10000) {
            utf16.push_back(static_cast<jchar>(cp));
        } else if (cp <= 0x10FFFF) {
            const uint32_t v = cp - 0x10000;
            utf16.push_back(static_cast<jchar>(0xD800 + (v >> 10)));
            utf16.push_back(static_cast<jchar>(0xDC00 + (v & 0x3FF)));
        } else {
            utf16.push_back(static_cast<jchar>(0xFFFD));
        }
    }
    return env->NewString(utf16.data(), static_cast<jsize>(utf16.size()));
}

// -- Archive handling ------------------------------------------------------

// The entry name unrar reports: FileNameEx when the caller supplied
// that buffer and the name is long, otherwise FileNameW.
const wchar_t* headerName(const RARHeaderDataEx& h) {
    if (h.FileNameEx != nullptr && h.FileNameEx[0] != 0) return h.FileNameEx;
    return h.FileNameW;
}

uint64_t headerUnpSize(const RARHeaderDataEx& h) {
    return (static_cast<uint64_t>(h.UnpSizeHigh) << 32) | h.UnpSize;
}

// Opens the archive; returns nullptr on failure (err receives the
// ERAR_* OpenResult). When passwordUtf8 is non-empty it is applied
// with RARSetPassword before any header is read, so RAR5 archives
// with encrypted headers can be listed/read too.
HANDLE openArchive(const std::string& pathUtf8, unsigned int mode,
                   const std::string& passwordUtf8, int* err) {
    RAROpenArchiveDataEx data;
    std::memset(&data, 0, sizeof(data));
    data.ArcName = const_cast<char*>(pathUtf8.c_str());
    data.OpenMode = mode;
    data.OpenResult = 0;
    data.Callback = nullptr;
    data.UserData = 0;
    data.OpFlags = 0;
    HANDLE h = RAROpenArchiveEx(&data);
    if (h == nullptr) {
        if (err != nullptr) *err = static_cast<int>(data.OpenResult);
        return nullptr;
    }
    if (!passwordUtf8.empty()) {
        // RARSetPassword takes char*; the bytes are UTF-8 (see the
        // header comment). The string lives until CloseArchive.
        RARSetPassword(h, const_cast<char*>(passwordUtf8.c_str()));
    }
    if (err != nullptr) *err = ERAR_SUCCESS;
    return h;
}

void closeArchive(HANDLE h) {
    if (h != nullptr) RARCloseArchive(h);
}

// One header read with a long-name buffer attached. The buffer must
// outlive the call; re-attach after every memset of the struct.
struct HeaderRead {
    RARHeaderDataEx data;
    std::vector<wchar_t> longName;  // 4096 wchar: names past 1023.
    HeaderRead() : longName(4096, 0) {
        reset();
    }
    void reset() {
        std::memset(&data, 0, sizeof(data));
        data.FileNameEx = longName.data();
        data.FileNameExSize = static_cast<unsigned int>(longName.size());
    }
};

// Scans from the first header until the entry whose wide name equals
// `target` is the current header, skipping everything before it.
// Returns ERAR_SUCCESS with that header current, ERAR_END_ARCHIVE
// when the archive ends first, or the failing ERAR_* code.
// Directories match by name too; the caller decides what to do.
int scanToEntry(HANDLE h, const std::wstring& target, HeaderRead* current) {
    for (;;) {
        current->reset();
        const int rc = RARReadHeaderEx(h, &current->data);
        if (rc != ERAR_SUCCESS) return rc;
        if (target == headerName(current->data)) return ERAR_SUCCESS;
        const int skip = RARProcessFile(h, RAR_SKIP, nullptr, nullptr);
        if (skip != ERAR_SUCCESS) return skip;
    }
}

// -- In-memory extraction sink ---------------------------------------------
// RARSetProcessDataProc gives the callback no user-data pointer, so
// the sink is thread-local: one JNI call per thread at a time.
thread_local std::vector<unsigned char>* g_sink = nullptr;

int CALLBACK processDataSink(unsigned char* addr, int size) {
    if (g_sink == nullptr || addr == nullptr || size < 0) return 0;
    try {
        g_sink->insert(g_sink->end(), addr, addr + size);
    } catch (...) {
        return 0;  // 0 aborts the extraction (RARX_USERBREAK).
    }
    return 1;
}

// A comic page is an image; 512 MiB is far above any real page and
// bounds what a hostile archive can make us allocate in RAM.
constexpr size_t kMaxEntryBytes = 512u * 1024u * 1024u;

}  // namespace

extern "C" {

JNIEXPORT jobjectArray JNICALL
Java_com_novelfa_reader_parser_RarNative_nListEntries(JNIEnv* env, jclass,
                                                      jstring archivePath) {
    if (archivePath == nullptr) return nullptr;
    const std::string path = jstringToUtf8(env, archivePath);
    if (path.empty()) return nullptr;

    int err = 0;
    HANDLE h = openArchive(path, RAR_OM_LIST, std::string(), &err);
    if (h == nullptr) return nullptr;

    jclass entryClass =
        env->FindClass("com/novelfa/reader/parser/RarEntry");
    if (entryClass == nullptr) {
        closeArchive(h);
        return nullptr;
    }
    jmethodID ctor = env->GetMethodID(entryClass, "<init>",
                                      "(Ljava/lang/String;JJZZ)V");
    if (ctor == nullptr) {
        closeArchive(h);
        return nullptr;
    }

    struct Entry {
        std::wstring name;
        uint64_t size;
        bool solid;
        bool directory;
    };
    std::vector<Entry> entries;
    HeaderRead current;
    jobjectArray result = nullptr;
    bool failed = false;
    for (;;) {
        current.reset();
        const int rc = RARReadHeaderEx(h, &current.data);
        if (rc == ERAR_END_ARCHIVE) break;
        if (rc != ERAR_SUCCESS) {
            // Encrypted headers without a password surface here as
            // ERAR_BAD_PASSWORD; a broken archive as ERAR_BAD_DATA.
            failed = true;
            break;
        }
        entries.push_back(Entry{headerName(current.data),
                                headerUnpSize(current.data),
                                (current.data.Flags & RHDF_SOLID) != 0,
                                (current.data.Flags & RHDF_DIRECTORY) != 0});
        const int skip = RARProcessFile(h, RAR_SKIP, nullptr, nullptr);
        if (skip != ERAR_SUCCESS) {
            failed = true;
            break;
        }
    }
    if (!failed) {
        result = env->NewObjectArray(static_cast<jsize>(entries.size()),
                                     entryClass, nullptr);
        if (result != nullptr) {
            for (size_t i = 0; i < entries.size(); i++) {
                jstring name = wideToJString(env, entries[i].name.c_str());
                if (name == nullptr) {
                    failed = true;
                    break;
                }
                jobject entry = env->NewObject(
                    entryClass, ctor, name,
                    static_cast<jlong>(entries[i].size),
                    static_cast<jlong>(0),  // headerOffset: not exposed
                    // by the unrar DLL API (see the header comment).
                    static_cast<jboolean>(entries[i].solid ? JNI_TRUE
                                                           : JNI_FALSE),
                    static_cast<jboolean>(entries[i].directory ? JNI_TRUE
                                                               : JNI_FALSE));
                env->DeleteLocalRef(name);
                if (entry == nullptr) {
                    failed = true;
                    break;
                }
                env->SetObjectArrayElement(result, static_cast<jsize>(i),
                                           entry);
                env->DeleteLocalRef(entry);
            }
        } else {
            failed = true;
        }
    }
    closeArchive(h);
    if (failed) return nullptr;
    return result;
}

JNIEXPORT jbyteArray JNICALL
Java_com_novelfa_reader_parser_RarNative_nExtractEntryBytes(
    JNIEnv* env, jclass, jstring archivePath, jstring password, jstring name,
    jlong /* headerOffset: unused, see the header comment */,
    jboolean /* solid: sequential scan either way */) {
    if (archivePath == nullptr || name == nullptr) return nullptr;
    const std::string path = jstringToUtf8(env, archivePath);
    const std::string passwordUtf8 =
        password != nullptr ? jstringToUtf8(env, password) : std::string();
    const std::wstring target = utf8ToWide(jstringToUtf8(env, name));
    if (path.empty() || target.empty()) return nullptr;

    int err = 0;
    HANDLE h = openArchive(path, RAR_OM_EXTRACT, passwordUtf8, &err);
    if (h == nullptr) return nullptr;

    HeaderRead current;
    const int found = scanToEntry(h, target, &current);
    jbyteArray result = nullptr;
    if (found == ERAR_SUCCESS) {
        if ((current.data.Flags & RHDF_DIRECTORY) != 0) {
            result = env->NewByteArray(0);  // A directory extracts empty.
        } else if (headerUnpSize(current.data) > kMaxEntryBytes) {
            result = nullptr;  // Absurd size: refuse rather than OOM.
        } else {
            std::vector<unsigned char> sink;
            sink.reserve(static_cast<size_t>(
                headerUnpSize(current.data) < (1u << 24)
                    ? headerUnpSize(current.data)
                    : (1u << 24)));
            g_sink = &sink;
            RARSetProcessDataProc(h, processDataSink);
            const int rc = RARProcessFile(h, RAR_TEST, nullptr, nullptr);
            g_sink = nullptr;
            RARSetProcessDataProc(h, nullptr);
            if (rc == ERAR_SUCCESS && sink.size() <= kMaxEntryBytes) {
                result = env->NewByteArray(static_cast<jsize>(sink.size()));
                if (result != nullptr && !sink.empty()) {
                    env->SetByteArrayRegion(
                        result, 0, static_cast<jsize>(sink.size()),
                        reinterpret_cast<const jbyte*>(sink.data()));
                }
            }
            // rc != 0 (bad password, CRC failure, truncated data, ...)
            // intentionally returns null: the caller reports it.
        }
    }
    closeArchive(h);
    return result;
}

JNIEXPORT jint JNICALL
Java_com_novelfa_reader_parser_RarNative_nExtractEntryToFile(
    JNIEnv* env, jclass, jstring archivePath, jstring password, jstring name,
    jlong /* headerOffset: unused, see the header comment */,
    jboolean /* solid: sequential scan either way */, jstring destPath) {
    if (archivePath == nullptr || name == nullptr || destPath == nullptr) {
        return ERAR_BAD_ARCHIVE;
    }
    const std::string path = jstringToUtf8(env, archivePath);
    const std::string passwordUtf8 =
        password != nullptr ? jstringToUtf8(env, password) : std::string();
    const std::wstring target = utf8ToWide(jstringToUtf8(env, name));
    const std::string dest = jstringToUtf8(env, destPath);
    if (path.empty() || target.empty() || dest.empty()) {
        return ERAR_BAD_ARCHIVE;
    }

    int err = 0;
    HANDLE h = openArchive(path, RAR_OM_EXTRACT, passwordUtf8, &err);
    if (h == nullptr) return err != 0 ? err : ERAR_BAD_ARCHIVE;

    HeaderRead current;
    const int found = scanToEntry(h, target, &current);
    int rc = ERAR_BAD_ARCHIVE;  // Entry name not present in the archive.
    if (found == ERAR_SUCCESS) {
        if ((current.data.Flags & RHDF_DIRECTORY) != 0) {
            rc = ERAR_SUCCESS;  // Nothing to write for a directory.
        } else {
            // RARProcessFileW takes a directory and a file name, not
            // a full path: split destPath at its last '/'.
            std::wstring destDir;
            std::wstring destName;
            const size_t slash = dest.rfind('/');
            if (slash == std::string::npos) {
                destDir = L".";
                destName = utf8ToWide(dest);
            } else {
                destDir = utf8ToWide(dest.substr(0, slash));
                destName = utf8ToWide(dest.substr(slash + 1));
                if (destName.empty()) {
                    closeArchive(h);
                    return ERAR_BAD_ARCHIVE;
                }
            }
            rc = RARProcessFileW(h, RAR_EXTRACT,
                                 const_cast<wchar_t*>(destDir.c_str()),
                                 const_cast<wchar_t*>(destName.c_str()));
        }
    } else if (found != ERAR_END_ARCHIVE) {
        rc = found;  // e.g. ERAR_BAD_PASSWORD, ERAR_BAD_DATA.
    }
    closeArchive(h);
    return rc;
}

JNIEXPORT jint JNICALL
Java_com_novelfa_reader_parser_RarNative_nGetRarVersion(JNIEnv* env, jclass,
                                                        jstring archivePath) {
    if (archivePath == nullptr) return 0;
    const std::string path = jstringToUtf8(env, archivePath);
    if (path.empty()) return 0;
    FILE* f = std::fopen(path.c_str(), "rb");
    if (f == nullptr) return 0;
    unsigned char sig[8] = {0};
    const size_t n = std::fread(sig, 1, sizeof(sig), f);
    std::fclose(f);
    if (n < 8) return 0;
    static const unsigned char kRar4[7] = {'R', 'a', 'r', '!', 0x1A, 0x07, 0x00};
    static const unsigned char kRar5[8] = {'R', 'a', 'r', '!', 0x1A, 0x07, 0x01, 0x00};
    if (std::memcmp(sig, kRar4, 7) == 0) return 4;
    if (std::memcmp(sig, kRar5, 8) == 0) return 5;
    return 0;
}

JNIEXPORT jstring JNICALL
Java_com_novelfa_reader_parser_RarNative_nGetVersion(JNIEnv* env, jclass) {
    // RARLAB's tarball version: 7.30 beta 2 ships as unrarsrc-7.3.2.
    // version.hpp carries RARVER_MAJOR 7, RARVER_MINOR 30 (i.e. 3.0)
    // and RARVER_BETA 2, so the public form is MAJOR.MINOR/10.BETA
    // while it is a beta, MAJOR.MINOR (two digits) once final.
    // (RARGetDllVersion() is NOT this: it returns the DLL API
    // version, currently 11.)
    char buf[32];
    if (RARVER_BETA > 0) {
        std::snprintf(buf, sizeof(buf), "%d.%d.%d", RARVER_MAJOR,
                      RARVER_MINOR / 10, RARVER_BETA);
    } else {
        std::snprintf(buf, sizeof(buf), "%d.%02d", RARVER_MAJOR,
                      RARVER_MINOR);
    }
    return env->NewStringUTF(buf);
}

}  // extern "C"
