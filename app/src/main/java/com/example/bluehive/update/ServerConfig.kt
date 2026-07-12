// update/ServerConfig.kt
package com.example.bluehive.update

/**
 * Single source of truth for BlueHive's update-server address.
 *
 * IMPORTANT: BlueHive is a SEPARATE app from its host. It has its OWN
 * ServerConfig with its OWN [HOST] — the two apps' configs are independent and
 * neither controls the other. If the update server (a laptop running Docker)
 * changes LAN IP, edit [HOST] here, rebuild BlueHive, and install.
 *
 * This governs only BlueHive's self-update. BlueHive's API / platform URLs remain
 * in BuildConfig (their existing per-build-type mechanism) — out of scope here.
 */
object ServerConfig {

    // ─────────────────────────────────────────────────────────────────────────
    //  EDIT THIS when the update server's IP changes:
    const val HOST = "192.168.1.136"
    // ─────────────────────────────────────────────────────────────────────────

    private const val PORT_UPDATE = 8090

    val updateBaseUrl: String get() = "http://$HOST:$PORT_UPDATE"
}
