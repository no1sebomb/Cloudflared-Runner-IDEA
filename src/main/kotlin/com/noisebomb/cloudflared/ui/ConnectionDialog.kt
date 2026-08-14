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
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Add/edit form for one connection. The type picker swaps which fields are shown, since a quick
 * tunnel needs a local target and an access client needs a hostname plus a local bind address.
 */
class ConnectionDialog(
    project: Project,
    private val original: ConnectionConfig,
    isNew: Boolean,
) : DialogWrapper(project) {

    private val typeCombo = JComboBox(ConnectionType.entries.toTypedArray())
    private val nameField = JBTextField(original.name, COLUMNS)
    private val targetField = JBTextField(original.target, COLUMNS)
    private val bindField = JBTextField(original.localBind.ifBlank { "localhost:" }, COLUMNS)

    private val targetLabel = JLabel()
    private val bindLabel = JLabel("Local bind:")
    private val hint = JLabel()

    /** Filled in on OK. */
    var result: ConnectionConfig = original.copyOf()
        private set

    init {
        title = if (isNew) "Add Connection" else "Edit Connection"
        typeCombo.selectedItem = original.type
        typeCombo.addActionListener { updateForType() }
        init()
        updateForType()
    }

    private fun selectedType(): ConnectionType = typeCombo.selectedItem as ConnectionType

    private fun updateForType() {
        val quick = selectedType() == ConnectionType.QUICK_TUNNEL
        targetLabel.text = if (quick) "Local service:" else "Hostname:"
        hint.text = if (quick) {
            "cloudflared tunnel --url <local service>"
        } else {
            "cloudflared access tcp --hostname <hostname> --url <local bind>"
        }
        bindLabel.isVisible = !quick
        bindField.isVisible = !quick
    }

    override fun createCenterPanel(): JComponent {
        hint.foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
        val form: JPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Type:", typeCombo)
            .addLabeledComponent("Name:", nameField)
            .addLabeledComponent(targetLabel, targetField)
            .addLabeledComponent(bindLabel, bindField)
            .addComponentToRightColumn(hint, UIUtil.LARGE_VGAP)
            .panel
        form.border = JBUI.Borders.empty(8)
        return form
    }

    override fun getPreferredFocusedComponent(): JComponent = targetField

    override fun doValidate(): ValidationInfo? {
        if (targetField.text.isBlank()) {
            val what = if (selectedType() == ConnectionType.QUICK_TUNNEL) "local service" else "hostname"
            return ValidationInfo("Enter a $what.", targetField)
        }
        if (selectedType() == ConnectionType.ACCESS_TCP && bindField.text.isBlank()) {
            return ValidationInfo("Enter a local bind address.", bindField)
        }
        return null
    }

    override fun doOKAction() {
        result = original.copyOf().apply {
            type = selectedType()
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
