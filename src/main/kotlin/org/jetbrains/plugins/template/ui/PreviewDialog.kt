package org.jetbrains.plugins.template.ui

import com.intellij.diff.DiffManager
import com.intellij.diff.DiffRequestPanel
import com.intellij.diff.contents.DiffContent
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.util.ui.JBUI
import javax.swing.JComponent

/**
 * Modal dialog that presents an IntelliJ-native side-by-side diff between the current
 * file content and the AI-proposed version, identical in look and feel to a VCS diff review.
 *
 * Clicking **Accept** signals approval; closing the dialog or clicking **Reject** leaves the
 * document untouched. Callers check [showAndGet] to determine the outcome.
 *
 * @param currentContent  Diff content representing the file as it currently stands.
 * @param proposedContent Diff content representing the file with the AI suggestion applied.
 */
class DiffReviewDialog(
    project: Project,
    currentContent: DiffContent,
    proposedContent: DiffContent,
) : DialogWrapper(project, true) {

    private val panel: DiffRequestPanel = DiffManager.getInstance()
        .createRequestPanel(project, disposable, null)

    init {
        val request = SimpleDiffRequest(
            "AI Suggestion – Review Changes",
            currentContent,
            proposedContent,
            "Current",
            "Proposed",
        )
        panel.setRequest(request)
        title = "AI Suggestion – Review Changes"
        setOKButtonText("Accept")
        setCancelButtonText("Reject")
        init()
    }

    override fun createCenterPanel(): JComponent = panel.component.also {
        it.preferredSize = JBUI.size(1000, 650)
    }
}
