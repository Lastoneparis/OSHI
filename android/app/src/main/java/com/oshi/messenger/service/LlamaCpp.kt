package com.oshi.messenger.service

/**
 * JNI bridge to llama.cpp for on-device LLM inference.
 * Loads libllama_jni.so which links against libllama.so, libggml.so, etc.
 */
class LlamaCpp {
    /**
     * Load a GGUF model from the given file path.
     * @return true if the model was loaded successfully.
     */
    external fun loadModel(modelPath: String): Boolean

    /**
     * Generate text from a prompt using the loaded model.
     * @param prompt The full prompt string (including chat template formatting).
     * @param maxTokens Maximum number of tokens to generate.
     * @return The generated text.
     */
    external fun generate(prompt: String, maxTokens: Int): String

    /**
     * Unload the model and free all native resources.
     */
    external fun unloadModel()

    companion object {
        init {
            System.loadLibrary("llama_jni")
        }
    }
}
