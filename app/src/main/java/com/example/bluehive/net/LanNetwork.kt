package com.example.bluehive.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory

/**
 * Routes BACKEND traffic to the LAN even while a full-tunnel VPN is up, while
 * leaving STREAMING traffic inside the tunnel.
 *
 * WHY BOTH AT ONCE — this is the whole point
 * ------------------------------------------
 * BlueHive is the one app that genuinely needs a split. On a CUJO-filtered line
 * `vidfast.vc` is only reachable through a VPN, so extraction and playback MUST
 * stay in the tunnel. But the host token, profiles and watch history live on
 * 192.168.1.136, and Android's VPN support routes the WHOLE address space into
 * the tunnel — where a machine on the user's own LAN does not exist.
 *
 * Measured in OGD on 2026-08-01, same failure, same cause:
 *
 *     RemoteAddonStore: fetch error: failed to connect to /192.168.1.136 (port 9000)
 *                       from /10.2.0.2 (port 39002) after 8000ms
 *
 * `10.2.0.2` is the tunnel address. In BlueHive the same break shows up as the
 * 30-second blank launch and the "Set up BlueHive" toast — the signature of an
 * unreachable platform backend, which looks identical to Docker being down.
 *
 * Per-app split tunnelling in the VPN client cannot express this: it is ONE app
 * needing the tunnel for one destination and the LAN for another. That is a
 * per-ROUTE decision. This makes it per-SOCKET, which is finer still and needs
 * no cooperation from whichever VPN the user happens to run.
 *
 * WHAT MUST NEVER USE THIS
 * ------------------------
 * ExoPlayerConfig, HlsSubtitles, the trailer downloaders and the Miruro
 * extractor all fetch from public hosts. Pinning those would drop streaming out
 * of the tunnel and re-break the very thing the VPN was turned on to fix. Only
 * clients aimed at 192.168.1.136 get [pinToLan] — traffic that never leaves the
 * building, where bypassing the VPN costs no privacy at all.
 *
 * Falls back to the platform default when no physical network is found, so with
 * the VPN OFF behaviour is byte-for-byte what it was.
 */
object LanNetwork {

    private const val TAG = "LanNetwork"

    @Volatile private var lan: Network? = null
    @Volatile private var started = false

    /** Latched once the platform refuses a bind — see [LanSocketFactory.create]. */
    @Volatile private var bypassDenied = false

    /** Call once from Application.onCreate, before any networking. */
    fun init(context: Context) {
        if (started) return
        started = true
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return

        // NOT_VPN is implied by the builder, which is what makes this resolve to
        // the physical interface rather than the tunnel sitting on top of it.
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()

        runCatching {
            cm.registerNetworkCallback(req, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    lan = network
                    // Re-evaluate: a VPN that denied bypass may since have gone.
                    bypassDenied = false
                    Log.d(TAG, "physical network available → $network")
                }
                override fun onLost(network: Network) {
                    if (lan == network) {
                        lan = null
                        Log.d(TAG, "physical network lost → falling back to default routing")
                    }
                }
            })
        }.onFailure { Log.w(TAG, "could not register network callback: ${it.message}") }
    }

    /**
     * Resolved per socket, not captured once: the VPN can come up or drop, and
     * Wi-Fi can reconnect, long after ApiClient's singletons are built. A
     * captured Network would be stale exactly when it mattered.
     */
    fun socketFactory(): SocketFactory = LanSocketFactory

    private object LanSocketFactory : SocketFactory() {

        /**
         * Binds to the physical network, falling back to default routing when the
         * platform refuses.
         *
         * MEASURED 2026-08-01 with ProtonVPN connected:
         *
         *     Failed to load profiles: Binding socket to network 100 failed:
         *     EPERM (Operation not permitted)
         *
         * Android does NOT let an app escape an active VPN. A VpnService may opt
         * in via Builder.allowBypass(); a privacy VPN reasonably does not, and
         * netd then denies the bind outright. There is no app-side way around it
         * short of CONNECTIVITY_USE_RESTRICTED_NETWORKS, which is system-only.
         *
         * So this must never be load-bearing. On the first refusal we latch and
         * revert to default routing — restoring exactly the pre-existing
         * behaviour (the request enters the tunnel and times out) rather than
         * converting it into an instant hard failure. The binding still works,
         * and still helps, on setups that permit it: a VPN that allows bypass, or
         * a WireGuard config whose AllowedIPs simply excludes the LAN so no
         * bypass is needed at all.
         */
        private fun create(make: (SocketFactory) -> Socket): Socket {
            if (!bypassDenied) {
                lan?.let { n ->
                    try {
                        return make(n.socketFactory)
                    } catch (e: Exception) {
                        bypassDenied = true
                        Log.w(TAG, "VPN forbids LAN bypass (${e.message}) — " +
                                "reverting to default routing")
                    }
                }
            }
            return make(getDefault())
        }

        override fun createSocket(): Socket = create { it.createSocket() }
        override fun createSocket(host: String?, port: Int): Socket =
            create { it.createSocket(host, port) }
        override fun createSocket(host: String?, port: Int, lh: InetAddress?, lp: Int): Socket =
            create { it.createSocket(host, port, lh, lp) }
        override fun createSocket(host: InetAddress?, port: Int): Socket =
            create { it.createSocket(host, port) }
        override fun createSocket(host: InetAddress?, port: Int, lh: InetAddress?, lp: Int): Socket =
            create { it.createSocket(host, port, lh, lp) }
    }
}

/**
 * True when [url] points at an address that genuinely cannot leave the building:
 * RFC1918 space, loopback, link-local, or a .local/.internal name.
 *
 * This is the test the whole bypass rests on. [pinToLan]'s justification is that
 * skipping the tunnel "costs no privacy at all" because the traffic never leaves
 * the LAN — true for 192.168.1.136, and FALSE for a public backend.
 */
fun isPrivateBackend(url: String?): Boolean {
    val host = runCatching { java.net.URI(url ?: return false).host }
        .getOrNull()?.lowercase() ?: return false
    if (host == "localhost" || host.endsWith(".local") || host.endsWith(".internal")) return true

    // NEVER let a hostname reach InetAddress.getByName here. That call performs a
    // BLOCKING DNS LOOKUP, and these run while an OkHttpClient is being built —
    // on whatever thread touches the object first, which for a `val` in an object
    // is the main thread. A lookup there is a frozen UI at best and
    // NetworkOnMainThreadException at worst.
    //
    // A "does it look numeric" character-class test is NOT sufficient: a real
    // domain made only of hex letters and dots (cafe.dec, add.ed) passes it and
    // gets resolved. So IPv4 is matched STRUCTURALLY — four dotted octets, each
    // 0-255 — and IPv6 is recognised by requiring a colon, which no hostname in
    // a URI host component can contain once the port has been stripped.
    val isIpv4 = host.matches(
        Regex("""^((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)$""")
    )
    val isIpv6 = host.contains(':')
    if (!isIpv4 && !isIpv6) return false          // a hostname: treat as public

    return runCatching {
        // Safe now: a literal resolves without touching the network.
        val addr = java.net.InetAddress.getByName(host.trim('[', ']'))
        addr.isLoopbackAddress || addr.isSiteLocalAddress || addr.isLinkLocalAddress
    }.getOrDefault(false)
}

/**
 * Marks an OkHttp client as talking to the LAN backend, so its sockets skip any
 * active VPN tunnel — but ONLY when [url] really is a LAN address.
 *
 * WHY THE URL ARGUMENT EXISTS
 *
 * The unconditional version of this was correct while the backend lived on
 * 192.168.1.136 and could not be reached from outside the house. Its own comment
 * says bypassing the VPN "costs no privacy at all" for that reason.
 *
 * That premise dies the moment the backend is a public domain. The mechanism
 * does not change — it still forces these sockets onto the physical interface —
 * but the consequence inverts: every profile, watch-history and pairing call
 * would leave the user's tunnel, carrying their real IP to the server and to
 * anyone watching the link, for users who switched a VPN on precisely to prevent
 * that. Worse, it would apply only to OUR traffic, so the leak is specific and
 * attributable rather than lost in a crowd.
 *
 * Deriving it from the URL means the behaviour follows the deployment with no
 * flag to remember: LAN build keeps the bypass, public build silently stops
 * using it and rides the tunnel like every other app. Streaming clients still
 * must never call this at all — see the note on [LanNetwork].
 */
fun OkHttpClient.Builder.pinToLan(url: String?): OkHttpClient.Builder =
    if (isPrivateBackend(url)) socketFactory(LanNetwork.socketFactory()) else this
