package org.jetbrains.plugins.template.actions

import com.intellij.diff.DiffContentFactory
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import org.jetbrains.plugins.template.api.OpenRouterClient
import org.jetbrains.plugins.template.settings.TodoExpanderSettings
import org.jetbrains.plugins.template.ui.DiffReviewDialog

private const val CONTEXT_LINES = 5
private const val MAX_STUB_SCAN_LINES = 15
private const val NOTIFICATION_GROUP = "TODO Expander"

/**
 * Patterns that identify a line as a stub or placeholder that the AI suggestion should replace.
 * Blank lines are handled separately in [TodoExpanderAction.findStubRange]: once at least one
 * stub has been detected they are treated as part of the block (extending [lastStub]), so no
 * orphaned blank lines are left beneath the replacement.
 */
private val STUB_PATTERNS = listOf(
    // JVM — throw NotImplemented / Unsupported
    Regex("""^\s*throw\s+new\s+\w*[Nn]ot[Ii]mplemented\w*.*"""),   // Java:   throw new NotImplementedException(...)
    Regex("""^\s*throw\s+\w*[Nn]ot[Ii]mplemented\w*.*"""),          // Kotlin: throw NotImplementedError(...)
    Regex("""^\s*throw\s+\w*[Uu]nsupported\w*.*"""),                 //        throw UnsupportedOperationException(...)
    // Kotlin
    Regex("""^\s*TODO\(\s*(".*?")?\s*\).*"""),                       // TODO() or TODO("message")
    Regex("""^\s*\?\?\?\s*[;]?\s*$"""),                              // ??? (Kotlin/Scala not-yet-implemented)
    // Python
    Regex("""^\s*raise\s+NotImplementedError.*"""),                  // raise NotImplementedError
    Regex("""^\s*pass\s*$"""),                                       // bare pass
    Regex("""^\s*\.\.\.\s*$"""),                                     // ... (ellipsis body)
    // Generic trivial return / yield
    Regex("""^\s*return\s*(null|undefined|None|0|false)?\s*[;]?\s*$"""),
    // Empty / incomplete variable assignment — e.g. `mostCommonLetters=` or `result =`
    // Matches: identifier (with optional dots/brackets) followed by `=` with nothing after.
    Regex("""^\s*[\w][\w.\[\]]*\s*=\s*$"""),
    // Bare echo or echo with an empty string — shell placeholder output
    Regex("""^\s*echo(\s+["']{0,2})?\s*$"""),
    // Bare print() call with no arguments — Python/JS placeholder output
    Regex("""^\s*print\s*\(\s*\)\s*[;]?\s*$"""),
    Regex("""^\s*console\s*\.\s*log\s*\(\s*\)\s*[;]?\s*$"""),
)

/**
 * An [AnAction] that expands a `TODO` comment under the caret into a concrete AI-generated
 * code implementation.
 *
 * The action is available via **right-click → Expand TODO with AI** and
 * **Tools → Expand TODO with AI**. It validates that the caret is on a TODO line
 * and that an OpenRouter API key is configured, then dispatches a background HTTP request.
 * Once the response arrives, a [DiffReviewDialog] lets the user accept or reject the suggestion
 * before it is written into the document.
 */
class TodoExpanderAction : AnAction("Expand TODO with AI") {

    /**
     * Validates preconditions, collects editor context on the EDT, and dispatches a
     * [Task.Backgroundable] that calls the OpenRouter API. On completion, shows a [DiffReviewDialog]
     * and — if the user accepts — applies the change as a single undoable [WriteCommandAction].
     */
    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val project = e.project ?: return
        val document = editor.document

        val caretLine = document.getLineNumber(editor.caretModel.offset)
        val todoLineText = document.getText(
            TextRange(document.getLineStartOffset(caretLine), document.getLineEndOffset(caretLine))
        )

        if (!todoLineText.contains("TODO", ignoreCase = true)) {
            notify(project, "Place the cursor on a line containing a // TODO comment.", NotificationType.INFORMATION)
            return
        }

        val apiKey = TodoExpanderSettings.getApiKey()
        if (apiKey.isNullOrBlank()) {
            notify(project, "No OpenRouter API key configured. Go to Settings → Tools → TODO Expander to add your key.", NotificationType.ERROR)
            return
        }

        // All EDT-sensitive reads happen here before the background thread starts.
        val indentation = todoLineText.takeWhile { it == ' ' || it == '\t' }
        val contextCode = extractContext(document, caretLine)
        val language = detectLanguage(e, document)

        // Track the TODO line's position with a marker so that document edits made while the
        // API call is in flight (user typing, auto-formatting, etc.) don't invalidate caretLine.
        val todoMarker: RangeMarker = document.createRangeMarker(
            document.getLineStartOffset(caretLine),
            document.getLineEndOffset(caretLine),
        )

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "AI is thinking…", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = "Waiting for AI response…"
                val generatedCode = OpenRouterClient.complete(apiKey, todoLineText.trim(), contextCode, language)
                val indentedCode = generatedCode.trim().lines().joinToString("\n") { "$indentation$it" }

                ApplicationManager.getApplication().invokeLater {
                    // Resolve the current TODO line from the marker; fall back to the original
                    // captured line only if the marker was somehow invalidated.
                    val currentTodoLine = if (todoMarker.isValid)
                        document.getLineNumber(todoMarker.startOffset)
                    else
                        caretLine
                    todoMarker.dispose()

                    val stubRange = findStubRange(document, currentTodoLine)

                    // Compute the replacement bounds once, on the EDT, before the modal dialog
                    // opens. Because the dialog is modal, the document cannot change while it is
                    // open, so these offsets are still valid when the user clicks Accept.
                    val replaceStart: Int
                    val replaceEnd: Int
                    val replacement: String
                    if (stubRange != null) {
                        replaceStart = document.getLineStartOffset(stubRange.first)
                        replaceEnd = if (stubRange.last + 1 < document.lineCount)
                            document.getLineStartOffset(stubRange.last + 1)
                        else
                            document.getLineEndOffset(stubRange.last)
                        replacement = indentedCode + "\n"
                    } else {
                        replaceStart = document.getLineEndOffset(currentTodoLine)
                        replaceEnd = replaceStart
                        replacement = "\n$indentedCode"
                    }

                    // Build the proposed full-file text so the diff viewer can highlight exactly
                    // what will change, just like a VCS diff.
                    val currentText = document.text
                    val proposedText = currentText.substring(0, replaceStart) +
                            replacement +
                            currentText.substring(replaceEnd)

                    val contentFactory = DiffContentFactory.getInstance()
                    val fileType = FileDocumentManager.getInstance().getFile(document)?.fileType
                    val leftContent = if (fileType != null)
                        contentFactory.create(currentText, fileType)
                    else
                        contentFactory.create(currentText)
                    val rightContent = if (fileType != null)
                        contentFactory.create(proposedText, fileType)
                    else
                        contentFactory.create(proposedText)

                    val dialog = DiffReviewDialog(project, leftContent, rightContent)
                    if (dialog.showAndGet()) {
                        WriteCommandAction.runWriteCommandAction(project, "Expand TODO with AI", null, Runnable {
                            document.replaceString(replaceStart, replaceEnd, replacement)
                        })
                    }
                }
            }
        })
    }

    /**
     * Keeps the action visible only when an editor is focused.
     */
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.getData(CommonDataKeys.EDITOR) != null
    }

    /**
     * Returns a human-readable language name for the file currently open in the editor.
     *
     * PSI language is preferred (e.g. "Kotlin") over the raw file-type name because PSI
     * correctly distinguishes dialects (Kotlin Script, JSX, etc.). Falls back to the
     * file-type name, and returns an empty string for plain-text files so the prompt
     * remains language-neutral.
     */
    private fun detectLanguage(e: AnActionEvent, document: Document): String {
        val psiLanguage = e.getData(CommonDataKeys.PSI_FILE)?.language?.displayName
        if (!psiLanguage.isNullOrBlank() && psiLanguage != "TEXT") return psiLanguage

        val typeName = FileDocumentManager.getInstance().getFile(document)?.fileType?.name
        return if (!typeName.isNullOrBlank() && typeName != "PLAIN_TEXT") typeName else ""
    }

    /**
     * Scans up to [MAX_STUB_SCAN_LINES] lines immediately below [todoLine] for stub or
     * placeholder content, using [STUB_PATTERNS].
     *
     * Blank lines before the first stub are transparent (the scan continues but the range does
     * not start). Once a stub has been detected, blank lines are treated as part of the stub
     * block: they extend [lastStub] so that trailing blank lines are consumed by the replacement
     * and not left as orphaned whitespace beneath the generated code. A non-blank, non-stub line
     * always ends the scan.
     *
     * @return The line-index range of the detected stub block, or null if no stub was found
     *         (in which case the caller should insert rather than replace).
     */
    private fun findStubRange(document: Document, todoLine: Int): IntRange? {
        val lastDocLine = document.lineCount - 1
        var firstStub = -1
        var lastStub = -1

        for (lineIdx in (todoLine + 1)..minOf(todoLine + MAX_STUB_SCAN_LINES, lastDocLine)) {
            val lineText = document.getText(
                TextRange(document.getLineStartOffset(lineIdx), document.getLineEndOffset(lineIdx))
            )
            when {
                STUB_PATTERNS.any { it.matches(lineText) } -> {
                    if (firstStub == -1) firstStub = lineIdx
                    lastStub = lineIdx
                }
                lineText.isBlank() -> {
                    // Before the first stub: keep scanning but don't anchor the block start.
                    // After the first stub: extend the range so the blank is consumed too.
                    if (firstStub != -1) lastStub = lineIdx
                }
                else -> break
            }
        }

        return if (firstStub == -1) null else firstStub..lastStub
    }

    /**
     * Returns a single string containing up to [CONTEXT_LINES] lines above and below
     * [caretLine], used to give the AI model surrounding code context.
     */
    private fun extractContext(document: Document, caretLine: Int): String {
        val firstLine = maxOf(0, caretLine - CONTEXT_LINES)
        val lastLine = minOf(document.lineCount - 1, caretLine + CONTEXT_LINES)
        return document.getText(
            TextRange(document.getLineStartOffset(firstLine), document.getLineEndOffset(lastLine))
        )
    }

    private fun notify(project: Project, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(message, type)
            .notify(project)
    }
}
