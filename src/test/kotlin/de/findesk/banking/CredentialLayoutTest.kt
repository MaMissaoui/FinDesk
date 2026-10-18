package de.findesk.banking

import com.sun.jna.Native
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Checks the in-memory layout of [CREDENTIALW] against `wincred.h`. Building a [com.sun.jna.Structure] never
 * loads `Advapi32`, so this runs on every OS and catches an offset drift on the machine that can still build.
 */
class CredentialLayoutTest {
    @Test
    fun `CREDENTIALW matches the 64-bit wincred layout`() {
        assumeTrue(Native.POINTER_SIZE == 8, "wincred.h layout is checked for 64-bit only")

        val credential = CREDENTIALW()

        assertEquals(80, credential.size(), "CREDENTIALW is 80 bytes on 64-bit Windows")
        assertEquals(32, credential.offsetOf("CredentialBlobSize"))
        assertEquals(40, credential.offsetOf("CredentialBlob"))
        assertEquals(48, credential.offsetOf("Persist"))
        assertEquals(72, credential.offsetOf("UserName"))
    }
}
