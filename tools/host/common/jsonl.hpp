// Small helpers shared by the host engines: JSON string escaping and a monotonic millisecond clock.
// The host engines speak JSON lines: one request per stdin line, one JSON object per stdout line.
#pragma once

#include <chrono>
#include <cstdio>
#include <string>

namespace hostio {

// Escapes a UTF-8 string for a JSON string literal. Bytes >= 0x80 pass through unchanged (the
// output stays UTF-8); control characters become \uXXXX.
inline std::string json_string(const std::string &s) {
    std::string o = "\"";
    for (unsigned char c : s) {
        switch (c) {
            case '"': o += "\\\""; break;
            case '\\': o += "\\\\"; break;
            case '\n': o += "\\n"; break;
            case '\r': o += "\\r"; break;
            case '\t': o += "\\t"; break;
            case '\b': o += "\\b"; break;
            case '\f': o += "\\f"; break;
            default:
                if (c < 0x20) {
                    char buf[8];
                    std::snprintf(buf, sizeof buf, "\\u%04x", c);
                    o += buf;
                } else {
                    o += static_cast<char>(c);
                }
        }
    }
    return o + "\"";
}

// A float as a JSON number with fixed precision.
inline std::string json_number(double v, int decimals = 3) {
    char buf[64];
    std::snprintf(buf, sizeof buf, "%.*f", decimals, v);
    return buf;
}

inline double now_ms() {
    using namespace std::chrono;
    return duration<double, std::milli>(steady_clock::now().time_since_epoch()).count();
}

// Writes one line to stdout and flushes, so a caller reading line by line never waits on a buffer.
inline void emit(const std::string &json) {
    std::fputs(json.c_str(), stdout);
    std::fputc('\n', stdout);
    std::fflush(stdout);
}

// Strips a trailing carriage return (a request file saved with CRLF line endings).
inline void chomp(std::string &line) {
    if (!line.empty() && line.back() == '\r') line.pop_back();
}

}  // namespace hostio
