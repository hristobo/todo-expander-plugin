package org.jetbrains.plugins.template.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe

/**
 * Thin wrapper around IntelliJ's [PasswordSafe] for storing the OpenRouter API key.
 *
 * Credentials are stored in the platform keychain (Keychain on macOS, libsecret on Linux,
 * Windows Credential Manager on Windows) and are never written to disk in plaintext.
 */
object TodoExpanderSettings {

    private const val CREDENTIAL_SERVICE = "TODO Expander — OpenRouter API Key"

    // Immutable value object — initialised once and reused across all get/set calls.
    private val ATTRIBUTES = CredentialAttributes(CREDENTIAL_SERVICE)

    /**
     * Returns the stored OpenRouter API key, or `null` if none has been configured.
     */
    fun getApiKey(): String? = PasswordSafe.instance.getPassword(ATTRIBUTES)

    /**
     * Persists [apiKey] in the platform keychain.
     * Passing `null` or a blank string removes any previously stored credential.
     */
    fun setApiKey(apiKey: String?) {
        PasswordSafe.instance.set(
            ATTRIBUTES,
            if (apiKey.isNullOrBlank()) null else Credentials("", apiKey),
        )
    }
}
