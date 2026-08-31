package com.example.network

import java.net.ServerSocket

object PortSelector {

    fun findAvailablePort(preferredPort: Int = 8080, maxAttempts: Int = 20): Int {
        for (offset in 0 until maxAttempts) {
            val port = preferredPort + offset
            if (isPortAvailable(port)) {
                return port
            }
        }
        // Fallback: system auto-assigns any free port
        return try {
            ServerSocket(0).use { it.localPort }
        } catch (e: Exception) {
            preferredPort
        }
    }

    private fun isPortAvailable(port: Int): Boolean {
        return try {
            ServerSocket(port).use {
                it.reuseAddress = true
                true
            }
        } catch (e: Exception) {
            false
        }
    }
}
