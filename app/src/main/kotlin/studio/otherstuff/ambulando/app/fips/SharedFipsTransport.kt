package studio.otherstuff.ambulando.app.fips

import android.app.PendingIntent
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.*
import android.os.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import studio.otherstuff.ambulando.core.nostr.Sha256Hex
import studio.otherstuff.ambulando.core.transport.*
import studio.otherstuff.ambulando.core.transport.LocalStages.Stage
import studio.otherstuff.ambulando.core.transport.LocalStages.Result
import java.net.Proxy
import java.net.Socket
import java.net.InetSocketAddress
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.net.SocketFactory

class FipsConsentRequired internal constructor(
    private val handoff: (isResumed: () -> Boolean) -> PendingIntent?,
    private val abandon: () -> Unit,
) : Exception(ConnectionIssue.CONSENT.userMessage), SafeConnectionIssue {
    override val connectionIssue = ConnectionIssue.CONSENT
    fun takeConsent(isResumed: () -> Boolean = { true }): PendingIntent? = handoff(isResumed)
    fun abandonConsent() = abandon()
}

/** Consumes NBrowser's enrolled shared-VPN grants. Never creates a VPN, node, key or global binding. */
class SharedFipsTransport(private val context: Context, ordinaryBase: OkHttpClient = OkHttpClient()) : HttpTransport, EventStreamTransport, AutoCloseable {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())
    private val releaseJob = SupervisorJob()
    private val releaseScope = CoroutineScope(releaseJob + Dispatchers.IO)
    private val leaseLock = Mutex()
    private val leases = ConcurrentHashMap<String, Lease>()
    private data class PendingRequest(val uri: Uri, val id: String, val consent: PendingConsent?)
    private val pending = ConcurrentHashMap<String, PendingRequest>()
    private var pendingEpoch = 0L
    private val originEpoch = HashMap<String, Long>()
    @Synchronized private fun fence(origin: String) = pendingEpoch to (originEpoch[origin] ?: 0L)
    @Synchronized private fun storePending(origin: String, fence: Pair<Long, Long>, value: PendingRequest) {
        if (closing || fence(origin) != fence) {
            releaseScope.launch { runCatching { call(value.uri, "release", value.id) } }
            unavailable("provider binding invalidated")
        }
        pending[origin] = value
    }
    @Synchronized private fun clearPending(origin: String): PendingRequest? {
        originEpoch[origin] = (originEpoch[origin] ?: 0L) + 1
        return pending.remove(origin)
    }
    private fun releasePending(value: PendingRequest) { releaseScope.launch { runCatching { call(value.uri, "release", value.id) } } }
    private fun consentProvider(uri: Uri): ConsentProvider {
        if (provider() != uri) unavailable("provider binding changed")
        val info = context.packageManager.resolveContentProvider(uri.authority!!, 0) ?: unavailable("provider missing")
        val signer = context.packageManager.getPackageInfo(info.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            .signingInfo?.apkContentsSigners?.singleOrNull() ?: unavailable("provider certificate missing")
        val identity = ConsentProvider(uri, info.packageName, Sha256Hex.of(signer.toByteArray()), info.applicationInfo.uid, android.os.Process.myUid())
        if (provider() != uri) unavailable("provider changed")
        return identity
    }
    private fun requireConsent(origin: String, value: PendingRequest): Nothing {
        val consent = value.consent ?: unavailable("native endpoint approval is still pending; return from Nostr Browser and confirm again")
        throw FipsConsentRequired(handoff = { isResumed ->
            try {
                val current = consentProvider(value.uri)
                // API30 has no public query. Only the exact enrolled provider contract is accepted there;
                // its audited creation uses FLAG_IMMUTABLE | FLAG_ONE_SHOT unconditionally.
                val immutable = Build.VERSION.SDK_INT < 31 || consent.intent.isImmutable
                synchronized(this) {
                    if (closing || pending[origin] !== value) unavailable("provider consent no longer current")
                    if (isResumed()) consent.take(current, origin, value.id, immutable) else null
                }
            } catch (e: Exception) {
                synchronized(this) { if (pending.remove(origin, value)) { clearPending(origin); releasePending(value) } }
                throw SharedConnectionException((e as? SafeConnectionIssue)?.connectionIssue ?: ConnectionIssue.CONSENT)
            }
        }, abandon = {
            synchronized(this) { if (pending.remove(origin, value)) { clearPending(origin); releasePending(value) } }
        })
    }
    @Volatile private var closing = false
    private val http = OkHttpTransport(base = ordinaryBase)
    private val streams = OkHttpEventStreamTransport(base = ordinaryBase)
    private val callback = Messenger(Handler(Looper.getMainLooper()) { message ->
        val b = message.data
        if (message.what == 1 && b.getInt("version") == 1 && b.getString("status") == "revoked") {
            val id = b.getString("requestId")
            // Fence a request reply racing this callback before its pending ID has been installed.
            synchronized(this) { pendingEpoch++ }
            leases.values.filter { it.id == id }.forEach { it.close() }
            pending.entries.filter { it.value.id == id }.forEach { releaseOrigin(it.key) }
        }
        true
    })
    private fun unavailable(detail: String): Nothing {
        val issue = when {
            detail.startsWith("Both trusted Nostr Browser providers are installed.") -> ConnectionIssue.AMBIGUITY
            detail.contains("peer address") || detail.contains("escaped") -> ConnectionIssue.PEER
            detail.contains("lifecycle") -> ConnectionIssue.BINDING
            detail == "native endpoint approval is still pending; return from Nostr Browser and confirm again" -> ConnectionIssue.PENDING
            detail.contains("provider") || detail.contains("certificate") || detail.contains("client closed") -> ConnectionIssue.PROVIDER
            else -> ConnectionIssue.GRANT
        }
        throw SharedConnectionException(issue, "FIPS requires the running shared Nostr Browser VPN and enrolled durable certificates: $detail. No HTTPS fallback was attempted.")
    }
    private fun endpoint(url: String): FipsPeerEndpoint? {
        return try { FipsUrlPolicy.endpoint(url) }
        catch (_: TowerError) { unavailable("noncanonical FIPS endpoint") }
    }
    @Volatile private var boundProvider: Uri? = null
    private val providerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Package replacement/removal/installation can invalidate an active or pending grant.
            val bound = boundProvider ?: return
            if (intent.data?.schemeSpecificPart == bound.authority?.removeSuffix(".fips.shared")) close()
            else runCatching { provider() }.onFailure { close() }
        }
    }
    init {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED); addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(providerReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else {
            // These package actions are protected system broadcasts on older Android versions.
            @Suppress("DEPRECATION")
            context.registerReceiver(providerReceiver, filter)
        }
    }
    @Synchronized internal fun bindProvider(candidate: Uri, forRelease: Boolean = false): Uri {
        // Release is revocation only: it may finish after closure without rebinding the client.
        if (forRelease) {
            if (boundProvider != candidate) unavailable("release provider does not match the original binding")
            return candidate
        }
        if (closing) unavailable("provider binding invalidated; restart Ambulando and verify the connection again")
        if (boundProvider != null && boundProvider != candidate) {
            close()
            unavailable("provider changed; restart Ambulando and verify the connection again")
        }
        boundProvider = candidate
        return candidate
    }
    private fun provider(forRelease: Boolean = false): Uri {
        try {
            val pins = context.resources.getStringArray(studio.otherstuff.ambulando.app.R.array.fips_shared_providers).map { entry ->
                val parts = entry.split('|')
                require(parts.size == 2)
                FipsProviderPin(parts[0], parts[1])
            }
            val installed = pins.mapNotNull { pin ->
                val info = context.packageManager.resolveContentProvider(pin.authority, 0) ?: return@mapNotNull null
                val signers = runCatching {
                    context.packageManager.getPackageInfo(info.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                        .signingInfo?.apkContentsSigners?.map { Sha256Hex.of(it.toByteArray()) }
                }.getOrNull().orEmpty()
                FipsProviderIdentity(info.packageName, info.authority.orEmpty(), signers, info.exported)
            }
            val selected = FipsProviderRoster.select(pins, installed)
            val uri = Uri.parse("content://${selected.authority}")
            return bindProvider(uri, forRelease)
        } catch (e: Exception) {
            if (boundProvider != null) close()
            unavailable(e.message ?: "provider missing or its current signing certificate is not enrolled")
        }
    }
    private fun call(uri: Uri, method: String, id: String? = null, extras: Bundle = Bundle()): Bundle {
        if (provider(forRelease = method == "release") != uri) unavailable("provider signing certificate changed")
        extras.putInt("version", 1)
        val stage = when (method) { "request" -> Stage.PROVIDER_REQUEST; "check" -> Stage.PROVIDER_CHECK; else -> Stage.PROVIDER_RELEASE }
        val result = LocalStages.blocking(stage) { context.contentResolver.call(uri, method, id, extras) } ?: unavailable("empty provider reply")
        if (provider(forRelease = method == "release") != uri) unavailable("provider changed during call")
        if (result.getInt("version") != 1) unavailable("unsupported provider version")
        return result
    }
    private suspend fun lease(e: FipsPeerEndpoint): Lease = LocalStages.trace(Stage.LEASE_ADMISSION) { leaseLock.withLock { LocalStages.trace(Stage.LEASE_LOCK) { withContext(Dispatchers.IO) {
        if (closing) unavailable("client closed")
        if (Build.VERSION.SDK_INT < 30) unavailable("shared FIPS requires Android 11 or newer")
        leases[e.origin]?.let { existing -> existing.renew(); return@withContext existing }
        val epoch = fence(e.origin)
        val prior = pending[e.origin]
        val uri = prior?.uri ?: provider()
        val reply = if (prior != null) call(uri, "check", prior.id) else call(uri, "request", extras = Bundle().apply {
            putString("endpoint", e.origin); putString("peerNpub", e.peerNpub); putString("purpose", "nstack"); putParcelable("callback", callback)
        })
        val status = reply.getString("status")
        val id = reply.getString("requestId") ?: prior?.id ?: unavailable("missing request ID")
        if (id.isBlank() || (prior != null && id != prior.id) ||
            (reply.containsKey("endpoint") && reply.getString("endpoint") != e.origin)) {
            clearPending(e.origin)?.let(::releasePending)
            unavailable("provider request binding changed")
        }
        if (status !in setOf("ready", "pending", "consent_required")) {
            clearPending(e.origin)?.let(::releasePending)
            unavailable(status ?: "unknown status")
        }
        if (status == "consent_required") {
            if (prior?.consent != null) {
                // A check must never replace the original one-shot capability or reset its handoff state.
                storePending(e.origin, epoch, prior)
                requireConsent(e.origin, prior)
            }
            @Suppress("DEPRECATION") val intent = reply.getParcelable<PendingIntent>("consentIntent") ?: run {
                releasePending(PendingRequest(uri, id, null)); unavailable("missing native consent")
            }
            val identity = try { consentProvider(uri) } catch (e: Exception) {
                releasePending(PendingRequest(uri, id, null)); throw e
            }
            if (intent.creatorPackage != identity.packageName || intent.creatorUid != identity.uid ||
                (Build.VERSION.SDK_INT >= 31 && !intent.isImmutable)) {
                clearPending(e.origin)?.let(::releasePending)
                releasePending(PendingRequest(uri, id, null))
                unavailable("untrusted provider consent intent")
            }
            val value = PendingRequest(uri, id, PendingConsent(identity, e.origin, id, intent))
            storePending(e.origin, epoch, value)
            LocalStages.emit(LocalStages.next(), Stage.LEASE_LOCK, Result.CONSENT)
            requireConsent(e.origin, value)
        }
        if (status == "pending") {
            if (prior != null) requireConsent(e.origin, prior)
            storePending(e.origin, epoch, PendingRequest(uri, id, null))
            unavailable("native endpoint approval is still pending; return from Nostr Browser and confirm again")
        }
        val (created, winner) = synchronized(this) {
            if (closing || fence(e.origin) != epoch) {
                releasePending(PendingRequest(uri, id, null))
                unavailable("provider binding invalidated")
            }
            pending.remove(e.origin)
            val created = Lease(uri, id, e, reply)
            created to leases.putIfAbsent(e.origin, created)
        }
        if (winner != null) { created.close(); winner.renew(); winner } else {
            try { created.start(); created.localCheck(); created } catch (e: Exception) { created.close(); throw e }
        }
    }
    } } }
    override suspend fun send(request: HttpRequest): HttpResponse {
        if (closing) unavailable("client closed; restart Ambulando and verify the connection again")
        val e = endpoint(request.url) ?: return http.send(request)
        val l = lease(e)
        l.localCheck()
        return OkHttpTransport(base = l.client, allowFips = true, preserveDispatcher = true).send(request).also { l.localCheck() }
    }
    override suspend fun open(request: HttpRequest): Pair<HttpResponseHead, EventChunks> {
        if (closing) unavailable("client closed; restart Ambulando and verify the connection again")
        val e = endpoint(request.url) ?: return streams.open(request)
        val l = lease(e); l.localCheck()
        return OkHttpEventStreamTransport(base = l.client, allowFips = true).open(request)
    }
    /** Only explicit connection selection discards consent for a different endpoint; active grants are untouched. */
    @Synchronized fun selectConsentOrigin(origin: String) {
        pendingEpoch++ // Also fence a previous selection's request whose reply has not arrived yet.
        pending.keys.filter { it != origin }.forEach { clearPending(it)?.let(::releasePending) }
    }
    @Synchronized fun releaseOrigin(origin: String) {
        leases[origin]?.close()
        clearPending(origin)?.let(::releasePending)
    }
    @Synchronized fun releaseAll() {
        pendingEpoch++
        leases.values.toList().forEach { it.close() }
        pending.values.forEach(::releasePending)
        pending.clear()
    }
    override fun close() {
        closing = true; releaseAll(); scope.cancel()
        runCatching { context.unregisterReceiver(providerReceiver) }
        // Complete after already-launched release calls, rather than canceling them before IPC.
        releaseJob.complete()
    }
    internal inner class Lease(val uri: Uri, val id: String, val endpoint: FipsPeerEndpoint, initial: Bundle) : AutoCloseable {
        private val closed = java.util.concurrent.atomic.AtomicBoolean(false)
        private val network = Network.fromNetworkHandle(initial.getLong("networkHandle"))
        private val generation = initial.getString("generation") ?: unavailable("missing runtime generation")
        @Volatile private var deadline = 0L
        private var binding: FipsGrantBinding? = null
        private val binder = initial.getBinder("lifecycle") ?: unavailable("missing provider lifecycle")
        private val sockets = ConcurrentHashMap.newKeySet<Socket>()
        private val death = IBinder.DeathRecipient { close() }
        private val timer = Runnable { if (SystemClock.elapsedRealtime() >= deadline) close() else scheduleExpiry() }
        private var renewal: Job? = null
        private val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(lost: Network) { if (lost == network) close() }
            override fun onCapabilitiesChanged(changed: Network, caps: NetworkCapabilities) { if (changed == network && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) close() }
        }
        private val factory = object : SocketFactory() {
            override fun createSocket(): Socket {
                renew()
                val socket = LocalStages.blocking(Stage.SOCKET) { network.socketFactory.createSocket() }
                sockets.add(socket)
                try { localCheck() } catch (e: Exception) { sockets.remove(socket); runCatching { socket.close() }; throw e }
                return socket
            }
            override fun createSocket(h: String, p: Int): Socket = throw IOException("FIPS requires scoped DNS")
            override fun createSocket(h: String, p: Int, a: java.net.InetAddress, l: Int): Socket = throw IOException("FIPS requires scoped DNS")
            override fun createSocket(a: java.net.InetAddress, p: Int): Socket = throw IOException("FIPS requires scoped DNS")
            override fun createSocket(a: java.net.InetAddress, p: Int, l: java.net.InetAddress, q: Int): Socket = throw IOException("FIPS requires scoped DNS")
        }
        val client: OkHttpClient = OkHttpClient.Builder().socketFactory(factory).proxy(Proxy.NO_PROXY)
            .dispatcher(Dispatcher().apply { maxRequestsPerHost = 4 })
            .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
            .connectionPool(ConnectionPool(0, 5, java.util.concurrent.TimeUnit.MINUTES)).dns { host ->
                renew(); if (host != endpoint.host) unavailable("DNS escaped endpoint")
                val addresses = LocalStages.blocking(Stage.DNS) { network.getAllByName(host).toList() }
                pin(addresses.map { it.address }); localCheck(); addresses
            }.eventListener(object : EventListener() {
                override fun connectStart(call: Call, address: InetSocketAddress, proxy: Proxy) {
                    LocalStages.emit(call.request().tag(StageObservation::class.java)?.id ?: 0, Stage.SOCKET, Result.BEGIN)
                    localCheck(); pin(listOf((address.address ?: unavailable("unresolved socket address")).address))
                    if (address.port != endpoint.port || proxy != Proxy.NO_PROXY) unavailable("socket escaped endpoint")
                }
                override fun connectEnd(call: Call, address: InetSocketAddress, proxy: Proxy, protocol: Protocol?) { LocalStages.emit(call.request().tag(StageObservation::class.java)?.id ?: 0, Stage.SOCKET, Result.END); localCheck(); pin(listOf((address.address ?: unavailable("unresolved socket address")).address)) }
                override fun connectionAcquired(call: Call, connection: Connection) {
                    localCheck()
                    val remote = connection.socket().remoteSocketAddress as? InetSocketAddress ?: run { close(); unavailable("missing connected peer") }
                    pin(listOf((remote.address ?: unavailable("unresolved connected peer")).address))
                    if (remote.port != endpoint.port) { close(); unavailable("connected peer port mismatch") }
                }
                override fun dnsStart(call: Call, domainName: String) { LocalStages.emit(call.request().tag(StageObservation::class.java)?.id ?: 0, Stage.DNS, Result.BEGIN) }
                override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<java.net.InetAddress>) { LocalStages.emit(call.request().tag(StageObservation::class.java)?.id ?: 0, Stage.DNS, Result.END) }
                override fun requestHeadersStart(call: Call) { LocalStages.emit(call.request().tag(StageObservation::class.java)?.id ?: 0, Stage.HTTP_HEADERS, Result.BEGIN) }
            }).build()
        init { accept(initial); binder.linkToDeath(death, 0) }
        fun start() {
            try {
                localCheck()
                manager.registerNetworkCallback(NetworkRequest.Builder().removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).addTransportType(NetworkCapabilities.TRANSPORT_VPN).build(), networkCallback)
                localCheck()
                scheduleExpiry()
                renewal = scope.launch { while (isActive && !closed.get()) { delay(10_000); try { renew() } catch (_: Exception) { close() } } }
            } catch (e: Exception) {
                // Revocation can run before registration: close may already have tried to unregister.
                runCatching { manager.unregisterNetworkCallback(networkCallback) }
                handler.removeCallbacks(timer); close(); throw e
            }
        }
        fun localCheck() {
            if (closed.get() || closing || SystemClock.elapsedRealtime() >= deadline || !binder.isBinderAlive || manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) != true) {
                close(); unavailable("grant expired, revoked or VPN changed")
            }
        }
        fun renew() = LocalStages.blocking(Stage.RENEW_ADMISSION) {
            synchronized(this) {
                LocalStages.blocking(Stage.RENEW_LOCK) {
                    try { localCheck(); accept(call(uri, "check", id)); localCheck(); scheduleExpiry() }
                    catch (e: Exception) { close(); throw SharedConnectionException((e as? SafeConnectionIssue)?.connectionIssue ?: ConnectionIssue.GRANT) }
                }
            }
        }
        private fun pin(addresses: List<ByteArray>) {
            try { endpoint.requirePeerAddresses(addresses) } catch (e: IllegalArgumentException) { close(); throw SharedConnectionException(ConnectionIssue.PEER, "FIPS peer address mismatch") }
        }
        private fun accept(b: Bundle) {
            val peerAddress = runCatching { InetAddresses.parseNumericAddress(b.getString("peerIpv6") ?: "").address }.getOrNull()
            if (peerAddress?.contentEquals(endpoint.addressBytes) != true) unavailable("provider peer address mismatch")
            val candidate = FipsGrantBinding(b.getString("requestId") ?: "", b.getString("grant") ?: "", b.getString("endpoint") ?: "",
                b.getString("peerNpub") ?: "", b.getString("purpose") ?: "", b.getLong("networkHandle"), b.getString("generation") ?: "",
                b.getByteArray("peerIpv6Bytes") ?: byteArrayOf(), b.getLong("expiresAt"), b.getLong("expiresElapsedRealtime"))
            candidate.validate(endpoint, id, binding, System.currentTimeMillis() / 1000, SystemClock.elapsedRealtime())
            if (b.getInt("version") != 1 || b.getString("status") != "ready" || b.getBinder("lifecycle") != binder || candidate.networkHandle != network.networkHandle || candidate.generation != generation) unavailable("grant lifecycle mismatch")
            binding = candidate
            deadline = candidate.expiresElapsedRealtime
        }

        private fun scheduleExpiry() { handler.removeCallbacks(timer); if (!closed.get()) handler.postDelayed(timer, maxOf(0, deadline - SystemClock.elapsedRealtime())) }
        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            LocalStages.emit(LocalStages.next(), Stage.LEASE_CLOSE, Result.CLOSED)
             leases.remove(endpoint.origin, this); handler.removeCallbacks(timer); renewal?.cancel()
            sockets.forEach { runCatching { it.close() } }; sockets.clear(); client.dispatcher.cancelAll(); client.connectionPool.evictAll()
            runCatching { manager.unregisterNetworkCallback(networkCallback) }; runCatching { binder.unlinkToDeath(death, 0) }
            releaseScope.launch { runCatching { call(uri, "release", id) } }
        }
    }
}
