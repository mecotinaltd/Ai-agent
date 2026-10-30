package com.collabcode.app.agent

/** Receives the generated text in small pieces. Return false to stop the generation. */
fun interface PieceSink {
    fun onPiece(bytes: ByteArray): Boolean
}

/** The doorway to llama.cpp (libcollab_llama.so, built from src/main/cpp). */
object LlamaBridge {
    val available: Boolean = try {
        System.loadLibrary("collab_llama")
        true
    } catch (e: UnsatisfiedLinkError) {
        false
    }

    /** Returns a handle, or 0 when the file cannot be loaded. */
    external fun nativeLoad(path: String, nCtx: Int, threads: Int): Long

    /**
     * Writes the reply piece by piece into [sink]. Returns the number of generated tokens,
     * or a negative code: -1 chat template, -2 prompt too long, -3 context, -4 decode, -5 tokenize.
     */
    external fun nativeGenerate(
        handle: Long,
        roles: Array<String>,
        contents: Array<String>,
        maxTokens: Int,
        temp: Float,
        topP: Float,
        topK: Int,
        repeatPenalty: Float,
        presencePenalty: Float,
        frequencyPenalty: Float,
        seed: Int,
        sink: PieceSink,
    ): Int

    external fun nativeStop(handle: Long)
    external fun nativeFree(handle: Long)
    external fun nativeSystemInfo(): String
}
