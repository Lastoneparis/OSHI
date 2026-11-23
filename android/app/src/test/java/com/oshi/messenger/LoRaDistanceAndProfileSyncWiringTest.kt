package com.oshi.messenger

import com.oshi.messenger.ui.viewmodel.LoRaFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * __LORA_NODE_KM_2026_09_23__ / __PROFILE_PICTURE_SYNC_2026_09_23__
 *
 * Three bugs that no formatter test could see, because each one was in the WIRING:
 *  1. `LoRaSettingsScreen` was opened without `deviceLocation`, so every node fell back to
 *     the "~" RSSI band and no real km was ever shown (iOS: `MeshtasticSettingsView.swift:281`).
 *  2. Removing the profile photo never cleared the bytes the repository sends, nor told contacts.
 *  3. A profile update sent before the init load finished carried `profileImageData: null`,
 *     which iOS applies as "photo removed".
 */
class LoRaDistanceAndProfileSyncWiringTest {

    private fun src(path: String): String {
        val candidates = listOf(File("src/main/java/$path"), File("app/src/main/java/$path"))
        return candidates.first { it.exists() }.readText()
    }

    @Test
    fun `the LoRa screen is given this phone's position`() {
        val main = src("com/oshi/messenger/ui/MainActivity.kt")
        val route = main.substringAfter("composable(\"loraSettings\")").substringBefore("composable(\"loraNodeSetup\")")
        assertTrue("loraSettings route must pass deviceLocation", route.contains("deviceLocation ="))
        assertTrue("and read it from the app's location source", route.contains("locationSharingManager.currentLocation"))
    }

    @Test
    fun `distance matches the iOS rendering`() {
        // iOS: km < 1 -> "%.0f m", else "%.1f km" (MeshtasticSettingsView.swift).
        //
        // __DISTANCE_FOLLOWS_THE_LOCALE_2026_10_21__ `imperial` est maintenant
        // EXPLICITE ici. Avant, ce test appelait la surcharge qui lit
        // `Locale.getDefault()`, donc il affirmait un rendu metrique tout en
        // dependant de la machine qui compile: sur une machine reglee en
        // US/GB il rendait "0.5 mi" et echouait. Ce qu'il veut verifier, c'est
        // le rendu METRIQUE face a iOS; il doit donc le demander.
        assertEquals("850 m", LoRaFormat.distanceLabel(0.85, imperial = false))
        assertEquals("1.0 km", LoRaFormat.distanceLabel(1.0, imperial = false))
        // Paris (Notre-Dame) -> Versailles: ~17.2 km great-circle.
        val km = LoRaFormat.distanceKm(48.8530, 2.3499, 48.8049, 2.1204)
        assertEquals(17.5, km, 0.6)
    }

    @Test
    fun `removing the profile photo clears what is sent and tells contacts`() {
        val vm = src("com/oshi/messenger/ui/viewmodel/SettingsViewModel.kt")
        val fn = vm.substringAfter("fun updateProfileImage(").substringBefore("fun setBiometricEnabled")
        val removal = fn.substringAfter("if (uri == null)").substringBefore("uri?.let")
        assertTrue(removal.contains("setMyProfilePicture(null)"))
        assertTrue(removal.contains("broadcastProfileToAllContacts()"))
    }

    @Test
    fun `a profile update never goes out before our own photo is loaded`() {
        val repo = src("com/oshi/messenger/data/repository/MessageRepository.kt")
        val send = repo.substringAfter("private suspend fun sendProfileUpdate(").substringBefore("put(\"type\", \"profile_update\")")
        val load = send.indexOf("if (!myProfilePictureLoaded) loadMyProfilePicture()")
        val read = send.indexOf("val profileBase64 = myProfilePicture")
        assertTrue("sendProfileUpdate must load before reading", load in 0 until read)
    }
}
