// Host mirror of the app's speech-to-text path, for the regression harness.
//
//   whisperhost [--threads N] MODEL      JSON lines: one WAV path per stdin line, one result per line
//   whisperhost --info                   one JSON line describing the build, then exit
//
// Mirrors, step by step:
//   core WavReader         16-bit PCM WAV -> 16 kHz mono float (channels mixed, linear resampling)
//   core AudioGate         < 0.3 s or RMS < 0.003 -> "" with confidence 0, whisper not run;
//                          at most 8 s of audio; bracketed non-speech tags stripped afterwards
//   native whisper_jni.cpp greedy, "en", no_context, no_timestamps, single_segment, suppress_nst,
//                          temperature_inc 0, max_tokens 48, 2 threads; confidence = mean p of the
//                          text tokens (ids below end-of-text), null when there are none
//
// Output per request:
//   {"text": "...", "confidence": 0.93 | 0 | null, "ms": 412.5, "raw": "..." | null,
//    "gated": false, "audio_s": 1.84}
//   text/confidence are what WhisperSpeechEngine would hand to the turn engine. raw is whisper's own
//   text before tag stripping (null when whisper did not run). ms covers gate + whisper + read-out,
//   not the file read. On a bad WAV or a whisper failure: text "" / confidence null plus "error".
#include <algorithm>
#include <cctype>
#include <clocale>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <cwctype>
#include <fstream>
#include <iostream>
#include <iterator>
#include <stdexcept>
#include <string>
#include <vector>

#include "jsonl.hpp"
#include "whisper.h"

#ifndef ENGINE_VERSION
#define ENGINE_VERSION "unknown"
#endif
#ifndef ENGINE_SHA256
#define ENGINE_SHA256 "unknown"
#endif

namespace {

// core AudioGate constants.
constexpr int SAMPLE_RATE = 16000;
constexpr double MIN_SECONDS = 0.3;
constexpr double MAX_SECONDS = 8.0;
constexpr double MIN_RMS = 0.003;

// native whisper_jni.cpp constants and the app's default thread count.
constexpr int MAX_TOKENS = 48;
constexpr int DEFAULT_THREADS = 2;

// ---- core WavReader -------------------------------------------------------------------------------

uint32_t u32(const std::vector<uint8_t> &b, size_t at) {
    return b[at] | (b[at + 1] << 8) | (b[at + 2] << 16) | (static_cast<uint32_t>(b[at + 3]) << 24);
}
int16_t i16(const std::vector<uint8_t> &b, size_t at) {
    return static_cast<int16_t>(b[at] | (b[at + 1] << 8));
}
std::string tag(const std::vector<uint8_t> &b, size_t at) {
    return std::string(reinterpret_cast<const char *>(b.data() + at), 4);
}

std::vector<float> resample(const std::vector<float> &in, int from, int to) {
    if (from == to || in.empty()) return in;
    std::vector<float> out(static_cast<size_t>(static_cast<int64_t>(in.size()) * to / from));
    const size_t last = in.size() - 1;
    for (size_t i = 0; i < out.size(); ++i) {
        const double x = static_cast<double>(i) * from / to;
        const size_t i0 = std::min(static_cast<size_t>(x), last);
        const size_t i1 = std::min(i0 + 1, last);
        const float f = static_cast<float>(x - static_cast<double>(i0));
        out[i] = in[i0] * (1 - f) + in[i1] * f;
    }
    return out;
}

// Throws std::runtime_error for anything that is not a sane 16-bit PCM WAV.
std::vector<float> read_wav(const std::string &path) {
    std::ifstream f(path, std::ios::binary);
    if (!f) throw std::runtime_error("cannot open file");
    std::vector<uint8_t> b((std::istreambuf_iterator<char>(f)), std::istreambuf_iterator<char>());
    if (b.size() < 12 || tag(b, 0) != "RIFF" || tag(b, 8) != "WAVE") throw std::runtime_error("not a WAV file");
    size_t pos = 12;
    int channels = 0, rate = 0, bits = 0, format = 0;
    while (pos + 8 <= b.size()) {
        const std::string id = tag(b, pos);
        const uint32_t size = u32(b, pos + 4);
        if (size > INT32_MAX || pos + 8 + static_cast<uint64_t>(size) > b.size()) {
            throw std::runtime_error("truncated chunk " + id);
        }
        const size_t body = pos + 8;
        if (id == "fmt ") {
            if (size < 16) throw std::runtime_error("fmt chunk too short");
            format = i16(b, body);
            channels = i16(b, body + 2);
            rate = static_cast<int32_t>(u32(b, body + 4));
            bits = i16(b, body + 14);
        } else if (id == "data") {
            if (format != 1 || bits != 16 || channels < 1 || channels > 8 || rate < 8000 || rate > 96000) {
                throw std::runtime_error("need 16-bit PCM, got format=" + std::to_string(format) + " bits=" +
                                         std::to_string(bits) + " channels=" + std::to_string(channels));
            }
            const size_t frames = size / (2 * channels);
            std::vector<float> mono(frames);
            for (size_t fr = 0; fr < frames; ++fr) {
                float sum = 0;
                for (int c = 0; c < channels; ++c) sum += i16(b, body + 2 * (fr * channels + c)) / 32768.0f;
                mono[fr] = sum / channels;
            }
            return resample(mono, rate, SAMPLE_RATE);
        }
        pos = body + size + (size & 1);
    }
    throw std::runtime_error("no data chunk");
}

// ---- core AudioGate --------------------------------------------------------------------------------

double rms(const std::vector<float> &pcm) {
    if (pcm.empty()) return 0.0;
    double sum = 0.0;
    for (float s : pcm) sum += static_cast<double>(s) * s;
    return std::sqrt(sum / pcm.size());
}

bool has_speech(const std::vector<float> &pcm) {
    return pcm.size() >= MIN_SECONDS * SAMPLE_RATE && rms(pcm) >= MIN_RMS;
}

// Java's default \s: [ \t\n\x0B\f\r].
bool java_space(char c) { return c == ' ' || c == '\t' || c == '\n' || c == '\x0B' || c == '\f' || c == '\r'; }

// Regex \[[^\]]*]|\([^)]*\)|\*[^*]*\* replaced with " ", then \s+ -> " ", then trim.
std::string clean(const std::string &text) {
    std::string stripped;
    for (size_t i = 0; i < text.size(); ++i) {
        const char open = text[i];
        const char close = open == '[' ? ']' : open == '(' ? ')' : open == '*' ? '*' : 0;
        if (close != 0) {
            const size_t end = text.find(close, i + 1);
            if (end != std::string::npos) {
                stripped += ' ';
                i = end;
                continue;
            }
        }
        stripped += open;
    }
    std::string out;
    bool in_space = false;
    for (char c : stripped) {
        if (java_space(c)) {
            in_space = true;
        } else {
            if (in_space && !out.empty()) out += ' ';
            in_space = false;
            out += c;
        }
    }
    return out;  // leading spaces were never emitted and trailing ones are dropped: trimmed
}

// Kotlin's Char.isLetterOrDigit over the decoded code points (iswalnum under a UTF-8 locale).
bool any_letter_or_digit(const std::string &s) {
    for (size_t i = 0; i < s.size();) {
        const unsigned char c = s[i];
        uint32_t cp;
        int len;
        if (c < 0x80) { cp = c; len = 1; }
        else if ((c >> 5) == 0x6) { cp = c & 0x1F; len = 2; }
        else if ((c >> 4) == 0xE) { cp = c & 0x0F; len = 3; }
        else if ((c >> 3) == 0x1E) { cp = c & 0x07; len = 4; }
        else { ++i; continue; }
        if (i + len > s.size()) break;
        for (int k = 1; k < len; ++k) cp = (cp << 6) | (static_cast<unsigned char>(s[i + k]) & 0x3F);
        i += len;
        if (cp < 0x80 ? std::isalnum(static_cast<int>(cp)) != 0 : std::iswalnum(static_cast<wint_t>(cp)) != 0) {
            return true;
        }
    }
    return false;
}

// ---- native whisper_jni.cpp ------------------------------------------------------------------------

struct Result {
    bool ok = false;
    std::string text;
    float confidence = -1.0f;  // -1: no text tokens
};

Result transcribe(whisper_context *ctx, const std::vector<float> &pcm, int threads) {
    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = threads;
    params.language = "en";
    params.translate = false;
    params.no_context = true;
    params.no_timestamps = true;
    params.single_segment = true;
    params.suppress_nst = true;
    params.temperature_inc = 0.0f;
    params.max_tokens = MAX_TOKENS;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_special = false;
    params.print_timestamps = false;

    Result r;
    whisper_reset_timings(ctx);
    if (whisper_full(ctx, params, pcm.data(), static_cast<int>(pcm.size())) != 0) return r;
    double p_sum = 0.0;
    int p_count = 0;
    const whisper_token eot = whisper_token_eot(ctx);
    const int segments = whisper_full_n_segments(ctx);
    for (int s = 0; s < segments; ++s) {
        r.text += whisper_full_get_segment_text(ctx, s);
        const int tokens = whisper_full_n_tokens(ctx, s);
        for (int t = 0; t < tokens; ++t) {
            if (whisper_full_get_token_id(ctx, s, t) >= eot) continue;
            p_sum += whisper_full_get_token_p(ctx, s, t);
            ++p_count;
        }
    }
    r.confidence = p_count > 0 ? static_cast<float>(p_sum / p_count) : -1.0f;
    r.ok = true;
    return r;
}

std::string confidence_json(bool known, float value) {
    return known ? hostio::json_number(value, 4) : "null";
}

void print_info(int threads) {
    hostio::emit(std::string("{\"engine\":\"whisper.cpp\",\"version\":") + hostio::json_string(ENGINE_VERSION) +
                 ",\"source_sha256\":" + hostio::json_string(ENGINE_SHA256) +
                 ",\"threads\":" + std::to_string(threads) +
                 ",\"system_info\":" + hostio::json_string(whisper_print_system_info()) + "}");
}

// Checks the AudioGate.clean / transcript mirror on cases whose Kotlin result is known.
int self_test() {
    struct Case {
        const char *in;
        const char *text;  // after clean + the letter-or-digit check
    };
    const Case cases[] = {
        {" [BLANK_AUDIO]", ""},
        {" (wind blowing)", ""},
        {" *music*", ""},
        {" Set the temperature to 21.", "Set the temperature to 21."},
        {"[Music] turn on the AC [noise]", "turn on the AC"},
        {"  hello \t\n world  ", "hello world"},
        {"unclosed [tag stays", "unclosed [tag stays"},
        {" ...", ""},
        {" \xE2\x99\xAA", ""},             // a music note is not a letter
        {" caf\xC3\xA9", "caf\xC3\xA9"},  // an accented letter is
    };
    int failures = 0;
    for (const auto &c : cases) {
        const std::string cleaned = clean(c.in);
        const std::string got = any_letter_or_digit(cleaned) ? cleaned : "";
        if (got != c.text) {
            std::fprintf(stderr, "FAIL clean(%s) = '%s', expected '%s'\n", hostio::json_string(c.in).c_str(),
                         got.c_str(), c.text);
            ++failures;
        }
    }
    std::fprintf(stderr, "self-test: %d failures\n", failures);
    return failures == 0 ? 0 : 1;
}

int usage() {
    std::fprintf(stderr, "usage: whisperhost [--threads N] MODEL   (WAV paths on stdin)\n"
                         "       whisperhost [--threads N] --info\n"
                         "       whisperhost --self-test\n");
    return 1;
}

}  // namespace

int main(int argc, char **argv) {
    // iswalnum needs a UTF-8 character type; either name exists on macOS and on glibc.
    if (!std::setlocale(LC_CTYPE, "C.UTF-8")) std::setlocale(LC_CTYPE, "en_US.UTF-8");

    int threads = DEFAULT_THREADS;
    bool info = false;
    std::string model;
    for (int i = 1; i < argc; ++i) {
        const std::string a = argv[i];
        if (a == "--threads" && i + 1 < argc) threads = std::max(1, std::atoi(argv[++i]));
        else if (a == "--info") info = true;
        else if (a == "--self-test") return self_test();
        else if (model.empty() && a.rfind("--", 0) != 0) model = a;
        else return usage();
    }
    if (info) {
        print_info(threads);
        return 0;
    }
    if (model.empty()) return usage();

    whisper_log_set([](ggml_log_level, const char *, void *) {}, nullptr);
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(model.c_str(), cparams);
    if (ctx == nullptr) {
        std::fprintf(stderr, "whisperhost: cannot load model %s\n", model.c_str());
        return 2;
    }
    std::fprintf(stderr, "whisperhost: whisper.cpp %s, %d threads, %s\n", ENGINE_VERSION, threads,
                 whisper_print_system_info());

    const size_t max_samples = static_cast<size_t>(MAX_SECONDS * SAMPLE_RATE);
    std::string path;
    while (std::getline(std::cin, path)) {
        hostio::chomp(path);
        if (path.empty()) continue;
        std::vector<float> pcm;
        try {
            pcm = read_wav(path);
        } catch (const std::exception &e) {
            hostio::emit(std::string("{\"text\":\"\",\"confidence\":null,\"ms\":0,\"raw\":null,\"gated\":false,") +
                         "\"audio_s\":null,\"error\":" + hostio::json_string(std::string("wav: ") + e.what()) + "}");
            continue;
        }
        const std::string audio_s = hostio::json_number(static_cast<double>(pcm.size()) / SAMPLE_RATE, 3);
        const double t0 = hostio::now_ms();
        if (!has_speech(pcm)) {
            const double ms = hostio::now_ms() - t0;
            hostio::emit("{\"text\":\"\",\"confidence\":0,\"ms\":" + hostio::json_number(ms) +
                         ",\"raw\":null,\"gated\":true,\"audio_s\":" + audio_s + "}");
            continue;
        }
        if (pcm.size() > max_samples) pcm.resize(max_samples);
        const Result r = transcribe(ctx, pcm, threads);
        std::string out;
        if (!r.ok) {
            const double ms = hostio::now_ms() - t0;
            out = "{\"text\":\"\",\"confidence\":null,\"ms\":" + hostio::json_number(ms) +
                  ",\"raw\":null,\"gated\":false,\"audio_s\":" + audio_s + ",\"error\":\"whisper_full failed\"}";
        } else {
            const std::string cleaned = clean(r.text);
            const bool spoken = any_letter_or_digit(cleaned);
            const std::string text = spoken ? cleaned : "";
            const std::string conf = spoken ? confidence_json(r.confidence >= 0.0f, r.confidence) : "0";
            const double ms = hostio::now_ms() - t0;
            out = "{\"text\":" + hostio::json_string(text) + ",\"confidence\":" + conf +
                  ",\"ms\":" + hostio::json_number(ms) + ",\"raw\":" + hostio::json_string(r.text) +
                  ",\"gated\":false,\"audio_s\":" + audio_s + "}";
        }
        hostio::emit(out);
    }
    whisper_free(ctx);
    return 0;
}
