// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.Locale
import javax.swing.JFileChooser
import javax.swing.JOptionPane
import javax.swing.filechooser.FileFilter

/** File types offered by choosers; [extension] has no leading dot and may contain one (keyrook.bak). */
internal enum class DialogFile(private val labelKey: String, val extension: String) {
    VAULT("credentials.vaultFilter", "keyrook"),
    BACKUP("files.backup", "keyrook.bak"),
    KEY("credentials.keyFilter", "key"),
    JSON("files.json", "json"),
    CSV("files.csv", "csv"),
    XML("files.xml", "xml"),
    PUBLIC_KEY("files.publicKey", "pub"),
    WORD_LIST("files.wordList", "txt"),
    HTML("files.html", "html"),
    ICS("files.ics", "ics");

    val label: String get() = UiText.text(labelKey)
}

internal fun hasExtension(name: String, extension: String): Boolean {
    val suffix = ".${extension.lowercase(Locale.ROOT)}"
    val lower = name.lowercase(Locale.ROOT)
    return lower.length > suffix.length && lower.endsWith(suffix)
}

/** Appends the extension unless present (case-insensitive); trailing dots are dropped so "vault." becomes "vault.keyrook". */
internal fun withExtension(path: Path, extension: String): Path {
    val name = path.fileName?.toString() ?: return path
    if (hasExtension(name, extension)) return path
    val base = name.trimEnd('.').ifEmpty { name }
    return path.resolveSibling("$base.$extension")
}

/** With the type's own filter selected the extension is added; with "All files" the typed name is kept unchanged. */
internal fun saveTarget(selected: Path, type: DialogFile, typedFilterSelected: Boolean): Path =
    if (typedFilterSelected) withExtension(selected, type.extension) else selected

/** A readable default name for an exported public key, derived only from its algorithm prefix. */
internal fun suggestedPublicKeyName(publicKey: String): String = when (publicKey.trim().substringBefore(' ')) {
    "ssh-ed25519" -> "id_ed25519.pub"
    "ssh-rsa" -> "id_rsa.pub"
    else -> "public-key.pub"
}

private class ExtensionFilter(private val type: DialogFile) : FileFilter() {
    override fun accept(file: File): Boolean = file.isDirectory || hasExtension(file.name, type.extension)
    override fun getDescription(): String = type.label
}

/**
 * Refuses existing targets inside the dialog, so the user can pick another name without starting over. This only
 * improves feedback: every writer still creates files exclusively and never replaces an existing one.
 */
private class NewFileChooser(private val type: DialogFile, private val typed: FileFilter) : JFileChooser() {
    override fun approveSelection() {
        val selected = selectedFile ?: return
        val target = saveTarget(selected.toPath(), type, fileFilter === typed)
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            JOptionPane.showMessageDialog(this, UiText.text("files.exists", target.fileName.toString()), "Keyrook",
                JOptionPane.WARNING_MESSAGE)
            return
        }
        selectedFile = target.toFile()
        super.approveSelection()
    }
}

/** Must run on the event dispatch thread. "All files" stays available for unusual names. */
internal fun chooseOpenFile(type: DialogFile): Path? {
    val filter = ExtensionFilter(type)
    val chooser = JFileChooser().apply {
        addChoosableFileFilter(filter)
        isAcceptAllFileFilterUsed = true
        fileFilter = filter
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile?.toPath() else null
}

/** Must run on the event dispatch thread. Returns a path that did not exist when the dialog was confirmed. */
internal fun chooseNewFile(type: DialogFile, suggestedName: String): Path? {
    val filter = ExtensionFilter(type)
    val chooser = NewFileChooser(type, filter).apply {
        addChoosableFileFilter(filter)
        isAcceptAllFileFilterUsed = true
        fileFilter = filter
        dialogType = JFileChooser.SAVE_DIALOG
        selectedFile = File(currentDirectory, suggestedName)
    }
    return if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile?.toPath() else null
}

/** Must run on the event dispatch thread. */
internal fun chooseFolder(): Path? {
    val chooser = JFileChooser().apply { fileSelectionMode = JFileChooser.DIRECTORIES_ONLY }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile?.toPath() else null
}
