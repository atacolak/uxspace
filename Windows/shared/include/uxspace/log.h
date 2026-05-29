// Header-only, Win32-only diagnostic log. Writes timestamped lines to a
// file path supplied at init() time; flushed after every record so a
// crash leaves the log intact. Multi-thread safe via a private mutex.
//
// init() truncates the file (CREATE_ALWAYS) so every app launch starts
// a fresh log — convenient for "reproduce + send me the log" debugging
// without cleanup steps for the user.

#pragma once

#include <windows.h>

#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <mutex>

namespace uxspace::log {

namespace detail {

inline HANDLE     g_file = INVALID_HANDLE_VALUE;
inline std::mutex g_mutex;

inline void writeLine(const char* level, const char* msg) {
    if (g_file == INVALID_HANDLE_VALUE) return;
    SYSTEMTIME st;
    GetLocalTime(&st);
    char buf[1280];
    const int len = std::snprintf(buf, sizeof(buf),
        "[%04u-%02u-%02u %02u:%02u:%02u.%03u %-5s] %s\r\n",
        st.wYear, st.wMonth, st.wDay,
        st.wHour, st.wMinute, st.wSecond, st.wMilliseconds,
        level, msg);
    if (len <= 0) return;
    std::lock_guard<std::mutex> lk(g_mutex);
    DWORD wrote = 0;
    WriteFile(g_file, buf, static_cast<DWORD>(len), &wrote, nullptr);
    FlushFileBuffers(g_file);
}

} // namespace detail

// `append=false` (default) truncates the file each init() — what :app
// wants so each launch starts a clean log. `append=true` opens for
// append, preserving previous content — what :driver wants because
// WUDFHost recycles the driver context on monitor changes and a
// CREATE_ALWAYS path would erase every prior session's diagnostics
// between user actions (verified empirically: v2300 driver log only
// ever contained the most recent PipeServer::Start line).
inline bool init(const wchar_t* filePath, bool append = false) {
    if (detail::g_file != INVALID_HANDLE_VALUE) return true;
    const DWORD disposition = append ? OPEN_ALWAYS : CREATE_ALWAYS;
    detail::g_file = CreateFileW(filePath, FILE_APPEND_DATA | GENERIC_WRITE,
        FILE_SHARE_READ | FILE_SHARE_WRITE,
        nullptr, disposition, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (detail::g_file == INVALID_HANDLE_VALUE) return false;
    DWORD wrote = 0;
    if (append) {
        // Seek to end so writes append. New file (size==0) still gets
        // the BOM so notepad opens it as UTF-8.
        const DWORD size = GetFileSize(detail::g_file, nullptr);
        SetFilePointer(detail::g_file, 0, nullptr, FILE_END);
        if (size == 0) {
            constexpr unsigned char kBom[] = { 0xEF, 0xBB, 0xBF };
            WriteFile(detail::g_file, kBom, sizeof(kBom), &wrote, nullptr);
        }
    } else {
        // BOM so notepad opens it as UTF-8.
        constexpr unsigned char kBom[] = { 0xEF, 0xBB, 0xBF };
        WriteFile(detail::g_file, kBom, sizeof(kBom), &wrote, nullptr);
    }
    return true;
}

inline void shutdown() {
    std::lock_guard<std::mutex> lk(detail::g_mutex);
    if (detail::g_file != INVALID_HANDLE_VALUE) {
        CloseHandle(detail::g_file);
        detail::g_file = INVALID_HANDLE_VALUE;
    }
}

inline void info(const char* fmt, ...) {
    char buf[1024];
    va_list args;
    va_start(args, fmt);
    std::vsnprintf(buf, sizeof(buf), fmt, args);
    va_end(args);
    detail::writeLine("INFO", buf);
}

inline void warn(const char* fmt, ...) {
    char buf[1024];
    va_list args;
    va_start(args, fmt);
    std::vsnprintf(buf, sizeof(buf), fmt, args);
    va_end(args);
    detail::writeLine("WARN", buf);
}

inline void error(const char* fmt, ...) {
    char buf[1024];
    va_list args;
    va_start(args, fmt);
    std::vsnprintf(buf, sizeof(buf), fmt, args);
    va_end(args);
    detail::writeLine("ERROR", buf);
}

} // namespace uxspace::log
