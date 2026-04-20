package org.jetbrains.plugins.template.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.TextRange
import org.jetbrains.plugins.template.api.OpenRouterClient

class TodoExpanderAction : AnAction("Expand TODO with AI") {

    companion object {
        private const val CONTEXT_LINES = 5
    }

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val project = e.project ?: return
        val document = editor.document

        val caretLine = document.getLineNumber(editor.caretModel.offset)
        val todoLine = document.getText(
            TextRange(document.getLineStartOffset(caretLine), document.getLineEndOffset(caretLine))
        )

        if (!todoLine.contains("TODO", ignoreCase = true)) {
            Messages.showInfoMessage(
                project,
                "Place the cursor on a line containing a // TODO comment.",
                "No TODO Found"
            )
            return
        }

        val apiKey = System.getenv("OPENROUTER_API_KEY")
        if (apiKey.isNullOrBlank()) {
            Messages.showErrorDialog(
                project,
                "The OPENROUTER_API_KEY environment variable is not set.\nPlease set it and restart the IDE.",
                "API Key Missing"
            )
            return
        }

        // Capture both values on the EDT before dispatching to the background thread
        val indentation = todoLine.takeWhile { it == ' ' || it == '\t' }
        val contextCode = extractContext(document, caretLine)

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Expanding TODO with AI…", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Calling OpenRouter API…"
                val generatedCode = OpenRouterClient.complete(apiKey, todoLine.trim(), contextCode)
                val indentedCode = generatedCode.trim().lines().joinToString("\n") { "$indentation$it" }

                ApplicationManager.getApplication().invokeLater {
                    WriteCommandAction.runWriteCommandAction(project, "Expand TODO with AI", null, Runnable {
                        document.insertString(document.getLineEndOffset(caretLine), "\n$indentedCode")
                    })
                }
            }
        })
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.getData(CommonDataKeys.EDITOR) != null
    }

    private fun extractContext(document: Document, caretLine: Int): String {
        val firstLine = maxOf(0, caretLine - CONTEXT_LINES)
        val lastLine = minOf(document.lineCount - 1, caretLine + CONTEXT_LINES)
        return (firstLine..lastLine).joinToString("\n") { line ->
            document.getText(TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line)))
        }
    }
}
