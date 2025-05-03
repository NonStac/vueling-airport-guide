package com.nonstac.airportguide.service // Adjust package if needed

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log // Add Log import
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID

class TextToSpeechService(context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = TextToSpeech(context, this)
    private var isInitialized = false
    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()
    private val TAG = "TextToSpeechService" // Add TAG for logging

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.US) // Set default language
            isInitialized = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
            if(isInitialized) {
                Log.d(TAG, "TTS Initialized successfully.")
                setupProgressListener()
            } else {
                Log.e(TAG, "TTS Language not supported or missing data.")
                isInitialized = false
            }
        } else {
            isInitialized = false
            Log.e(TAG, "TTS Initialization failed with status: $status")
        }
    }

    private fun setupProgressListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                _isSpeaking.value = true
                Log.d(TAG,"TTS Started: $utteranceId")
            }

            override fun onDone(utteranceId: String?) {
                _isSpeaking.value = false
                Log.d(TAG,"TTS Done: $utteranceId")
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                _isSpeaking.value = false
                Log.e(TAG,"TTS Deprecated Error: $utteranceId")
            }
            override fun onError(utteranceId: String?, errorCode: Int) {
                _isSpeaking.value = false
                Log.e(TAG,"TTS Error: $utteranceId, Code: $errorCode")
            }
        })
    }

    fun speak(text: String) {
        if (isInitialized && text.isNotEmpty()) {
            val utteranceId = UUID.randomUUID().toString()
            Log.d(TAG, "TTS Speak Request: $utteranceId - '$text'")
            // Use QUEUE_FLUSH to interrupt previous speech if any
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        } else if (!isInitialized) {
            Log.e(TAG, "TTS not initialized, cannot speak.")
        } else {
            Log.w(TAG, "TTS Speak request with empty text.")
        }
    }


    fun stop() {
        if (isInitialized && _isSpeaking.value) {
            Log.d(TAG, "TTS Stop Requested.")
            tts?.stop()
            // Manually update state as onDone might not be called immediately after stop
            _isSpeaking.value = false
        }
    }


    fun shutdown() {
        Log.d(TAG, "TTS Shutdown.")
        tts?.stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
        _isSpeaking.value = false
    }
}