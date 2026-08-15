package com.noisebomb.cloudflared.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.AnimatedIcon
import com.noisebomb.cloudflared.model.ConnectionStatus
import com.noisebomb.cloudflared.model.ConnectionType
import javax.swing.Icon

/** `_dark` variants are picked up by [IconLoader]; never reference them directly. */
object CloudflaredIcons {

    val QuickTunnel: Icon = IconLoader.getIcon("/icons/quickTunnel.svg", CloudflaredIcons::class.java)
    val AccessClient: Icon = IconLoader.getIcon("/icons/accessClient.svg", CloudflaredIcons::class.java)

    fun of(type: ConnectionType): Icon = when (type) {
        ConnectionType.QUICK_TUNNEL -> QuickTunnel
        ConnectionType.ACCESS_TCP -> AccessClient
    }

    /**
     * Running is a checkmark rather than a play triangle, which reads as a button you are supposed
     * to press. Starting is the platform spinner, which needs
     * [com.intellij.ui.AnimatedIcon.ANIMATION_IN_RENDERER_ALLOWED] on the table to actually turn.
     */
    fun of(status: ConnectionStatus): Icon = when (status) {
        ConnectionStatus.STOPPED -> AllIcons.Process.Step_passive
        ConnectionStatus.STARTING -> AnimatedIcon.Default.INSTANCE
        ConnectionStatus.RUNNING -> AllIcons.General.InspectionsOK
        ConnectionStatus.AWAITING_AUTH -> AllIcons.General.Warning
        ConnectionStatus.FAILED -> AllIcons.General.Error
    }

    /**
     * Whether [of] returns a single-colour icon that should be recoloured on a selected row. The
     * colourful ones read fine as they are, and the spinner has to be left alone or the filter
     * freezes it on one frame.
     */
    fun isMonochrome(status: ConnectionStatus): Boolean = status == ConnectionStatus.STOPPED
}
