package com.epher.app.data.model

enum class ConnectionState(val label: String) {
    Connecting("Connecting"),
    Connected("Connected"),
    Reconnecting("Reconnecting"),
    Backgrounded("Paused"),
    Expired("Expired"),
}
