package com.kuzey.notificationkiller

import android.content.Context
import androidx.annotation.Keep
import java.io.BufferedReader
import java.io.InputStreamReader

class PrivilegedService : IRemoteService.Stub {

    constructor()

    @Keep
    constructor(context: Context)

    override fun runCommand(command: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val stdout = BufferedReader(InputStreamReader(process.inputStream)).readText()
            val stderr = BufferedReader(InputStreamReader(process.errorStream)).readText()
            process.waitFor()

            buildString {
                if (stdout.isNotBlank()) append(stdout.trim())
                if (stderr.isNotBlank()) {
                    if (isNotEmpty()) append("\n")
                    append("ERR: ").append(stderr.trim())
                }
                if (isEmpty()) append("OK")
            }
        } catch (e: Exception) {
            "ERR: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    override fun destroy() {
        System.exit(0)
    }
}
