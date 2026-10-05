package com.dreamdisplayx.platform.server.utils.net

import com.dreamdisplayx.core.protocol.common.packets.ClientHello
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DisplayCapabilityPolicyTest {
    @Test
    fun legacyOrMissingHelloReceivesFlatDisplay() {
        assertFalse(DisplayCapabilityPolicy.conformingFor(displayConforming = true, hello = null))
        assertFalse(DisplayCapabilityPolicy.conformingFor(displayConforming = true, hello = ClientHello()))
    }

    @Test
    fun capableViewerKeepsConformingGeometry() {
        assertTrue(
            DisplayCapabilityPolicy.conformingFor(
                displayConforming = true,
                hello = ClientHello(supportsConforming = true),
            ),
        )
    }

    @Test
    fun flatDisplayNeverBecomesConforming() {
        assertFalse(
            DisplayCapabilityPolicy.conformingFor(
                displayConforming = false,
                hello = ClientHello(supportsConforming = true),
            ),
        )
    }
}
