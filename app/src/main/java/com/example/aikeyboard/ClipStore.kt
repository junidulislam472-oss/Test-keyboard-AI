package com.example.aikeyboard

import java.io.File

class ClipItem(val text: String?, val file: File?, val mime: String?, val time: Long) {
    var dismissed = false
}

/** Clipboard history. Kept in memory only (never written to disk) so copied passwords are not stored. */
object ClipStore {
    val items = ArrayList<ClipItem>()

    fun add(item: ClipItem) {
        if (item.text != null) items.removeAll { it.text == item.text }
        items.add(0, item)
        while (items.size > 25) items.removeAt(items.size - 1).file?.delete()
    }

    fun clear() {
        items.forEach { it.file?.delete() }
        items.clear()
    }
}
