// JNI bridge between Kotlin (LlamaBridge.kt) and llama.cpp.
// Written against the llama.cpp C API of release b5602 (see LLAMA_CPP_TAG in CMakeLists.txt).
#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"
#include "utf8_util.h"

#define TAG "CollabLlama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct Engine {
    llama_model *model = nullptr;
    const llama_vocab *vocab = nullptr;
    int n_ctx = 4096;
    int n_threads = 4;
    std::atomic<bool> stop{false};
    std::mutex run_mutex;
};

std::once_flag g_backend_once;

void log_cb(ggml_log_level level, const char *text, void *) {
    if (level >= GGML_LOG_LEVEL_WARN) LOGE("%s", text);
}

bool starts_with(const std::string &s, const char *p) {
    return s.rfind(p, 0) == 0;
}

// Result codes returned to Kotlin (negative = error).
constexpr int ERR_TEMPLATE = -1;
constexpr int ERR_PROMPT_TOO_LONG = -2;
constexpr int ERR_CONTEXT = -3;
constexpr int ERR_DECODE = -4;
constexpr int ERR_TOKENIZE = -5;

std::string jstr(JNIEnv *env, jstring s) {
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_collabcode_app_agent_LlamaBridge_nativeLoad(JNIEnv *env, jobject, jstring path, jint n_ctx,
                                                     jint n_threads) {
    std::call_once(g_backend_once, [] {
        llama_log_set(log_cb, nullptr);
        llama_backend_init();
    });
    const std::string p = jstr(env, path);
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;  // CPU only: phone GPUs are not supported by this build
    mp.use_mmap = true;
    llama_model *model = llama_model_load_from_file(p.c_str(), mp);
    if (!model) {
        LOGE("could not load %s", p.c_str());
        return 0;
    }
    auto *e = new Engine();
    e->model = model;
    e->vocab = llama_model_get_vocab(model);
    e->n_ctx = std::max(512, static_cast<int>(n_ctx));
    e->n_threads = std::max(1, static_cast<int>(n_threads));
    LOGI("loaded %s (ctx %d, threads %d)", p.c_str(), e->n_ctx, e->n_threads);
    return reinterpret_cast<jlong>(e);
}

JNIEXPORT void JNICALL
Java_com_collabcode_app_agent_LlamaBridge_nativeStop(JNIEnv *, jobject, jlong handle) {
    auto *e = reinterpret_cast<Engine *>(handle);
    if (e) e->stop.store(true);
}

JNIEXPORT void JNICALL
Java_com_collabcode_app_agent_LlamaBridge_nativeFree(JNIEnv *, jobject, jlong handle) {
    auto *e = reinterpret_cast<Engine *>(handle);
    if (!e) return;
    e->stop.store(true);
    {
        std::lock_guard<std::mutex> lock(e->run_mutex);  // wait for a running generation to end
        if (e->model) llama_model_free(e->model);
        e->model = nullptr;
    }
    delete e;
}

JNIEXPORT jstring JNICALL
Java_com_collabcode_app_agent_LlamaBridge_nativeSystemInfo(JNIEnv *env, jobject) {
    return env->NewStringUTF(llama_print_system_info());
}

JNIEXPORT jint JNICALL
Java_com_collabcode_app_agent_LlamaBridge_nativeGenerate(
    JNIEnv *env, jobject, jlong handle, jobjectArray roles, jobjectArray contents, jint max_tokens,
    jfloat temp, jfloat top_p, jint top_k, jfloat repeat_penalty, jfloat presence_penalty,
    jfloat frequency_penalty, jint seed, jobject sink) {
    auto *e = reinterpret_cast<Engine *>(handle);
    if (!e || !e->model || !sink) return ERR_CONTEXT;
    jclass sink_cls = env->GetObjectClass(sink);
    jmethodID on_piece = sink_cls ? env->GetMethodID(sink_cls, "onPiece", "([B)Z") : nullptr;
    if (!on_piece) {
        env->ExceptionClear();
        return ERR_CONTEXT;
    }
    std::lock_guard<std::mutex> lock(e->run_mutex);
    e->stop.store(false);

    // 1. Turn the messages into one prompt with the model's own chat template.
    const jsize n_msg = env->GetArrayLength(roles);
    std::vector<std::string> role_s(n_msg), content_s(n_msg);
    std::vector<llama_chat_message> msgs(n_msg);
    size_t total_chars = 0;
    for (jsize i = 0; i < n_msg; i++) {
        auto jr = reinterpret_cast<jstring>(env->GetObjectArrayElement(roles, i));
        auto jc = reinterpret_cast<jstring>(env->GetObjectArrayElement(contents, i));
        role_s[i] = jstr(env, jr);
        content_s[i] = jstr(env, jc);
        env->DeleteLocalRef(jr);
        env->DeleteLocalRef(jc);
        msgs[i].role = role_s[i].c_str();
        msgs[i].content = content_s[i].c_str();
        total_chars += content_s[i].size() + role_s[i].size();
    }
    const char *tmpl = llama_model_chat_template(e->model, nullptr);
    if (!tmpl) tmpl = "chatml";  // models without an embedded template: ChatML is the safest guess
    std::vector<char> buf(total_chars * 2 + 1024);
    int len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(), buf.size());
    if (len > static_cast<int>(buf.size())) {
        buf.resize(len + 16);
        len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(), buf.size());
    }
    if (len < 0) return ERR_TEMPLATE;
    const std::string prompt(buf.data(), len);

    // 2. Tokenize.
    const bool add_special = !(starts_with(prompt, "<|begin_of_text|>") || starts_with(prompt, "<s>") ||
                               starts_with(prompt, "<bos>"));
    int n_prompt = -llama_tokenize(e->vocab, prompt.c_str(), prompt.size(), nullptr, 0, add_special, true);
    if (n_prompt <= 0) return ERR_TOKENIZE;
    std::vector<llama_token> tokens(n_prompt);
    if (llama_tokenize(e->vocab, prompt.c_str(), prompt.size(), tokens.data(), tokens.size(), add_special,
                       true) < 0) {
        return ERR_TOKENIZE;
    }
    if (n_prompt + 8 >= e->n_ctx) return ERR_PROMPT_TOO_LONG;
    const int room = e->n_ctx - n_prompt - 4;
    const int max_new = std::max(1, std::min(static_cast<int>(max_tokens), room));

    // 3. A fresh context per request keeps this code independent of KV-cache API changes.
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = e->n_ctx;
    cp.n_batch = 512;
    cp.n_ubatch = 512;
    cp.n_threads = e->n_threads;
    cp.n_threads_batch = e->n_threads;
    llama_context *ctx = llama_init_from_model(e->model, cp);
    if (!ctx) return ERR_CONTEXT;

    // 4. Sampler chain: penalties, top-k, top-p, temperature, random pick.
    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (repeat_penalty != 1.0f || presence_penalty != 0.0f || frequency_penalty != 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_penalties(64, repeat_penalty, frequency_penalty,
                                                                   presence_penalty));
    }
    if (temp <= 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        if (top_k > 0) llama_sampler_chain_add(smpl, llama_sampler_init_top_k(top_k));
        if (top_p > 0.0f && top_p < 1.0f) llama_sampler_chain_add(smpl, llama_sampler_init_top_p(top_p, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temp));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(seed < 0 ? LLAMA_DEFAULT_SEED : static_cast<uint32_t>(seed)));
    }

    int result = 0;

    // 5. Read the prompt in chunks.
    for (int i = 0; i < n_prompt && result == 0; i += cp.n_batch) {
        if (e->stop.load()) break;
        const int n = std::min<int>(cp.n_batch, n_prompt - i);
        llama_batch batch = llama_batch_get_one(tokens.data() + i, n);
        if (llama_decode(ctx, batch) != 0) result = ERR_DECODE;
    }

    // 6. Generate. Bytes are held back until they form complete UTF-8 characters.
    std::string pending;
    bool keep_going = true;
    int produced = 0;
    auto emit = [&](const std::string &bytes) -> bool {
        if (bytes.empty()) return true;
        jbyteArray arr = env->NewByteArray(static_cast<jsize>(bytes.size()));
        env->SetByteArrayRegion(arr, 0, static_cast<jsize>(bytes.size()),
                                reinterpret_cast<const jbyte *>(bytes.data()));
        const jboolean ok = env->CallBooleanMethod(sink, on_piece, arr);
        env->DeleteLocalRef(arr);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return false;
        }
        return ok == JNI_TRUE;
    };

    while (result == 0 && keep_going && produced < max_new && !e->stop.load()) {
        llama_token tok = llama_sampler_sample(smpl, ctx, -1);
        if (llama_vocab_is_eog(e->vocab, tok)) break;
        char piece[256];
        int n = llama_token_to_piece(e->vocab, tok, piece, sizeof(piece), 0, true);
        if (n < 0) {
            std::string big(static_cast<size_t>(-n), '\0');
            n = llama_token_to_piece(e->vocab, tok, big.data(), big.size(), 0, true);
            if (n > 0) pending.append(big.data(), n);
        } else if (n > 0) {
            pending.append(piece, n);
        }
        const size_t k = utf8_valid_prefix(pending);
        if (k > 0) {
            keep_going = emit(pending.substr(0, k));
            pending.erase(0, k);
        }
        produced++;
        if (!keep_going) break;
        llama_batch next = llama_batch_get_one(&tok, 1);
        if (llama_decode(ctx, next) != 0) {
            result = ERR_DECODE;
            break;
        }
    }
    if (keep_going && !pending.empty()) emit(pending);  // whatever is left, even if cut short

    llama_sampler_free(smpl);
    llama_free(ctx);
    return result < 0 ? result : produced;
}

}  // extern "C"
