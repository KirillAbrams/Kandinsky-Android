package dev.kirill.kandinsky.chat.diagnostics

import android.view.Window

// Система может ограничить фактическую частоту из-за питания или температуры устройства
class ActiveDisplayPolicy(private val window: Window) : AutoCloseable {
    private var ownedMode: Int? = null
    private var previous = 0

    fun setActive(active: Boolean) {
        if (active == (ownedMode != null)) return
        val attributes = window.attributes
        if (active) {
            val display = window.decorView.display ?: return
            val current = display.mode
            val mode =
                display.supportedModes
                    .filter {
                        it.physicalWidth == current.physicalWidth &&
                            it.physicalHeight == current.physicalHeight
                    }
                    .maxByOrNull { it.refreshRate } ?: return
            previous = attributes.preferredDisplayModeId
            ownedMode = mode.modeId
            attributes.preferredDisplayModeId = mode.modeId
            window.attributes = attributes
        } else {
            if (attributes.preferredDisplayModeId == ownedMode) {
                attributes.preferredDisplayModeId = previous
                window.attributes = attributes
            }
            ownedMode = null
        }
    }

    override fun close() {
        setActive(false)
    }
}
