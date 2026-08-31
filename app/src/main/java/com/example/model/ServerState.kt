package com.example.model

enum class ServerState {
    STARTING,
    ACTIVE,
    STOPPING,
    STOPPED,
    ERROR,
    NO_WIFI
}

data class ServerInfo(
    val state: ServerState = ServerState.STOPPED,
    val ipAddress: String? = null,
    val port: Int = 8080,
    val errorMessage: String? = null
) {
    val localUrl: String?
        get() = if (state == ServerState.ACTIVE && !ipAddress.isNullOrBlank()) {
            "http://$ipAddress:$port"
        } else {
            null
        }
}
