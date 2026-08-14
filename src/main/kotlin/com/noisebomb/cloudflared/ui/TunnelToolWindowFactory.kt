package com.noisebomb.cloudflared.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class TunnelToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = TunnelPanel(project)
        // Tool window disposable dies with the project, taking the consoles with it.
        Disposer.register(toolWindow.disposable, panel)
        val content = ContentFactory.getInstance().createContent(panel, null, false)
        content.isCloseable = false
        toolWindow.contentManager.addContent(content)
    }
}
