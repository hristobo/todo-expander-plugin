package org.jetbrains.plugins.template.settings

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JPasswordField

/**
 * Settings page registered under **Settings → Tools → TODO Expander**.
 *
 * Provides a password field for entering the OpenRouter API key, which is stored
 * securely via [TodoExpanderSettings] (backed by the platform [com.intellij.ide.passwordSafe.PasswordSafe]).
 */
class TodoExpanderConfigurable : Configurable {

    // Nullable because IntelliJ calls disposeUIResources() to release Swing components between
    // settings page visits. The platform guarantees createComponent() is called before apply()
    // or reset(), so these methods always find a non-null field in practice.
    private var apiKeyField: JPasswordField? = null

    override fun getDisplayName(): String = "TODO Expander"

    /**
     * Builds the settings form: a labeled password field followed by a themed hint.
     * Wrapped in a north-anchored panel to prevent the form from stretching vertically.
     */
    override fun createComponent(): JComponent {
        val field = JPasswordField(42)
        apiKeyField = field

        val form = JPanel(GridBagLayout())
        val c = GridBagConstraints().apply { anchor = GridBagConstraints.WEST }

        c.apply {
            gridx = 0
            gridy = 0
            insets = Insets(0, 0, 4, 8)
        }
        form.add(JBLabel("OpenRouter API key:"), c)

        c.apply {
            gridx = 1
            fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0
        }
        form.add(field, c)

        c.apply {
            gridx = 1
            gridy = 1
            fill = GridBagConstraints.NONE
            weightx = 0.0
            insets = Insets(2, 0, 0, 0)
        }
        form.add(
            JBLabel("Obtain your key at openrouter.ai").apply {
                foreground = UIUtil.getContextHelpForeground()
                font = UIUtil.getLabelFont(UIUtil.FontSize.SMALL)
            },
            c,
        )

        reset()

        return JPanel(BorderLayout()).apply {
            add(form, BorderLayout.NORTH)
        }
    }

    /**
     * Returns `true` if the field content differs from the currently stored API key.
     * Comparison is exact — trailing whitespace is significant.
     */
    override fun isModified(): Boolean {
        val stored = TodoExpanderSettings.getApiKey() ?: ""
        val entered = String(apiKeyField?.password ?: charArrayOf())
        return entered != stored
    }

    /**
     * Saves the current field value to the platform keychain via [TodoExpanderSettings].
     * A blank value clears the stored credential.
     */
    override fun apply() {
        val key = String(apiKeyField?.password ?: charArrayOf())
        TodoExpanderSettings.setApiKey(key.ifBlank { null })
    }

    /**
     * Reloads the stored API key into the field, discarding any unsaved changes.
     */
    override fun reset() {
        apiKeyField?.text = TodoExpanderSettings.getApiKey() ?: ""
    }

    /**
     * Nulls the field reference to allow garbage collection of the Swing component
     * between settings page visits.
     */
    override fun disposeUIResources() {
        apiKeyField = null
    }
}
