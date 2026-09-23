// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import java.net.Inet6Address

/** Builds text only. Strict operands are quoted for PowerShell and POSIX shells, never executed. */
internal object ConnectionCommands {
    fun ssh(host: String, port: Int, username: String): String {
        val destination = checkedHost(host)
        checkPortAndUser(port, username)
        return "ssh -p $port -l '$username' -- '$destination'"
    }

    fun sftp(host: String, port: Int, username: String): String {
        val checked = checkedHost(host)
        checkPortAndUser(port, username)
        val destination = if (':' in checked) "[$checked]" else checked
        return "sftp -P $port -- '$username@$destination'"
    }

    private fun checkPortAndUser(port: Int, username: String) {
        require(port in 1..65535) { "Invalid connection port" }
        require(Regex("[A-Za-z0-9_][A-Za-z0-9_.-]{0,63}").matches(username)) { "Unsupported login name" }
    }

    private fun checkedHost(input: String): String {
        require(input.length in 1..253) { "Invalid connection host" }
        if (':' in input) {
            val host = if (input.startsWith('[') && input.endsWith(']')) input.substring(1, input.lastIndex) else input
            require(Regex("[0-9A-Fa-f:.]+").matches(host)) { "Invalid IPv6 host" }
            // Literal parsing never performs a DNS lookup. Scoped/interface-specific addresses are refused.
            Inet6Address.ofLiteral(host)
            return host
        }
        if (input.all { it in '0'..'9' || it == '.' }) {
            val octets = input.split('.')
            require(octets.size == 4 && octets.all {
                it.isNotEmpty() && it.length <= 3 && (it.length == 1 || it[0] != '0') && it.toInt() in 0..255
            }) { "Invalid IPv4 host" }
        } else {
            val host = input.removeSuffix(".")
            require(host.split('.').all { label ->
                label.length in 1..63 && Regex("[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?").matches(label)
            }) { "Invalid connection host" }
        }
        return input
    }
}
