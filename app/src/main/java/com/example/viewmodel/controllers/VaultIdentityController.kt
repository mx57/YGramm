// File: app/src/main/java/com/example/viewmodel/controllers/VaultIdentityController.kt
package com.example.viewmodel.controllers

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The seed phrase reveal flag. Identity actions (regenerate, sync, pair) live in the ViewModel:
 * duplicated copies of them here had no callers and could never be reached from the UI.
 */
class VaultIdentityController {

    private val _isSeedPhraseRevealed = MutableStateFlow(false)
    val isSeedPhraseRevealed: StateFlow<Boolean> = _isSeedPhraseRevealed.asStateFlow()

    fun toggleRevealSeedPhrase() {
        _isSeedPhraseRevealed.value = !_isSeedPhraseRevealed.value
    }
}
