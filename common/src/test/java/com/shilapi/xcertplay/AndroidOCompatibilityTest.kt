package com.shilapi.xcertplay

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import android.os.ParcelFileDescriptor
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowContentResolver
import java.io.File

/** Exercise Android 8.0 and 8.1 framework paths without starting USB, Bluetooth or Wi-Fi hardware. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 27], manifest = Config.NONE, shadows = [AndroidOCompatibilityTest.NoWifiDirect::class])
class AndroidOCompatibilityTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun resetPreferences() {
        context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("diplay", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun oldWifiDirectPreferencesFallBackWithoutChangingManualCredentials() {
        assertEquals(WirelessHotspotMode.LOCAL_ONLY_HOTSPOT, AirPlayPersistence.loadWirelessHotspotMode(context))

        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.WIFI_P2P)

        assertEquals(WirelessHotspotMode.LOCAL_ONLY_HOTSPOT, AirPlayPersistence.loadWirelessHotspotMode(context))
        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.MANUAL)
        AirPlayPersistence.saveManualHotspotSsid(context, "Test car hotspot")
        AirPlayPersistence.saveManualHotspotPassphrase(context, "test-only-passphrase")
        AirPlayPersistence.saveManualHotspotBand(context, ManualHotspotBand.GHZ_5)
        AirPlayPersistence.saveManualHotspotChannel(context, 36)
        AirPlayPersistence.saveManualHotspotSecurity(context, ManualHotspotSecurity.WPA2)

        assertEquals(WirelessHotspotMode.MANUAL, AirPlayPersistence.loadWirelessHotspotMode(context))
        assertEquals("Test car hotspot", AirPlayPersistence.loadManualHotspotSsid(context))
        assertEquals("test-only-passphrase", AirPlayPersistence.loadManualHotspotPassphrase(context))
        assertEquals(ManualHotspotBand.GHZ_5, AirPlayPersistence.loadManualHotspotBand(context))
        assertEquals(36, AirPlayPersistence.loadManualHotspotChannel(context))
        assertEquals(ManualHotspotSecurity.WPA2, AirPlayPersistence.loadManualHotspotSecurity(context))
    }

    @Test fun wirelessRecoveryReopensProjectionWithoutTouchingWifiDirect() {
        DiPlayPreferences.savePhone(context, "00:11:22:33:44:55", "Test iPhone")
        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.WIFI_P2P)
        // Attach a framework activity without onCreate: recovery needs no views, bundled identity or radios.
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()

        DiPlayActivity::class.java.getDeclaredMethod("resetWirelessGroup").apply {
            isAccessible = true
        }.invoke(activity)

        val started = shadowOf(activity).nextStartedActivity
        assertNotNull(started)
        assertEquals(CarPlayHostActivity::class.java.name, started.component!!.className)
        assertTrue(AirPlayPersistence.loadWirelessEnabled(context))
        assertEquals(WirelessHotspotMode.LOCAL_ONLY_HOTSPOT, AirPlayPersistence.loadWirelessHotspotMode(context))
    }

    @Test fun selectedDocumentWritesUtf8WithoutUsingDownloadsAndPreservesItOnFailure() {
        val provider = SelectedDocumentProvider(File(context.cacheDir, "android8-report.txt"))
        provider.attachInfo(context, ProviderInfo().apply { authority = "android8.documents" })
        ShadowContentResolver.registerProviderInternal("android8.documents", provider)
        val report = "DiPlay · diagnostic report\nVideo: H.264\n"

        DiagnosticExportStore.write(context.contentResolver, provider.uri, report)

        assertEquals(report, provider.file.readText())
        provider.denyWrite = true
        assertThrows(SecurityException::class.java) {
            DiagnosticExportStore.write(context.contentResolver, provider.uri, "replacement")
        }
        assertEquals(report, provider.file.readText())
    }

    @Test fun sessionServiceStartsWithAndroid8NotificationChannelAndDisconnectAction() {
        val controller = Robolectric.buildService(DiPlaySessionService::class.java).create()
        try {
            val service = controller.get()
            assertEquals(Service.START_NOT_STICKY, service.onStartCommand(Intent(context, DiPlaySessionService::class.java), 0, 1))
            val notification = shadowOf(service).lastForegroundNotification
            assertNotNull(notification)
            assertEquals("DiPlay", notification.extras.getString(Notification.EXTRA_TITLE))
            assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
            assertEquals("Disconnect", notification.actions.single().title.toString())
            assertNotNull(notification.actions.single().actionIntent)
            val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel(notification.channelId)
            assertNotNull(channel)
            assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        } finally {
            controller.destroy()
        }
    }

    private class SelectedDocumentProvider(val file: File) : ContentProvider() {
        val uri: Uri = Uri.parse("content://android8.documents/document/report")
        var denyWrite = false
        override fun onCreate() = true
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            assertEquals(this.uri, uri)
            assertEquals("wt", mode)
            if (denyWrite) throw SecurityException("Selected document is no longer writable")
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_CREATE or
                ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE)
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Use the selected document URI")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
            error("A selected document does not need MediaStore publication")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
            error("A failed export must not delete the user's selected document")
        override fun getType(uri: Uri) = "text/plain"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    }

    @Implements(WifiP2pManager::class)
    class NoWifiDirect {
        @Implementation
        protected fun initialize(context: Context, looper: Looper, listener: WifiP2pManager.ChannelListener?): WifiP2pManager.Channel =
            throw AssertionError("Android 8 recovery must not initialize or remove a Wi-Fi Direct group")
    }
}
