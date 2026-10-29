package com.example.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString

/**
 * Копирование текста в буфер обмена с отметкой, какую запись скопировали.
 *
 * Без отметки нажатие на «копировать» неотличимо от молчания системы, а проверить это глазами
 * на длинном списке нельзя. Ключ (id сообщения или URI пира) остаётся в состоянии до
 * следующего копирования, поэтому подпись вид ровно у одной строки.
 */
@Composable
fun rememberCopyToClipboard(): Pair<(String, String) -> Unit, MutableState<String?>> {
    val clipboard = LocalClipboardManager.current
    val copiedKey = remember { mutableStateOf<String?>(null) }
    val copy: (String, String) -> Unit = { key, text ->
        if (text.isNotBlank()) {
            clipboard.setText(AnnotatedString(text))
            copiedKey.value = key
        }
    }
    return copy to copiedKey
}
