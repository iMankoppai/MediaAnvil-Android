package com.imankoppai.mediaanvil.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

class AppUpdateCheckerTest {
    @Test fun comparesReleaseVersions() {
        assertTrue(AppUpdateChecker.isNewer("v1.5", "1.4"))
        assertTrue(AppUpdateChecker.isNewer("1.4.1", "1.4"))
        assertTrue(AppUpdateChecker.isNewer("v1.03", "1.02"))
        assertFalse(AppUpdateChecker.isNewer("v1.4", "1.4"))
        assertFalse(AppUpdateChecker.isNewer("v1.3.9", "1.4"))
        assertFalse(AppUpdateChecker.isNewer("v1.02", "1.02"))
    }

    @Test fun parsesStandardSha256Files() {
        val hash = "a".repeat(64)
        assertEquals(hash, AppUpdateInstaller.parseExpectedSha256("$hash  MediaAnvil-v1.02.apk\n"))
        assertEquals(hash, AppUpdateInstaller.parseExpectedSha256("SHA256=$hash"))
    }

    @Test fun rejectsMissingSha256() {
        assertThrows(IllegalStateException::class.java) {
            AppUpdateInstaller.parseExpectedSha256("not a checksum")
        }
    }

    @Test fun calculatesFileSha256() {
        val file = File.createTempFile("mediaanvil", ".apk")
        try {
            file.writeText("abc")
            assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                AppUpdateInstaller.sha256(file),
            )
        } finally {
            file.delete()
        }
    }

    @Test fun acceptsOnlyApkMatchingReleaseChecksum() {
        val file = File.createTempFile("mediaanvil-update", ".apk")
        try {
            file.writeText("trusted apk bytes")
            val expected = AppUpdateInstaller.sha256(file)
            AppUpdateInstaller.verifySha256(file, "$expected  MediaAnvil-v1.03.apk")

            file.appendText("tampered")
            val error = assertThrows(IllegalStateException::class.java) {
                AppUpdateInstaller.verifySha256(file, "$expected  MediaAnvil-v1.03.apk")
            }
            assertEquals("sha256_mismatch", error.message)
        } finally {
            file.delete()
        }
    }
}
