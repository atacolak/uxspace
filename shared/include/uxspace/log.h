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

inline bool init(const wchar_t* filePath) {
    if (detail::g_file != INVALID_HANDLE_VALUE) return true;
    detail::g_file = CreateFileW(filePath, GENERIC_WRITE,
        FILE_SHARE_READ | FILE_SHARE_WRITE,
        nullptr, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (detail::g_file == INVALID_HANDLE_VALUE) return false;
    // BOM so notepad opens it as UTF-8.
    DWORD wrote = 0;
    constexpr unsigned char kBom[] = { 0xEF, 0xBB, 0xBF };
    WriteFile(detail::g_file, kBom, sizeof(kBom), &wrote, nullptr);
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
