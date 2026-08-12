package com.adit.iptv

import android.app.AlertDialog
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Constraints
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.TimeUnit

@UnstableApi
class MainActivity : AppCompatActivity() {

    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var playerFrame: ViewGroup
    private lateinit var headerContainer: View
    private lateinit var nowPlayingBlock: View
    private lateinit var categoryScroll: View
    private lateinit var dividerLine: View
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var channelList: RecyclerView
    private lateinit var search: EditText
    private lateinit var now: TextView
    private lateinit var status: TextView
    private lateinit var playlistLabel: TextView
    private lateinit var liveBadge: View
    private lateinit var fullscreenButton: TextView
    private lateinit var categoryChipContainer: LinearLayout

    private lateinit var repository: ChannelRepository
    private lateinit var adapter: ChannelAdapter

    private val allChannels = mutableListOf<Channel>()
    private val visible = mutableListOf<Channel>()
    private var favorites: MutableSet<String> = mutableSetOf()
    private var history: MutableList<String> = mutableListOf()

    private var selectedGroup: String? = null
    private var favoriteMode = false
    private var historyMode = false
    private var currentChannel: Channel? = null
    private var isFullscreen = false
    private var retriedCurrentChannel = false

    private val retryHandler = Handler(Looper.getMainLooper())

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(R.layout.activity_main)

        repository = ChannelRepository(this)
        favorites = repository.getFavorites()
        history = repository.getHistory()

        bindViews()
        setupPlayer()
        setupList()
        setupClicks()
        setupSearch()

        allChannels.addAll(repository.loadCachedOrBundled())
        rebuildCategoryChips()
        applyFilters()
        updatePlaylistLabel()

        refreshFromRemote(showSpinner = false, silent = true)
        schedulePeriodicUpdate()

        status.text = "Siap"
    }

    private fun bindViews() {
        playerView = findViewById(R.id.playerView)
        playerFrame = findViewById(R.id.playerFrame)
        headerContainer = findViewById(R.id.headerContainer)
        nowPlayingBlock = findViewById(R.id.nowPlayingBlock)
        categoryScroll = findViewById(R.id.categoryScroll)
        dividerLine = findViewById(R.id.dividerLine)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        channelList = findViewById(R.id.channelList)
        search = findViewById(R.id.searchInput)
        now = findViewById(R.id.nowPlaying)
        status = findViewById(R.id.statusText)
        playlistLabel = findViewById(R.id.playlistLabel)
        liveBadge = findViewById(R.id.liveBadge)
        fullscreenButton = findViewById(R.id.fullscreenButton)
        categoryChipContainer = findViewById(R.id.categoryChipContainer)
    }

    private fun setupPlayer() {
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(20000)
            .setUserAgent("IPTVPlayer/2.0 (Android)")

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(http))
            .build()

        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                status.text = "Playback error: ${error.errorCodeName}"
                liveBadge.visibility = View.GONE
                // one automatic retry for transient network hiccups, common on live streams
                val channel = currentChannel
                if (channel != null && !retriedCurrentChannel) {
                    retriedCurrentChannel = true
                    status.text = "Koneksi terputus, mencoba lagi..."
                    retryHandler.postDelayed({ play(channel) }, 2500)
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                status.text = if (isPlaying) "Sedang diputar" else "Buffering..."
                liveBadge.visibility = if (isPlaying) View.VISIBLE else View.GONE
                if (isPlaying) retriedCurrentChannel = false
            }
        })
        playerView.player = player
    }

    private fun setupList() {
        adapter = ChannelAdapter(
            onClick = { channel -> play(channel) },
            onToggleFavorite = { channel -> toggleFavorite(channel) }
        )
        channelList.layoutManager = LinearLayoutManager(this)
        channelList.adapter = adapter

        swipeRefresh.setColorSchemeResources(R.color.accent)
        swipeRefresh.setOnRefreshListener { refreshFromRemote(showSpinner = true, silent = false) }
    }

    private fun setupClicks() {
        findViewById<View>(R.id.addPlaylistButton).setOnClickListener { playlistDialog() }
        findViewById<View>(R.id.homeButton).setOnClickListener { setMode(fav = false, hist = false) }
        findViewById<View>(R.id.favoritesButton).setOnClickListener { setMode(fav = true, hist = false) }
        findViewById<View>(R.id.historyButton).setOnClickListener { setMode(fav = false, hist = true) }
        findViewById<View>(R.id.settingsButton).setOnClickListener { settingsDialog() }
        findViewById<View>(R.id.updateButton).setOnClickListener {
            refreshFromRemote(showSpinner = false, silent = false)
        }
        fullscreenButton.setOnClickListener { toggleFullscreen() }
        refreshNavSelection()
    }

    private fun setupSearch() {
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = applyFilters()
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
    }

    private fun setMode(fav: Boolean, hist: Boolean) {
        favoriteMode = fav
        historyMode = hist
        refreshNavSelection()
        applyFilters()
    }

    private fun refreshNavSelection() {
        findViewById<View>(R.id.homeButton).isSelected = !favoriteMode && !historyMode
        findViewById<View>(R.id.favoritesButton).isSelected = favoriteMode
        findViewById<View>(R.id.historyButton).isSelected = historyMode
    }

    // ---------------- Playback ----------------

    private fun play(channel: Channel) {
        if (channel.url.isBlank()) return
        currentChannel = channel
        retriedCurrentChannel = false
        now.text = channel.name
        addHistory(channel.url)
        adapter.setPlaying(channel.url)

        try {
            val clean = channel.url.substringBefore("?").lowercase(Locale.US)
            val itemBuilder = MediaItem.Builder().setUri(Uri.parse(channel.url))
            when {
                clean.endsWith(".m3u8") -> itemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
                clean.endsWith(".mpd") -> itemBuilder.setMimeType(MimeTypes.APPLICATION_MPD)
            }
            player.setMediaItem(itemBuilder.build())
            player.prepare()
            player.playWhenReady = true
            status.text = "Memuat ${channel.name}..."
        } catch (e: Exception) {
            status.text = "Gagal: ${e.message}"
        }
    }

    private fun addHistory(url: String) {
        history.remove(url)
        history.add(0, url)
        while (history.size > 30) history.removeAt(history.size - 1)
        repository.saveHistory(history)
        if (historyMode) applyFilters()
    }

    private fun toggleFavorite(channel: Channel) {
        if (!favorites.add(channel.url)) favorites.remove(channel.url)
        repository.saveFavorites(favorites)
        applyFilters()
    }

    // ---------------- Playlist / channel sources ----------------

    private fun playlistDialog() {
        val input = EditText(this)
        input.hint = "https://contoh.com/playlist.m3u"
        input.setTextColor(getColorCompat(R.color.text))
        AlertDialog.Builder(this, R.style.AppDialog)
            .setTitle("Tambah Playlist M3U")
            .setMessage("Masukkan URL playlist M3U/M3U8. Playlist ini akan menggantikan daftar channel yang sedang aktif.")
            .setView(input)
            .setNegativeButton("Batal", null)
            .setPositiveButton("Muat") { _, _ -> loadExternalPlaylist(input.text.toString().trim()) }
            .show()
    }

    private fun loadExternalPlaylist(url: String) {
        if (url.isBlank()) return
        status.text = "Mengambil playlist..."
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { repository.fetchRemote(url) }
                allChannels.clear()
                allChannels.addAll(result)
                repository.saveCache(result)
                rebuildCategoryChips()
                setMode(fav = false, hist = false)
                updatePlaylistLabel()
                status.text = "Playlist berhasil dimuat (${result.size} channel)"
            } catch (e: Exception) {
                status.text = "Playlist error: ${e.message}"
            }
        }
    }

    private fun refreshFromRemote(showSpinner: Boolean, silent: Boolean) {
        val url = repository.getRemoteUrl()
        if (url.isBlank()) {
            if (!silent) {
                Toast.makeText(this, "Belum ada Remote Channel URL. Atur di ⚙ Settings.", Toast.LENGTH_LONG).show()
            }
            swipeRefresh.isRefreshing = false
            return
        }

        if (showSpinner) swipeRefresh.isRefreshing = true
        if (!silent) status.text = "Memeriksa update..."

        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { repository.fetchRemote(url) }
                allChannels.clear()
                allChannels.addAll(result)
                repository.saveCache(result)
                rebuildCategoryChips()
                applyFilters()
                updatePlaylistLabel()
                if (!silent) {
                    Toast.makeText(this@MainActivity, "Diperbarui • ${result.size} channel", Toast.LENGTH_SHORT).show()
                }
                status.text = "Update berhasil"
            } catch (e: Exception) {
                if (!silent) {
                    status.text = "Gagal update: ${e.message}"
                    Toast.makeText(this@MainActivity, "Gagal update: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun schedulePeriodicUpdate() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<ChannelUpdateWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            ChannelUpdateWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    // ---------------- Filtering / UI ----------------

    private fun applyFilters() {
        val q = search.text.toString().trim().lowercase(Locale.getDefault())

        val source: List<Channel> = when {
            historyMode -> history.mapNotNull { url -> allChannels.find { it.url == url } }
            favoriteMode -> allChannels.filter { favorites.contains(it.url) }
            else -> allChannels
        }

        visible.clear()
        visible.addAll(source.filter { channel ->
            (selectedGroup == null || channel.group.equals(selectedGroup, ignoreCase = true)) &&
                (q.isBlank() ||
                    channel.name.lowercase(Locale.getDefault()).contains(q) ||
                    channel.group.lowercase(Locale.getDefault()).contains(q))
        })

        adapter.submit(visible, favorites, currentChannel?.url)
    }

    private fun rebuildCategoryChips() {
        categoryChipContainer.removeAllViews()
        val groups = allChannels.map { it.group }.filter { it.isNotBlank() }.distinct().sorted()

        categoryChipContainer.addView(makeChip("Semua", selectedGroup == null) {
            selectedGroup = null
            rebuildCategoryChips()
            applyFilters()
        })
        groups.forEach { group ->
            categoryChipContainer.addView(makeChip(group, selectedGroup == group) {
                selectedGroup = group
                rebuildCategoryChips()
                applyFilters()
            })
        }
    }

    private fun makeChip(label: String, selected: Boolean, onClick: () -> Unit): View {
        val chip = layoutInflater.inflate(R.layout.item_chip, categoryChipContainer, false) as TextView
        chip.text = label
        chip.isSelected = selected
        chip.setOnClickListener { onClick() }
        return chip
    }

    private fun updatePlaylistLabel() {
        val lastUpdate = repository.getLastUpdateLabel()
        val suffix = if (lastUpdate.isNotBlank()) " • update $lastUpdate" else ""
        playlistLabel.text = "${allChannels.size} channel$suffix"
    }

    // ---------------- Settings ----------------

    private fun settingsDialog() {
        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        val pad = (16 * resources.displayMetrics.density).toInt()
        container.setPadding(pad, pad / 2, pad, 0)

        val label = TextView(this)
        label.text = "Remote Channel URL (raw GitHub, .json atau .m3u8)"
        label.setTextColor(getColorCompat(R.color.muted))
        label.textSize = 12f
        container.addView(label)

        val input = EditText(this)
        input.setText(repository.getRemoteUrl())
        input.hint = "https://raw.githubusercontent.com/user/repo/main/channels.json"
        input.setTextColor(getColorCompat(R.color.text))
        container.addView(input)

        AlertDialog.Builder(this, R.style.AppDialog)
            .setTitle("Settings")
            .setMessage(
                "IPTV Player ${BuildConfig.VERSION_NAME}\n" +
                    "HLS / M3U8 • MPEG-DASH / MPD\n\n" +
                    "Isi Remote Channel URL supaya daftar channel bisa kamu update kapan saja " +
                    "lewat GitHub (misalnya dari Termux), tanpa install ulang aplikasi. " +
                    "App akan cek update otomatis setiap dibuka dan setiap ±15 menit di latar belakang."
            )
            .setView(container)
            .setNegativeButton("Tutup", null)
            .setPositiveButton("Simpan & Update") { _, _ ->
                repository.setRemoteUrl(input.text.toString().trim())
                refreshFromRemote(showSpinner = false, silent = false)
            }
            .show()
    }

    // ---------------- Fullscreen ----------------

    private fun toggleFullscreen() {
        isFullscreen = !isFullscreen

        headerContainer.visibility = if (isFullscreen) View.GONE else View.VISIBLE
        nowPlayingBlock.visibility = if (isFullscreen) View.GONE else View.VISIBLE
        categoryScroll.visibility = if (isFullscreen) View.GONE else View.VISIBLE
        dividerLine.visibility = if (isFullscreen) View.GONE else View.VISIBLE
        swipeRefresh.visibility = if (isFullscreen) View.GONE else View.VISIBLE

        val params = playerFrame.layoutParams
        if (isFullscreen) {
            params.height = ViewGroup.LayoutParams.MATCH_PARENT
            playerFrame.setPadding(0, 0, 0, 0)
            (playerFrame.layoutParams as? LinearLayout.LayoutParams)?.setMargins(0, 0, 0, 0)
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            fullscreenButton.text = "⤡"
            hideSystemBars()
        } else {
            params.height = (220 * resources.displayMetrics.density).toInt()
            val margin = (12 * resources.displayMetrics.density).toInt()
            (playerFrame.layoutParams as? LinearLayout.LayoutParams)?.setMargins(margin, margin, margin, margin)
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            fullscreenButton.text = "⛶"
            showSystemBars()
        }
        playerFrame.layoutParams = params
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
        }
    }

    private fun showSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    private fun getColorCompat(id: Int): Int = androidx.core.content.ContextCompat.getColor(this, id)

    override fun onDestroy() {
        retryHandler.removeCallbacksAndMessages(null)
        player.release()
        super.onDestroy()
    }
}
