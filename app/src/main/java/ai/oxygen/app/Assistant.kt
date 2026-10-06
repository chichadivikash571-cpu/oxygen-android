package ai.oxygen.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/** Registers OXYGEN with Android as a digital assistant. */
class OxygenInteractionService : VoiceInteractionService()

/** Android asks for one of these each time the assistant gesture is used. */
class OxygenSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = OxygenSession(this)
}

/** When the assistant gesture happens, show the OXYGEN globe over the screen (not the whole app). */
class OxygenSession(context: Context) : VoiceInteractionSession(context) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val intent = Intent(context, OverlayActivity::class.java)
            .setAction(Intent.ACTION_ASSIST)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        try {
            startAssistantActivity(intent)
        } catch (e: Exception) {
            try {
                context.startActivity(intent)
            } catch (_: Exception) {
            }
        }
        hide()
    }
}

/**
 * Android expects an assistant to include a speech recognizer. OXYGEN does its own listening inside the
 * app, so this one only declines politely. Other apps keep using Google's recognizer.
 */
class OxygenRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: RecognitionService.Callback?) {
        try {
            listener?.error(SpeechRecognizer.ERROR_CLIENT)
        } catch (_: Exception) {
        }
    }

    override fun onCancel(listener: RecognitionService.Callback?) {}

    override fun onStopListening(listener: RecognitionService.Callback?) {}
}
