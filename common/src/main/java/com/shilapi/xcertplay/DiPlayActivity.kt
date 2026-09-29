// SPDX-License-Identifier: AGPL-3.0-only
// UI copy and visual language adapted from DiAuto. See docs/THIRD_PARTY_NOTICES.md.
package com.shilapi.xcertplay

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** DiAuto's visual language, with a connection flow for an independent CarPlay receiver. */
class DiPlayActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var page = "home"
    private var setupError: String? = null
    private var status: TextView? = null
    private var connectButton: Button? = null
    private var disconnectButton: Button? = null
    private var lastRunning: Boolean? = null
    private var pendingWireless = false
    private var initialLaunch = true
    private var notificationTransport = true
    private var exportInProgress = false
    private var exportButton: Button? = null
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        connect(notificationTransport)
    }
    private val tick = object : Runnable {
        override fun run() { refreshStatus(); handler.postDelayed(this, 1000) }
    }
    private val bluetoothPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) choosePhone() else permissionHelp("Nearby devices", "Allow Nearby devices so DiPlay can connect to your paired iPhone.")
    }
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) exportDiagnostics(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.shilapi.xcertplay.hud.BydNavigationOutputs.onAppOpened(applicationContext)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = BG; window.navigationBarColor = BG
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            hide(WindowInsetsCompat.Type.statusBars())
        }
        setupError = runCatching { DiPlayBootstrap.ensure(this) }.exceptionOrNull()?.let {
            "Local setup could not finish. Reinstall the complete DiPlay beta APK and try again."
        }
        page = savedInstanceState?.getString("page") ?: intent.getStringExtra("page") ?: "home"
        render()
        handleWirelessRecovery()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (page != "home") { page = "home"; render() }
                else { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        page = intent.getStringExtra("page") ?: "home"; render()
        handleWirelessRecovery()
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("page", page); super.onSaveInstanceState(outState) }
    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); render() }
    override fun onResume() {
        super.onResume(); handler.removeCallbacks(tick); handler.post(tick)
        // Back from the car settings: refresh the car hotspot reminder on the home page.
        if (!initialLaunch && page == "home") render()
        if (initialLaunch) {
            initialLaunch = false
            if (setupError == null && !CarPlayBackgroundSession.hasSession() &&
                DiPlayPreferences.autoConnect(this) && intent.getStringExtra("page") == null) {
                handler.post { connect(AirPlayPersistence.loadWirelessEnabled(this)) }
            }
        }
    }
    override fun onPause() { handler.removeCallbacks(tick); super.onPause() }

    private fun render() {
        status = null; connectButton = null; disconnectButton = null; lastRunning = null
        val scroll = ScrollView(this).apply { setBackgroundColor(BG); isFillViewport = true; clipToPadding = false }
        val content = column().apply { setPadding(dp(32), dp(24), dp(32), dp(32)) }
        scroll.addView(content)
        val header = row().apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(ImageView(this).apply { setImageResource(R.drawable.ic_carplay); contentDescription = "CarPlay" }, LinearLayout.LayoutParams(dp(36), dp(36)))
        header.addView(label("DiPlay", 26, TEXT, true).apply { setPadding(dp(12), 0, 0, 0) }, LinearLayout.LayoutParams(0, dp(56), 1f))
        header.addView(button(if (page == "home") "Car home" else "Back", false) {
            if (page == "home") startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
            else { page = "home"; render() }
        }, LinearLayout.LayoutParams(dp(130), dp(56)))
        content.addView(header)
        content.addView(space(24))
        when (page) {
            "settings" -> settings(content)
            "about" -> about(content)
            else -> home(content)
        }
        setContentView(scroll)
        refreshStatus()
    }

    private fun home(content: LinearLayout) {
        val wide = resources.configuration.screenWidthDp >= 850
        val body = column()
        val left = column()
        left.addView(label("YOUR PHONE. YOUR DRIVE.", 12, ACCENT, true).apply { letterSpacing = .16f })
        left.addView(label("A familiar drive.", if (wide) 42 else 36, TEXT, true).apply { setPadding(0, dp(12), 0, dp(10)) })
        left.addView(label("Your maps, music and conversations.\nCarPlay, right here on your car display.", 19, MUTED))
        val card = card()
        card.addView(label("WIRELESS CARPLAY", 12, ACCENT, true).apply { letterSpacing = .12f })
        status = label("Ready when you are", 24, TEXT, true).apply { setPadding(0, dp(10), 0, dp(16)) }
        card.addView(status)
        connectButton = button("Connect phone", true) {
            if (CarPlayBackgroundSession.hasSession()) openProjection()
            else connect(true)
        }
        card.addView(connectButton, matchButton())
        card.addView(label("Pair your iPhone with the car’s Bluetooth, then connect.\nKeep Bluetooth and Wi-Fi on.", 15, MUTED).apply { setPadding(0, dp(14), 0, 0) })
        if (carHotspotOff()) {
            card.addView(label("The car hotspot “${AirPlayPersistence.loadManualHotspotSsid(this)}” is off. Turn it on in the car settings before connecting.", 15, WARNING).apply { setPadding(0, dp(14), 0, 0) })
            card.addView(button("Open car hotspot settings", false) { openCarWifiSettings() }, matchButton(10, 56))
        }
        card.addView(button("Choose iPhone", false) { choosePhone() }, matchButton(16, 56))
        disconnectButton = button("Disconnect", false) {
            disconnectButton?.isEnabled = false
            CarPlayBackgroundSession.stop { runOnUiThread { refreshStatus() } }
        }.apply { visibility = View.GONE }
        card.addView(disconnectButton, matchButton(10, 56))
        val right = column().apply { gravity = Gravity.CENTER_HORIZONTAL }
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.ic_carplay)
            contentDescription = "CarPlay icon"
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val branding = column().apply {
            gravity = Gravity.CENTER
            addView(logo, LinearLayout.LayoutParams(dp(96), dp(96)))
        }
        right.addView(button("Connect with USB", false) { connect(false) }, matchButton())
        right.addView(label("Plug your iPhone into a USB data port.\nAllow CarPlay when your iPhone asks.", 14, MUTED).apply { gravity = Gravity.CENTER; setPadding(dp(8), dp(10), dp(8), dp(24)) })
        right.addView(button("Settings", false) { page = "settings"; render() }, matchButton())
        right.addView(label("Make DiPlay feel right for your car.", 14, MUTED).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(24)) })
        right.addView(label("PUBLIC PREVIEW  ·  ${version()}", 12, MUTED).apply { letterSpacing = .08f })
        if (wide) {
            // Both rows share column widths. The USB button starts at the wireless
            // card's top edge, independently of hero wrapping or font scaling.
            fun columns(first: View, second: View, stretchSecond: Boolean = false) = row().apply {
                gravity = Gravity.TOP
                addView(first, LinearLayout.LayoutParams(0, -2, 1.6f))
                addView(space(40), LinearLayout.LayoutParams(dp(40), 1))
                addView(second, LinearLayout.LayoutParams(0, if (stretchSecond) -1 else -2, 1f))
            }
            body.addView(columns(left, branding, true))
            body.addView(space(26))
            body.addView(columns(card, right))
        } else {
            body.addView(left)
            body.addView(space(26))
            body.addView(card)
            body.addView(space(26))
            body.addView(branding)
            body.addView(space(24))
            body.addView(right)
        }
        setupError?.let { body.addView(label(it, 16, WARNING).apply { setPadding(0, dp(16), 0, 0) }) }
        content.addView(body)
    }

    private fun settings(content: LinearLayout) {
        content.addView(label("Your drive, your way.", 34, TEXT, true))
        content.addView(label("Apply reconnects CarPlay for size, resolution, music buffer and frame rate. Other changes apply to your next connection.", 17, MUTED).apply { setPadding(0, dp(8), 0, dp(24)) })
        section(content, "Automatic connection") { card ->
            toggle(card, "Connect when DiPlay opens", "Use your last connection type and selected iPhone.", DiPlayPreferences.autoConnect(this)) { DiPlayPreferences.saveAutoConnect(this, it) }
            toggle(card, "Open after the car starts", "Availability depends on your head unit’s startup settings.", AirPlayPersistence.loadAutoStartOnBoot(this)) { AirPlayPersistence.saveAutoStartOnBoot(this, it) }
            card.addView(button("Choose iPhone · ${DiPlayPreferences.phoneName(this)}", false) { choosePhone() }, matchButton(12, 60))
        }
        section(content, "Wireless connection") { card -> wirelessLinkControls(card) }
        section(content, "Display and performance") { card ->
            carPlaySizeControl(card)
            choice(card, "Resolution", listOf("Native", "80% · lighter load", "60% · lightest load"), listOf(10, 8, 6).indexOf(AirPlayPersistence.loadDisplayScaleTenths(this)).coerceAtLeast(0)) { AirPlayPersistence.saveDisplayScaleTenths(this, listOf(10, 8, 6)[it]) }
            val bufferPresets = com.shilapi.xcertplay.media.MediaAudioBuffer.presets
            choice(card, "Music buffer", listOf("300 ms · default", "500 ms", "1000 ms · most stable"),
                bufferPresets.indexOf(AirPlayPersistence.loadMediaBufferMillis(this)).coerceAtLeast(0)) {
                AirPlayPersistence.saveMediaBufferMillis(this, bufferPresets[it])
            }
            choice(card, "Frame rate", listOf("30 fps · lighter load", "60 fps · smoother motion"), if (AirPlayPersistence.loadFps(this) == 60) 1 else 0) { AirPlayPersistence.saveFps(this, if (it == 1) 60 else 30) }
            toggle(card, "Efficient video", "Use HEVC. Leave off for the widest head-unit compatibility.", AirPlayPersistence.loadHevcEnabled(this)) { AirPlayPersistence.saveHevcEnabled(this, it) }
            toggle(card, "Right-hand drive", "Place CarPlay’s controls closer to the driver.", AirPlayPersistence.loadRightHandDrive(this)) { AirPlayPersistence.saveRightHandDrive(this, it) }
            toggle(card, "Full screen", "Hide the car’s system bars while CarPlay is open.", AirPlayPersistence.loadHideTopBar(this) && AirPlayPersistence.loadHideBottomBar(this)) {
                AirPlayPersistence.saveHideTopBar(this, it); AirPlayPersistence.saveHideBottomBar(this, it)
            }
        }
        if (com.shilapi.xcertplay.hud.BydOutputSettings.available(this)) section(content, "BYD navigation") { card ->
            toggle(card, "Navigation on HUD and instrument cluster",
                "Show phone navigation arrows, distance and street names on supported BYD displays. Vehicle compatibility varies.",
                com.shilapi.xcertplay.hud.BydOutputSettings.enabled(this)) { com.shilapi.xcertplay.hud.BydOutputSettings.setEnabled(this, it) }
        }
        section(content, "Permissions and connection help") { card ->
            card.addView(label("Nearby devices connects your iPhone. Microphone enables Siri and calls. Older Android versions also require Location for wireless setup. USB mode may ask for a local VPN connection.", 16, MUTED))
            card.addView(button("App permissions", false) { openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }, matchButton(16, 60))
            card.addView(button("Bluetooth settings", false) { openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }, matchButton(10, 60))
            card.addView(button("Wireless connection help", false) { wirelessHelp() }, matchButton(10, 60))
        }
        section(content, "About and diagnostics") { card ->
            card.addView(button("About DiPlay", false) { page = "about"; render() }, matchButton(0, 60))
            exportButton = button(if (exportInProgress) "Saving report…" else "Save diagnostic report", false) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) exportDiagnostics()
                else chooseReportDestination()
            }.apply { isEnabled = !exportInProgress }
            card.addView(exportButton, matchButton(10, 60))
            val destination = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "Reports save to Downloads/DiPlay. " else "Choose where to save your report. "
            card.addView(label(destination + "Nothing is sent automatically. Protocol payloads and credentials are excluded.", 14, MUTED).apply { setPadding(0, dp(12), 0, 0) })
        }
    }

    private fun about(content: LinearLayout) {
        content.addView(label("DiPlay", 40, TEXT, true))
        content.addView(label("CarPlay, at home in your car.", 20, MUTED).apply { setPadding(0, dp(8), 0, dp(24)) })
        section(content, "Public preview · ${version()}") { card ->
            card.addView(label("An independent CarPlay receiver for Android head units. Wired and wireless connections run on the head unit, with local authentication. A standard iPhone can connect without a jailbreak, Mac, dongle or sign-in.\n\nThis preview uses an experimental accessory identity. Compatibility with every iPhone and head unit is still being tested. It is not an Apple-certified product.", 17, TEXT))
        }
        section(content, "Made possible by open source") { card ->
            card.addView(label("Receiver based on xcertplay, licensed under GPL-3.0. DiPlay’s interface follows DiAuto’s design, licensed under AGPL-3.0.\n\nIncludes AndroidX, Bouncy Castle, JmDNS and SLF4J. Source and license notices accompany this release.\n\nCarPlay and the CarPlay icon belong to Apple Inc. DiPlay is an independent project.", 16, MUTED))
        }
    }

    // The car hotspot link needs the hotspot on; DiPlay only checks it (turning it on needs ADB-only permission).
    private fun carHotspotOff(): Boolean =
        AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.MANUAL &&
            com.shilapi.xcertplay.network.CarHotspotStatus.isEnabled(this) == false

    private fun carHotspotOffDialog() {
        AlertDialog.Builder(this).setTitle("Car hotspot is off")
            .setMessage("DiPlay connects through the car hotspot “${AirPlayPersistence.loadManualHotspotSsid(this)}”. Turn it on in the car settings, then connect.")
            .setPositiveButton("Open car settings") { _, _ -> openCarWifiSettings() }
            .setNeutralButton("Connect") { _, _ -> connect(true) }
            .setNegativeButton("Cancel", null).show()
    }

    // BYD maps the AOSP tether action to its own hotspot screen; other firmware falls back to Wi-Fi settings.
    // BYD shows that screen as a dialog and closes it unless its own settings or the car home screen is on top,
    // so the home screen goes first.
    private fun openCarWifiSettings() {
        val hotspot = Intent("com.android.settings.WIFI_TETHER_SETTINGS")
        val target = packageManager.resolveActivity(hotspot, 0)?.activityInfo?.packageName
        if (target == null) {
            openSystem(Intent(Settings.ACTION_WIRELESS_SETTINGS))
            return
        }
        if (target == "com.byd.carsettings") {
            runCatching { startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)) }
        }
        if (runCatching { startActivity(hotspot) }.isSuccess) return
        openSystem(Intent(Settings.ACTION_WIRELESS_SETTINGS))
    }

    // Wi-Fi Direct is the default link. The car's own hotspot is an alternative when Wi-Fi Direct is unstable.
    // The runtime config rejects manual mode without valid credentials, so it is only saved together with them.
    private fun wirelessLinkControls(parent: LinearLayout) {
        val carHotspot = AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.MANUAL
        val defaultLink = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "Wi-Fi Direct · default" else "Local hotspot · default"
        val options = arrayOf(defaultLink, "Car hotspot")
        val control = button("Wireless link · ${options[if (carHotspot) 1 else 0]}", false) {}
        control.setOnClickListener {
            var selection = if (carHotspot) 1 else 0
            AlertDialog.Builder(this).setTitle("Wireless link")
                .setSingleChoiceItems(options, selection) { _, index -> selection = index }
                .setPositiveButton(if (CarPlayBackgroundSession.hasSession()) "Apply and reconnect" else "Save") { _, _ ->
                    when {
                        (selection == 1) == carHotspot -> Unit
                        selection == 0 -> applyWirelessLink(WirelessHotspotMode.WIFI_P2P)
                        hotspotError(storedSsid(), storedPassword()) == null -> applyWirelessLink(WirelessHotspotMode.MANUAL)
                        else -> askHotspotCredentials { ssid, password ->
                            saveHotspotCredentials(ssid, password)
                            applyWirelessLink(WirelessHotspotMode.MANUAL)
                        }
                    }
                }.setNegativeButton("Cancel", null).show()
        }
        parent.addView(control, matchButton(0, 60)); parent.addView(space(12))
        if (!carHotspot) {
            val description = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                "DiPlay creates its own Wi-Fi Direct network for the iPhone."
                else "DiPlay creates a local hotspot for the iPhone. Android chooses the Wi-Fi band."
            parent.addView(label(description, 14, MUTED).apply {
                setPadding(0, 0, 0, dp(18))
            })
            return
        }
        val ssid = storedSsid()
        val password = storedPassword()
        parent.addView(button("Hotspot name · $ssid", false) {
            textInput("Car hotspot name", ssid, secret = false) { value ->
                hotspotError(value, password)?.let { toast(it); return@textInput }
                saveHotspotCredentials(value, password)
                render()
            }
        }, matchButton(0, 60))
        parent.addView(space(12))
        parent.addView(button("Hotspot password · ${if (password.isEmpty()) "none" else "•".repeat(8)}", false) {
            textInput("Car hotspot password", password, secret = true) { value ->
                hotspotError(ssid, value)?.let { toast(it); return@textInput }
                saveHotspotCredentials(ssid, value)
                render()
            }
        }, matchButton(0, 60))
        parent.addView(label("Turn on the hotspot in the car settings first and enter the same name and password here. The iPhone joins this network for CarPlay. Changes apply to your next connection.", 14, MUTED).apply {
            setPadding(0, dp(8), 0, dp(18))
        })
    }

    private fun storedSsid() = AirPlayPersistence.loadManualHotspotSsid(this)
    private fun storedPassword() = AirPlayPersistence.loadManualHotspotPassphrase(this)
    private fun hotspotError(ssid: String, password: String) =
        com.shilapi.xcertplay.orchestration.ManualHotspotValidation.validate(ssid, password)

    private fun saveHotspotCredentials(ssid: String, password: String) {
        AirPlayPersistence.saveManualHotspotSsid(this, ssid)
        AirPlayPersistence.saveManualHotspotPassphrase(this, password)
        AirPlayPersistence.saveManualHotspotSecurity(this,
            com.shilapi.xcertplay.orchestration.ManualHotspotValidation.securityFor(password))
        AirPlayPersistence.saveManualHotspotBand(this, com.shilapi.xcertplay.orchestration.ManualHotspotBand.AUTO)
        AirPlayPersistence.saveManualHotspotChannel(this, 0)
    }

    private fun askHotspotCredentials(done: (String, String) -> Unit) {
        textInput("Car hotspot name", storedSsid(), secret = false) { ssid ->
            textInput("Car hotspot password", storedPassword(), secret = true) { password ->
                val error = hotspotError(ssid, password)
                if (error != null) toast(error) else done(ssid, password)
            }
        }
    }

    private fun applyWirelessLink(mode: WirelessHotspotMode) {
        AirPlayPersistence.saveWirelessHotspotMode(this, mode)
        render()
        if (CarPlayBackgroundSession.hasSession()) connect(true)
    }

    private fun textInput(title: String, current: String, secret: Boolean, save: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(current)
            setSingleLine()
            inputType = if (secret) {
                android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                android.text.InputType.TYPE_CLASS_TEXT
            }
        }
        AlertDialog.Builder(this).setTitle(title).setView(input)
            .setPositiveButton("Save") { _, _ -> save(input.text.toString().let { if (secret) it else it.trim() }) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun carPlaySizeControl(parent: LinearLayout) {
        val sizes = com.shilapi.xcertplay.airplay.CarPlaySize.entries
        val current = com.shilapi.xcertplay.airplay.CarPlaySize.fromWidthMillimeters(AirPlayPersistence.loadWidthPhysicalMm(this))
        choice(parent, "CarPlay size", sizes.map { it.label }, sizes.indexOf(current)) {
            AirPlayPersistence.saveWidthPhysicalMm(this, sizes[it].widthMillimeters)
        }
        parent.addView(label("Changes the size of CarPlay icons and text. Applying a size reconnects CarPlay.", 14, MUTED).apply {
            setPadding(0, 0, 0, dp(18))
        })
    }

    private fun connect(wireless: Boolean) {
        if (setupError != null) { toast(setupError!!); return }
        if (wireless && carHotspotOff()) { carHotspotOffDialog(); return }
        if (wireless && DiPlayPreferences.phoneAddress(this) == null) {
            pendingWireless = true; choosePhone(); return
        }
        val preferences = getSharedPreferences("diplay", MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && !preferences.getBoolean("notification_asked", false)) {
            preferences.edit().putBoolean("notification_asked", true).apply()
            notificationTransport = wireless
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        val open = {
            AirPlayPersistence.saveWirelessEnabled(this, wireless)
            openProjection()
        }
        if (CarPlayBackgroundSession.hasSession()) CarPlayBackgroundSession.stop { runOnUiThread { open() } }
        else open()
    }
    private fun openProjection() {
        startActivity(Intent(this, CarPlayHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }
    private fun choosePhone() {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT); return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            AlertDialog.Builder(this).setTitle("Turn on Bluetooth")
                .setMessage("Enable the car’s Bluetooth and pair your iPhone first.")
                .setPositiveButton("Open Bluetooth") { _, _ -> openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                .setNegativeButton("Later", null).show(); return
        }
        val devices = runCatching { adapter.bondedDevices.sortedBy { it.name ?: "" } }.getOrDefault(emptyList())
        if (devices.isEmpty()) {
            AlertDialog.Builder(this).setTitle("Pair your iPhone")
                .setMessage("On your iPhone, open Settings → Bluetooth and pair with the car. Then return to DiPlay and choose Connect phone.")
                .setPositiveButton("Open Bluetooth") { _, _ -> openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                .setNegativeButton("Got it", null).show(); return
        }
        AlertDialog.Builder(this).setTitle("Choose your iPhone")
            .setItems(devices.map { device ->
                val name = device.name ?: "Paired device"
                if (devices.count { it.name == device.name } > 1) "$name · ${device.address.takeLast(5)}" else name
            }.toTypedArray()) { _, index ->
                val device = devices[index]
                DiPlayPreferences.savePhone(this, device.address, device.name ?: "iPhone")
                val start = pendingWireless; pendingWireless = false
                render()
                if (start) connect(true)
            }.setNeutralButton("Pair another") { _, _ -> openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            .setNegativeButton("Cancel") { _, _ -> pendingWireless = false }.show()
    }

    private fun wirelessHelp() {
        val supportsWifiDirect = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val recoveryHelp = if (supportsWifiDirect)
            "If a previous projection app left its connection running, reset CarPlay Wi-Fi below and connect again. Your car’s normal internet Wi-Fi stays on."
            else "Reconnect below to restart the CarPlay connection using your saved wireless settings. If you use the car hotspot, keep it on."
        AlertDialog.Builder(this).setTitle("Wireless connection help")
            .setMessage("Pair your iPhone with the car’s Bluetooth, keep Wi-Fi on, and allow CarPlay on the iPhone. Close any other phone-projection app.\n\n$recoveryHelp")
            .setPositiveButton("Got it", null)
            .setNeutralButton(if (supportsWifiDirect) "Reset CarPlay Wi-Fi" else "Reconnect CarPlay Wi-Fi") { _, _ ->
                confirmWirelessReset()
            }.show()
    }

    private fun handleWirelessRecovery() {
        if (page != "wireless-recovery") return
        page = "home"; render()
        confirmWirelessReset()
    }

    private fun confirmWirelessReset() {
        val supportsWifiDirect = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val message = if (supportsWifiDirect)
            "This ends the existing Wi-Fi Direct connection, including one left behind after reinstalling. Close other projection apps first. Your car’s internet Wi-Fi stays on."
            else "This restarts CarPlay using your saved wireless settings. DiPlay restarts its own hotspot when selected. If you use the car hotspot, keep it on."
        AlertDialog.Builder(this).setTitle(if (supportsWifiDirect) "Reset CarPlay Wi-Fi?" else "Reconnect CarPlay Wi-Fi?")
            .setMessage(message)
            .setPositiveButton(if (supportsWifiDirect) "Reset and connect" else "Reconnect") { _, _ ->
                CarPlayBackgroundSession.stop { runOnUiThread { resetWirelessGroup() } }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun resetWirelessGroup() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // Legacy Android uses our local hotspot or the user's existing hotspot, not Wi-Fi Direct.
            connect(true)
            return
        }
        val manager = getSystemService(android.net.wifi.p2p.WifiP2pManager::class.java)
        if (manager == null) { toast("This head unit does not support Wi-Fi Direct."); return }
        val channel = manager.initialize(this, mainLooper, null)
        try {
            manager.requestGroupInfo(channel) { group ->
                if (group == null) { channel.close(); connect(true); return@requestGroupInfo }
                manager.removeGroup(channel, object : android.net.wifi.p2p.WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        val deadline = android.os.SystemClock.elapsedRealtime() + 4000
                        fun waitUntilRemoved() {
                            manager.requestGroupInfo(channel) { remaining ->
                                when {
                                    remaining == null -> { channel.close(); if (!isFinishing && !isDestroyed) connect(true) }
                                    android.os.SystemClock.elapsedRealtime() >= deadline -> {
                                        channel.close(); toast("Wi-Fi Direct is still busy. Close the other projection app and try again.")
                                    }
                                    else -> handler.postDelayed({ waitUntilRemoved() }, 200)
                                }
                            }
                        }
                        waitUntilRemoved()
                    }
                    override fun onFailure(reason: Int) { channel.close(); toast("Could not reset Wi-Fi Direct. Close the other projection app and try again.") }
                })
            }
        } catch (_: SecurityException) {
            channel.close(); permissionHelp("Wireless permissions", "Allow Nearby devices and, on older Android versions, Location before resetting CarPlay Wi-Fi.")
        }
    }

    private fun refreshStatus() {
        val running = CarPlayBackgroundSession.hasSession()
        status?.text = when {
            setupError != null -> "Setup needs attention"
            CarPlayBackgroundSession.active -> "CarPlay connected"
            running -> "Connecting to your iPhone…"
            DiPlayPreferences.phoneAddress(this) != null -> "Ready for ${DiPlayPreferences.phoneName(this)}"
            else -> "Ready when you are"
        }
        if (lastRunning != running) {
            connectButton?.text = if (running) "Open CarPlay" else "Connect phone"
            disconnectButton?.visibility = if (running) View.VISIBLE else View.GONE
            disconnectButton?.isEnabled = true
            lastRunning = running
        }
        connectButton?.isEnabled = setupError == null
    }
    private fun reportFileName() = "DiPlay-${SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())}.txt"

    private fun chooseReportDestination() {
        // Some head units omit or disable DocumentsUI. Launch itself can throw, before
        // the result callback and the background writer's exception handler ever run.
        runCatching { export.launch(reportFileName()) }.onFailure {
            toast(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                "This head unit could not open a save location. Please try saving to Downloads again."
                else "This head unit has no available file picker to save the report.")
        }
    }

    private fun exportDiagnostics(uri: Uri? = null) {
        if (exportInProgress) return
        exportInProgress = true
        exportButton?.apply { isEnabled = false; text = "Saving report…" }
        val appContext = applicationContext
        val fileName = reportFileName()
        Thread({
            val result = runCatching {
                val report = buildString {
                    appendLine("DiPlay ${version()} · private beta diagnostic report")
                    appendLine("Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
                    appendLine("Head unit: ${Build.MANUFACTURER} ${Build.MODEL}")
                    appendLine("Connection: ${if (AirPlayPersistence.loadWirelessEnabled(appContext)) "wireless" else "USB"}")
                    appendLine("Authentication: local experimental beta identity; no remote fallback")
                    appendLine("Saved video preference (may differ from active session): ${if (AirPlayPersistence.loadHevcEnabled(appContext)) "HEVC" else "H.264"}; ${AirPlayPersistence.loadFps(appContext)} fps")
                    appendLine("CarPlay size: ${com.shilapi.xcertplay.airplay.CarPlaySize.fromWidthMillimeters(AirPlayPersistence.loadWidthPhysicalMm(appContext)).label}")
                    appendLine("Saved resolution preference (may differ from active session): ${AirPlayPersistence.loadDisplayScaleTenths(appContext) * 10}%")
                    appendLine("Session: ${if (CarPlayBackgroundSession.active) "active" else if (CarPlayBackgroundSession.hasSession()) "connecting" else "stopped"}")
                    appendLine("Head-unit board: ${Build.BOARD}; hardware: ${Build.HARDWARE}; build: ${Build.DISPLAY}")
                    appendLine()
                    appendLine("--- Last display negotiation (timestamps distinguish it from current settings) ---")
                    appendLine(DisplayDiagnosticSnapshot.report(appContext))
                    appendLine()
                    for (name in SessionLogFile.REPORT_NAMES) {
                        val file = File(appContext.filesDir, "logs/$name")
                        if (file.isFile) {
                            appendLine("--- $name ---")
                            file.useLines { lines -> lines.forEach { line -> DiagnosticRedactor.redact(line)?.let { appendLine(it) } } }
                        }
                    }
                }
                if (uri != null) DiagnosticExportStore.write(appContext.contentResolver, uri, report)
                else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    DiagnosticExportStore.saveToDownloads(appContext.contentResolver, fileName, report)
                } else error("A save location is required")
            }
            runOnUiThread {
                exportInProgress = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                exportButton?.apply { isEnabled = true; text = "Save diagnostic report" }
                if (result.isSuccess) {
                    AlertDialog.Builder(this).setTitle("Diagnostic report saved")
                        .setMessage(if (uri == null) "Downloads/DiPlay/$fileName" else "Your report was saved to the selected location.")
                        .setPositiveButton("Done", null).show()
                } else {
                    AlertDialog.Builder(this).setTitle("Could not save the report")
                        .setMessage("Check that storage is available, or choose another save location.")
                        .setPositiveButton("Choose location") { _, _ -> chooseReportDestination() }
                        .setNegativeButton("Close", null).show()
                }
            }
        }, "diplay-export").start()
    }
    private fun permissionHelp(title: String, body: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(body).setPositiveButton("App settings") { _, _ ->
            openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }.setNegativeButton("Later", null).show()
    }
    private fun openSystem(intent: Intent) { runCatching { startActivity(intent) }.onFailure { toast("Open this setting from your car’s Settings app.") } }
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    private fun version() = packageManager.getPackageInfo(packageName, 0).versionName ?: "0.1.0-beta.1"
    private fun section(parent: LinearLayout, title: String, build: (LinearLayout) -> Unit) {
        val card = card(); card.addView(label(title, 22, TEXT, true).apply { setPadding(0, 0, 0, dp(16)) }); build(card)
        parent.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(18) })
    }
    private fun toggle(parent: LinearLayout, title: String, description: String, value: Boolean, save: (Boolean) -> Unit) {
        val line = row().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(12), 0, dp(12)) }
        val text = column(); text.addView(label(title, 18, TEXT, true)); text.addView(label(description, 14, MUTED).apply { setPadding(0, dp(6), dp(16), 0) })
        line.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        line.addView(Switch(this).apply { contentDescription = title; isChecked = value; minHeight = dp(56); buttonTintList = ColorStateList.valueOf(ACCENT); setOnCheckedChangeListener { _, checked -> save(checked) } })
        parent.addView(line)
    }
    private fun choice(parent: LinearLayout, title: String, options: List<String>, current: Int, save: (Int) -> Unit) {
        var selection = current
        val button = button("$title · ${options[selection]}", false) {}
        button.setOnClickListener {
            var pendingSelection = selection
            AlertDialog.Builder(this).setTitle(title)
                .setSingleChoiceItems(options.toTypedArray(), selection) { _, index -> pendingSelection = index }
                .setPositiveButton(if (CarPlayBackgroundSession.hasSession()) "Apply and reconnect" else "Save") { _, _ ->
                    if (pendingSelection != selection) {
                        selection = pendingSelection
                        save(selection)
                        button.text = "$title · ${options[selection]}"
                        if (CarPlayBackgroundSession.hasSession()) {
                            connect(AirPlayPersistence.loadWirelessEnabled(this))
                        }
                    }
                }.setNegativeButton("Cancel", null).show()
        }
        parent.addView(button, matchButton(0, 60)); parent.addView(space(12))
    }
    private fun card() = column().apply { background = rounded(SURFACE, BORDER); setPadding(dp(24), dp(24), dp(24), dp(24)) }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(-1, -2) }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(-1, -2) }
    private fun label(value: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(color); gravity = Gravity.CENTER_VERTICAL
        typeface = if (bold) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.create("sans-serif", Typeface.NORMAL)
        setLineSpacing(dp(3).toFloat(), 1f)
    }
    private fun button(title: String, primary: Boolean, click: () -> Unit) = Button(this).apply {
        text = title; isAllCaps = false; textSize = 18f; setTextColor(if (primary) BG else TEXT)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        background = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x336F9FD9), rounded(if (primary) ACCENT else SURFACE, if (primary) ACCENT else BORDER), null)
        setPadding(dp(16), 0, dp(16), 0); minHeight = dp(56); stateListAnimator = null
        setOnClickListener { click() }
    }
    private fun rounded(color: Int, stroke: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(20).toFloat(); setStroke(dp(1), stroke) }
    private fun matchButton(top: Int = 0, height: Int = 68) = LinearLayout.LayoutParams(-1, dp(height)).apply { topMargin = dp(top) }
    private fun space(height: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(height)) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    companion object {
        private val BG = Color.rgb(12, 17, 27)
        private val SURFACE = Color.rgb(21, 30, 44)
        private val BORDER = Color.rgb(42, 56, 75)
        private val ACCENT = Color.rgb(166, 200, 255)
        private val TEXT = Color.rgb(241, 245, 252)
        private val MUTED = Color.rgb(168, 182, 202)
        private val WARNING = Color.rgb(255, 196, 128)
    }
}
