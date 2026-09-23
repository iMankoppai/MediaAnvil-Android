package com.imankoppai.mediaanvil

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.imankoppai.mediaanvil.data.DeviceAudioLibrary
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Guards the Phase 3 acceptance criterion: a new install must not ask for
 * "all files access", and audio must come from the media library instead.
 *
 * These assertions read the installed package's real manifest, so re-adding the
 * permission fails the test rather than shipping silently.
 */
@RunWith(AndroidJUnit4::class)
class Phase3PermissionTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Suppress("DEPRECATION")
    private fun requestedPermissions(): List<String> {
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        )
        return info.requestedPermissions?.toList().orEmpty()
    }

    @Test
    fun appDoesNotRequestAllFilesAccess() {
        assertFalse(
            "MANAGE_EXTERNAL_STORAGE must not be requested any more",
            requestedPermissions().contains(Manifest.permission.MANAGE_EXTERNAL_STORAGE),
        )
    }

    @Test
    fun appRequestsTheMediaLibraryAudioPermission() {
        assertTrue(
            "audio must be readable through the media library",
            requestedPermissions().contains(DeviceAudioLibrary.readPermission),
        )
    }
}
