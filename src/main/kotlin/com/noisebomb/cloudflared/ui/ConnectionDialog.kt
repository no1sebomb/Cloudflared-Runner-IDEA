package com.noisebomb.cloudflared.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.noisebomb.cloudflared.model.ConnectionConfig
import com.noisebomb.cloudflared.model.ConnectionType
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Add/edit form for one connection. The type is fixed by whichever entry of the "+" menu was used —
 * the two types share almost no fields, so switching between them mid-form is not worth supporting.
 */
class ConnectionDialog(
    project: Project,
    private val original: ConnectionConfig,
    isNew: Boolean,
) : DialogWrapper(project) {

    private val type = original.type
    private val nameField = JBTextField(original.name, COLUMNS)
    private val targetField = JBTextField(original.target, COLUMNS)
    private val bindField = JBTextField(original.localBind.ifBlank { "localhost:" }, COLUMNS)

    private val hint = JLabel(
        when (type) {
            ConnectionType.QUICK_TUNNEL -> "cloudflared tunnel --url <local service>"
            ConnectionType.ACCESS_TCP -> "cloudflared access tcp --hostname <hostname> --url <local bind>"
        },
    )

    /** Filled in on OK. */
    var result: ConnectionConfig = original.copyOf()
        private set

    init {
        title = (if (isNew) "Add " else "Edit ") + type.displayName
        init()
    }

    override fun createCenterPanel(): JComponent {
        hint.foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
        val builder = FormBuilder.createFormBuilder()
            .addLabeledComponent("Name:", nameField)
            .addLabeledComponent(
                if (type == ConnectionType.QUICK_TUNNEL) "Local service:" else "Hostname:",
                targetField,
            )
        if (type == ConnectionType.ACCESS_TCP) builder.addLabeledComponent("Local bind:", bindField)
        val form: JPanel = builder
            .addComponentToRightColumn(hint, UIUtil.LARGE_VGAP)
            .panel
        form.border = JBUI.Borders.empty(8)
        return form
    }

    override fun getPreferredFocusedComponent(): JComponent = targetField

    override fun doValidate(): ValidationInfo? {
        if (targetField.text.isBlank()) {
            val what = if (type == ConnectionType.QUICK_TUNNEL) "local service" else "hostname"
            return ValidationInfo("Enter a $what.", targetField)
        }
        if (type == ConnectionType.ACCESS_TCP && bindField.text.isBlank()) {
            return ValidationInfo("Enter a local bind address.", bindField)
        }
        return null
    }

    override fun doOKAction() {
        result = original.copyOf().apply {
            type = this@ConnectionDialog.type
            name = nameField.text.trim()
            target = targetField.text.trim()
            localBind = if (type == ConnectionType.ACCESS_TCP) bindField.text.trim() else ""
        }
        super.doOKAction()
    }

    private companion object {
        const val COLUMNS = 28
    }
}
