package com.jslw.genz.data

import com.jslw.genz.model.Song

/** Folders kept out of the library: call recordings and WhatsApp Audio by default, plus any the user hides. */
object HiddenFolders {
    private val CALL = Regex("(?i)(^|/)(call[ _-]?rec[a-z]*|call ?recordings?|call|calls|phonerecord[a-z]*)(/|$)")
    private val WHATSAPP = Regex("(?i)/whatsapp( business)? (audio|voice notes)(/|$)")

    fun custom(): Set<String> = Prefs.hiddenFolders.split('\n').map { it.trim().trimEnd('/') }.filter { it.isNotEmpty() }.toSet()

    fun hide(path: String) { Prefs.hiddenFolders = (custom() + path.trimEnd('/')).joinToString("\n") }
    fun unhide(path: String) { Prefs.hiddenFolders = (custom() - path.trimEnd('/')).joinToString("\n") }

    fun isCallRecording(dir: String) = CALL.containsMatchIn(dir)
    fun isWhatsAppAudio(dir: String) = WHATSAPP.containsMatchIn(dir)

    /** A filter that is cheap to run over the whole library. */
    fun filter(): (Song) -> Boolean {
        val calls = Prefs.hideCallRecordings
        val wa = Prefs.hideWhatsAppAudio
        val mine = custom()
        if (!calls && !wa && mine.isEmpty()) return { true }
        return { s ->
            val dir = s.path.substringBeforeLast('/', s.folder)
            !(calls && isCallRecording(dir)) && !(wa && isWhatsAppAudio(dir)) &&
                mine.none { dir == it || dir.startsWith("$it/") }
        }
    }
}
