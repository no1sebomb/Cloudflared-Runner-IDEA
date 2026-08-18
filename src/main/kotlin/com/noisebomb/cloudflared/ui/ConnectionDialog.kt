package com.noisebomb.cloudflared.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.ComponentValidator
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.CollectionComboBoxModel
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBIntSpinner
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.fields.ExtendableTextComponent.Extension
import com.intellij.ui.components.fields.ExtendableTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.BottomGap
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.RightGap
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import com.noisebomb.cloudflared.model.AccessProtocol
import com.noisebomb.cloudflared.model.ConnectionColor
import com.noisebomb.cloudflared.model.ConnectionConfig
import com.noisebomb.cloudflared.model.ConnectionType
import com.noisebomb.cloudflared.model.EdgeIpVersion
import com.noisebomb.cloudflared.model.LogLevel
import com.noisebomb.cloudflared.model.TunnelProtocol
import com.noisebomb.cloudflared.service.CloudflaredBinary
import com.noisebomb.cloudflared.service.HostProbe
import com.noisebomb.cloudflared.service.TunnelService
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import javax.swing.JComponent
import javax.swing.ScrollPaneConstants
import javax.swing.Scrollable
import javax.swing.event.DocumentEvent
import javax.swing.text.JTextComponent

/**
 * Add/edit form for one connection. The type is fixed by whichever entry of the "+" menu was used —
 * the two types share only the name and the colour, so switching between them mid-form is not worth
 * supporting.
 *
 * Every advanced option maps to one real `cloudflared` flag and is a checkbox, a combo or a value
 * field. There is deliberately no free-text argument box: a stray `--help` there turns a tunnel into
 * a process that prints usage and exits, and nothing upstream of the spawn would catch it.
 */
class ConnectionDialog(
    private val project: Project,
    private val original: ConnectionConfig,
    isNew: Boolean,
) : DialogWrapper(project) {

    private val connectionType = original.type
    private val isQuickTunnel = connectionType == ConnectionType.QUICK_TUNNEL

    private val nameField = JBTextField(original.name, FIELD_COLUMNS)
    private val colorCombo = swatchCombo(original.color)
    private val executableField = TextFieldWithBrowseButton().apply {
        text = original.executable
        (textField as? JBTextField)?.emptyText?.text = TunnelService.getInstance(project).executable
        addBrowseFolderListener(project, FileChooserDescriptorFactory.singleFile())
    }
    private val targetField = ExtendableTextField(original.target, FIELD_COLUMNS).apply {
        emptyText.text = if (isQuickTunnel) SERVICE_HINT else HOSTNAME_HINT
    }
    private val bindField = ExtendableTextField(original.localBind.ifBlank { "localhost:" }, FIELD_COLUMNS).apply {
        emptyText.text = BIND_HINT
    }
    private val accessProtocolCombo = textCombo(AccessProtocol.entries, original.accessProtocol)

    // --- advanced, quick tunnel ---
    private val tunnelProtocolCombo = textCombo(TunnelProtocol.entries, original.tunnelProtocol)
    private val edgeIpCombo = textCombo(EdgeIpVersion.entries, original.edgeIpVersion)
    private val regionField = JBTextField(original.region, SHORT_COLUMNS)
    private val retriesSpinner = JBIntSpinner(original.retries, 0, MAX_RETRIES)
    private val hostHeaderField = JBTextField(original.httpHostHeader, COLUMNS)
    private val originServerNameField = JBTextField(original.originServerName, COLUMNS)
    private val postQuantumBox = JBCheckBox("Post-quantum key agreement", original.postQuantum)
    private val http2OriginBox = JBCheckBox("Talk HTTP/2 to the local service", original.http2Origin)
    private val noChunkedBox = JBCheckBox("Disable chunked transfer encoding", original.noChunkedEncoding)
    private val noTlsVerifyBox = JBCheckBox("Skip TLS verification of the local service", original.noTlsVerify)
    private val socks5Box = JBCheckBox("Serve the local end as a SOCKS5 proxy", original.socks5)
    private val noAutoUpdateBox = JBCheckBox("Disable automatic cloudflared updates", original.noAutoUpdate)

    // --- advanced, access ---
    private val destinationField = JBTextField(original.destination, COLUMNS)

    // --- advanced, shared ---
    private val logLevelCombo = textCombo(LogLevel.entries, original.logLevel)

    /**
     * The platform's own field validation — amber or red outline plus its balloon. Everything the
     * checks report goes through these rather than a hand-rolled tooltip, so a warning here looks
     * exactly like a warning anywhere else in the IDE.
     */
    private val targetStatus = FieldStatus(targetField)
    private val bindStatus = FieldStatus(bindField)
    private val executableStatus =
        FieldStatus(executableField.textField as ExtendableTextField, host = executableField)

    /** Target of the last check; its result is advisory and never blocks OK. */
    private var checkedTarget = ""

    /** Executable of the last check, already resolved through the project default. */
    private var checkedExecutable = ""

    /** Bind address of the last check. Unlike the hostname, a taken port does block OK. */
    private var checkedBind = ""
    private var bindPortTaken = false

    /** Set once the user edits Name by hand, after which [targetField] stops writing to it. */
    private var nameEdited = original.name.isNotBlank()
    private var updatingName = false

    private val commandPreview = CommandPreview()

    /** Debounce [checkBind] and [checkTarget] so a probe does not run on every keystroke. */
    private val bindAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, disposable)
    private val targetAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, disposable)
    private val executableAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, disposable)

    /** Filled in on OK. */
    var result: ConnectionConfig = original.copyOf()
        private set

    init {
        title = (if (isNew) "Add " else "Edit ") + connectionType.displayName
        setOKButtonText(if (isNew) "Add" else "Save")
        init()
        installAutoName()
        installPreview()
        installTargetCheck()
        installExecutableCheck()
        if (!isQuickTunnel) installBindCheck()
        updatePreview()
    }

    override fun createCenterPanel(): JComponent {
        val form = panel {
            group(GENERAL_TITLE) {
                row("Name:") {
                    cell(nameField)
                    label("Color:").gap(RightGap.SMALL)
                    cell(colorCombo)
                }
                row("Executable:") { cell(executableField).align(AlignX.FILL) }
                    .rowComment("Leave empty to use the <code>cloudflared</code> this project is set up with.")
            }

            group(CONNECTION_TITLE) {
                if (isQuickTunnel) {
                    row("Local service:") { cell(targetField) }
                } else {
                    row("Public hostname:") {
                        cell(targetField)
                        label("Protocol:").gap(RightGap.SMALL)
                        cell(accessProtocolCombo)
                    }
                    row("Local bind:") { cell(bindField) }
                }
            }

            // Always collapsed on open: the form the user came here for is the one above it, and a
            // group that remembers being expanded pushes that form off the top of a short dialog.
            collapsibleGroup(ADVANCED_TITLE) {
                if (isQuickTunnel) quickTunnelOptions() else accessOptions()
                row("Log level:") { cell(logLevelCombo) }
            }.expanded = false

            group(COMMAND_TITLE) {
                row { cell(commandPreview).align(AlignX.FILL) }
            }
        }
        form.border = JBUI.Borders.empty(8)
        val host = FormHost(form)
        // Lay the form out once before measuring it. The command preview wraps, so its height is a
        // function of its width — and until something has laid it out its width is zero, which sends
        // it to a fallback width that is not the one it ends up with. Measuring the untouched form
        // therefore reports the height of a wrap that never happens, leaving the viewport short by
        // however much the real wrap adds and putting a scrollbar on a form that fits.
        host.setSize(host.preferredSize.width, Short.MAX_VALUE.toInt())
        host.doLayout()
        return JBScrollPane(host).apply {
            border = JBUI.Borders.empty()
            viewportBorder = null
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBar.unitIncrement = JBUI.scale(SCROLL_UNIT)
            // Opens at the form's own size; past this the dialog scrolls instead of running off
            // the bottom of the screen, which is what the expanded advanced group does otherwise.
            // The slack is for the rounding either side of that measurement: a scrollbar that buys
            // the user a pixel or two of travel is pure cost, so err on the side of not showing one.
            val natural = preferredSize.height + JBUI.scale(FORM_HEIGHT_SLACK)
            preferredSize = Dimension(preferredSize.width, minOf(natural, maxFormHeight()))
        }
    }

    /**
     * How tall the form may get before it scrolls instead. Measured off the screen rather than
     * fixed, because the screen is the thing the cap exists to stay inside: a constant generous
     * enough for a laptop leaves a desktop scrolling a form that had room to open whole, and one
     * tuned to today's form starts scrolling for a pixel or two the moment a row is added to it.
     *
     * The budget is the *usable* screen — [GraphicsEnvironment.getMaximumWindowBounds] already
     * excludes the taskbar — less room for the dialog's title bar, button row and margins.
     */
    private fun maxFormHeight(): Int {
        val usable = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds.height
        return (usable * SCREEN_BUDGET).toInt().coerceAtLeast(JBUI.scale(MIN_FORM_HEIGHT))
    }

    /**
     * Keeps the form at the viewport's width so only vertical scrolling ever happens, and pinned to
     * the top so a short form does not stretch to fill the dialog.
     */
    private class FormHost(form: JComponent) : JBPanel<FormHost>(BorderLayout()), Scrollable {
        init {
            isOpaque = false
            add(form, BorderLayout.NORTH)
        }

        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

        override fun getScrollableUnitIncrement(visible: Rectangle, orientation: Int, direction: Int): Int =
            JBUI.scale(SCROLL_UNIT)

        override fun getScrollableBlockIncrement(visible: Rectangle, orientation: Int, direction: Int): Int =
            visible.height

        override fun getScrollableTracksViewportWidth(): Boolean = true

        override fun getScrollableTracksViewportHeight(): Boolean = false
    }

    private fun Panel.quickTunnelOptions() {
        row("Protocol:") {
            cell(tunnelProtocolCombo).gap(RightGap.COLUMNS)
            label("Edge IP version:").gap(RightGap.SMALL)
            cell(edgeIpCombo)
        }
        row("Region:") {
            cell(regionField).gap(RightGap.COLUMNS)
            label("Connection retries:").gap(RightGap.SMALL)
            cell(retriesSpinner)
        }.rowComment("Empty is the global region. <code>us</code> pins the tunnel to the United States.")
            .bottomGap(BottomGap.SMALL)
        row("Host header:") { cell(hostHeaderField).align(AlignX.FILL) }
        row("Origin server name:") { cell(originServerNameField).align(AlignX.FILL) }
            .bottomGap(BottomGap.SMALL)
        row { cell(postQuantumBox) }
        row { cell(http2OriginBox) }
        row { cell(noChunkedBox) }
        row { cell(noTlsVerifyBox) }
        row { cell(socks5Box) }
        row { cell(noAutoUpdateBox) }
    }

    /**
     * Short on purpose: `cloudflared access` takes almost nothing besides the hostname and the
     * listener. The two flags it does take that are not service-token credentials are here.
     */
    private fun Panel.accessOptions() {
        row("Destination:") { cell(destinationField).align(AlignX.FILL) }
            .rowComment("Machine to reach behind the Access application, when it fronts more than one.")
    }

    override fun getPreferredFocusedComponent(): JComponent = targetField

    override fun doValidate(): ValidationInfo? {
        // Target first: on an untouched form both are empty, and naming the field the user is
        // actually expected to fill beats demanding a name the target is about to generate anyway.
        if (targetField.text.isBlank()) {
            val what = if (isQuickTunnel) "local service" else "public hostname"
            return ValidationInfo("Enter a $what.", targetField)
        }
        if (nameField.text.isBlank()) return ValidationInfo("Enter a name.", nameField)
        if (!isQuickTunnel) {
            val bind = bindField.text.trim()
            if (bind.isBlank()) return ValidationInfo("Enter a local bind address.", bindField)
            if (HostProbe.socketAddress(bind) == null) {
                return ValidationInfo("Local bind needs a host and a port, e.g. localhost:5433.", bindField)
            }
            // Only ever blocks on a port we have actually seen refuse a bind; see [checkBind].
            if (bind == checkedBind && bindPortTaken) {
                val port = HostProbe.socketAddress(bind)?.port
                return ValidationInfo("Port :$port is already allocated.", bindField)
            }
        }
        return null
    }

    override fun doOKAction() {
        result = original.copyOf().also { applyTo(it) }
        super.doOKAction()
    }

    /** Everything the form knows, written onto [config]. Shared by OK and the command preview. */
    private fun applyTo(config: ConnectionConfig) {
        config.type = connectionType
        config.name = nameField.text.trim()
        config.color = colorCombo.item
        config.executable = executableField.text.trim()
        config.target = targetField.text.trim()
        config.logLevel = logLevelCombo.item
        if (isQuickTunnel) {
            config.localBind = ""
            config.tunnelProtocol = tunnelProtocolCombo.item
            config.edgeIpVersion = edgeIpCombo.item
            config.region = regionField.text.trim()
            config.retries = retriesSpinner.number
            config.httpHostHeader = hostHeaderField.text.trim()
            config.originServerName = originServerNameField.text.trim()
            config.postQuantum = postQuantumBox.isSelected
            config.http2Origin = http2OriginBox.isSelected
            config.noChunkedEncoding = noChunkedBox.isSelected
            config.noTlsVerify = noTlsVerifyBox.isSelected
            config.socks5 = socks5Box.isSelected
            config.noAutoUpdate = noAutoUpdateBox.isSelected
        } else {
            config.localBind = bindField.text.trim()
            config.accessProtocol = accessProtocolCombo.item
            config.destination = destinationField.text.trim()
        }
    }

    // --- behaviour ----------------------------------------------------------------------------

    /**
     * Name is required, so an empty one tracks the target until the user takes it over. Typing in
     * Name — even to clear it — hands control back for good, which is the only way to avoid an
     * autofill that keeps overwriting what you just typed.
     */
    private fun installAutoName() {
        nameField.onChange { if (!updatingName) nameEdited = true }
        targetField.onChange {
            if (nameEdited) return@onChange
            updatingName = true
            try {
                nameField.text = ConnectionConfig.suggestName(connectionType, targetField.text.trim())
            } finally {
                updatingName = false
            }
        }
    }

    private fun installPreview() {
        listOf(
            nameField,
            targetField,
            bindField,
            regionField,
            hostHeaderField,
            originServerNameField,
            destinationField,
        ).forEach { it.onChange { updatePreview() } }
        listOf(accessProtocolCombo, tunnelProtocolCombo, edgeIpCombo, logLevelCombo)
            .forEach { it.addActionListener { updatePreview() } }
        retriesSpinner.addChangeListener { updatePreview() }
        executableField.textField.onChange { updatePreview() }
        listOf(postQuantumBox, http2OriginBox, noChunkedBox, noTlsVerifyBox, socks5Box, noAutoUpdateBox)
            .forEach { it.addActionListener { updatePreview() } }
    }

    private fun updatePreview() {
        val draft = original.copyOf().also { applyTo(it) }
        if (draft.target.isBlank()) draft.target = if (isQuickTunnel) "<local service>" else "<hostname>"
        if (!isQuickTunnel && draft.localBind.isBlank()) draft.localBind = "<local bind>"
        val executable = draft.resolveExecutable(TunnelService.getInstance(project).executable)
        commandPreview.setCommand(draft.commandLine(executable))
    }

    /**
     * Both types get their target checked when the user leaves the field, but they are different
     * questions. See [checkHostname] and [checkLocalService].
     */
    private fun installTargetCheck() {
        targetField.addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent) = checkTarget()
        })
        // Only the quick tunnel probes as you type: its target is a socket on this machine, while an
        // access hostname costs a DNS lookup and an HTTPS round trip per keystroke.
        if (isQuickTunnel) {
            targetField.onChange {
                targetAlarm.cancelAllRequests()
                targetAlarm.addRequest({ checkTarget() }, TYPING_PAUSE_MILLIS)
            }
        }
        when {
            targetField.text.isNotBlank() -> checkTarget()
            isQuickTunnel -> targetStatus.clear()
        }
    }

    private fun checkTarget() {
        if (isQuickTunnel) checkLocalService() else checkHostname()
    }

    /**
     * A quick tunnel is happy to start without its origin — it just answers 502 — so this never
     * blocks anything. It is still worth saying, because a tunnel that serves nothing but 502 looks
     * identical to a working one until you open the URL.
     */
    private fun checkLocalService() {
        val target = targetField.text.trim()
        if (target == checkedTarget) return
        checkedTarget = target
        if (target.isBlank() || HostProbe.socketAddress(target) == null) {
            targetStatus.clear()
            return
        }
        targetStatus.clear()
        val httpExpected = original.copyOf().apply { this.target = target }.isHttpTarget()
        ApplicationManager.getApplication().executeOnPooledThread {
            val reachable = HostProbe.canConnect(target)
            val speaksHttp = !reachable || !httpExpected || HostProbe.speaksHttp(target)
            onEdt {
                if (target != checkedTarget) return@onEdt
                when {
                    !reachable -> targetStatus.problem(
                        "Nothing is listening on $target — the tunnel will answer 502",
                        warning = true,
                    )

                    !speaksHttp -> targetStatus.problem(
                        "$target answered, but not over HTTP — the public URL only serves HTTP",
                        warning = true,
                    )

                    else -> targetStatus.ok("$target is reachable")
                }
            }
        }
    }

    /**
     * Two questions for an access client: does the hostname resolve, and is Cloudflare answering for
     * it. The `server: cloudflare` header is the giveaway — an Access application replies with a
     * redirect to the SSO page, and that reply is still Cloudflare's. A host that answers with
     * anything else is almost certainly not something `cloudflared access` can front.
     *
     * It stops there. Whether an Access *application* is actually published on that hostname only
     * answers to a request carrying a token, which this plugin deliberately never holds.
     */
    private fun checkHostname() {
        val host = targetField.text.trim().substringAfter("://").substringBefore('/').substringBefore(':')
        if (host == checkedTarget) return
        checkedTarget = host
        if (host.isBlank()) {
            targetStatus.clear()
            return
        }
        targetStatus.clear()
        ApplicationManager.getApplication().executeOnPooledThread {
            val resolved = HostProbe.resolves(host)
            val server = if (resolved) HostProbe.serverHeader(host) else null
            onEdt {
                if (host != checkedTarget) return@onEdt
                when {
                    !resolved -> targetStatus.problem("$host could not be resolved", warning = false)
                    server == null ->
                        targetStatus.problem("$host did not answer over HTTPS", warning = true)
                    !server.contains(CLOUDFLARE) ->
                        targetStatus.problem("$host is served by \"$server\", not Cloudflare", warning = true)
                    else -> targetStatus.ok("$host is accessible")
                }
            }
        }
    }

    /**
     * Asks the executable what it is, rather than finding out at Run time. An empty field is checked
     * too — it resolves to the project's `cloudflared`, and "not on PATH" is exactly the answer worth
     * having before the connection is saved.
     *
     * Advisory in every case: PATH inside the IDE is not always PATH at spawn time, and refusing to
     * save a connection over a disagreement about that would be worse than letting it fail loudly.
     */
    private fun installExecutableCheck() {
        val field = executableField.textField
        field.addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent) = checkExecutable()
        })
        field.onChange {
            executableAlarm.cancelAllRequests()
            executableAlarm.addRequest({ checkExecutable() }, TYPING_PAUSE_MILLIS)
        }
        checkExecutable()
    }

    private fun checkExecutable() {
        val typed = executableField.text.trim()
        val resolved = typed.ifBlank { TunnelService.getInstance(project).executable }
        if (resolved == checkedExecutable) return
        checkedExecutable = resolved
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = CloudflaredBinary.probe(resolved)
            onEdt {
                if (resolved != checkedExecutable) return@onEdt
                when (result) {
                    is CloudflaredBinary.Result.Ok -> executableStatus.ok(result.version)
                    is CloudflaredBinary.Result.Unexpected -> executableStatus.problem(
                        "$resolved does not look like cloudflared — ${result.description}",
                        warning = true,
                    )

                    CloudflaredBinary.Result.Missing -> executableStatus.problem(
                        if (typed.isBlank()) "$resolved was not found on PATH" else "$resolved could not be run",
                        warning = false,
                    )
                }
            }
        }
    }

    /**
     * cloudflared exits on a bind failure, and the message is buried in Go internals, so the port is
     * worth testing while the user is still looking at the field. Done off the EDT and cached rather
     * than probed inside [doValidate], which runs on every keystroke.
     */
    private fun installBindCheck() {
        bindField.addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent) = checkBind()
        })
        // A taken port disables Save, so waiting for focus to leave would strand the user in a
        // dialog they cannot submit while they are busy fixing the very field that blocked it.
        bindField.onChange {
            bindAlarm.cancelAllRequests()
            bindAlarm.addRequest({ checkBind() }, TYPING_PAUSE_MILLIS)
        }
        checkBind()
    }

    private fun checkBind() {
        val bind = bindField.text.trim()
        if (bind == checkedBind) return
        checkedBind = bind
        bindPortTaken = false
        val port = HostProbe.socketAddress(bind)?.port
        if (bind.isBlank() || port == null) {
            bindStatus.clear()
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            val available = HostProbe.isPortAvailable(bind)
            onEdt {
                if (bind != checkedBind) return@onEdt
                bindPortTaken = !available
                // A taken port already comes back from doValidate, which outlines the field and
                // disables Save; decorating it here as well would put two balloons on one field.
                if (available) bindStatus.ok("Port :$port is available") else bindStatus.clear()
                // Re-run validation, so the OK button agrees with what the label now says.
                initValidation()
            }
        }
    }

    /** The dialog is modal, so anything less permissive than `any()` runs only after it closes. */
    private fun onEdt(action: () -> Unit) {
        ApplicationManager.getApplication().invokeLater({
            if (!isDisposed) action()
        }, ModalityState.any())
    }

    /**
     * One field's verdict. `ComponentValidator` supplies the outline and the message balloon but
     * never an icon, so the icon inside the field is added alongside it — and without a tooltip,
     * since the balloon already carries the text.
     */
    private inner class FieldStatus(private val field: ExtendableTextField, host: JComponent = field) {
        private val validator = ComponentValidator(disposable)
            // A browse button is a composite; the outline belongs around the whole control.
            .apply { if (host !== field) withOutlineProvider(ComponentValidator.CWBB_PROVIDER) }
            .installOn(host)

        /** Success has no platform state, so the green check is ours and carries its own tooltip. */
        fun ok(message: String) {
            validator.updateInfo(null)
            field.setExtensions(Extension.create(AllIcons.General.InspectionsOK, message, null))
        }

        fun problem(message: String, warning: Boolean) {
            val icon = if (warning) AllIcons.General.BalloonWarning else AllIcons.General.BalloonError
            field.setExtensions(Extension.create(icon, null, null))
            validator.updateInfo(ValidationInfo(message, field).let { if (warning) it.asWarning() else it })
        }

        fun clear() {
            field.setExtensions(emptyList())
            validator.updateInfo(null)
        }
    }

    // --- component helpers --------------------------------------------------------------------

    private fun <T : Any> textCombo(items: List<T>, selected: T): ComboBox<T> =
        ComboBox(CollectionComboBoxModel(items)).apply { item = selected }

    private fun swatchCombo(selected: ConnectionColor): ComboBox<ConnectionColor> =
        ComboBox(CollectionComboBoxModel(ConnectionColor.entries.toList())).apply {
            renderer = SimpleListCellRenderer.create { label, value, _ ->
                label.text = value?.displayName.orEmpty()
                label.icon = value?.let { ConnectionColors.swatch(it) }
            }
            item = selected
        }

    private fun JTextComponent.onChange(action: () -> Unit) {
        document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = action()
        })
    }

    private companion object {
        const val COLUMNS = 28

        /** Name and the target share this so the two rows read as one column. */
        const val FIELD_COLUMNS = 24
        const val SHORT_COLUMNS = 6
        const val MAX_RETRIES = 100
        const val CLOUDFLARE = "cloudflare"
        const val TYPING_PAUSE_MILLIS = 350
        const val SCROLL_UNIT = 16

        /** Share of the usable screen height the form may occupy before it starts scrolling. */
        const val SCREEN_BUDGET = 0.75

        /** Floor for [maxFormHeight], so a very short screen still shows a usable slice of form. */
        const val MIN_FORM_HEIGHT = 420

        /** Swallows the last pixels of measurement rounding rather than scrolling for them. */
        const val FORM_HEIGHT_SLACK = 4
        const val BIND_HINT = "localhost:5433"
        const val SERVICE_HINT = "localhost:8080"
        const val HOSTNAME_HINT = "db.example.com"
        const val GENERAL_TITLE = "General"
        const val CONNECTION_TITLE = "Connection"
        const val COMMAND_TITLE = "Startup Command"
        const val ADVANCED_TITLE = "Advanced Options"
    }
}
