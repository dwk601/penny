package com.dwk.flowmoney

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MainActivityImportTest {
    @Test fun advertisedLengthProbeFailureFallsBackToUnknownLength() {
        assertThat(csvAdvertisedLength { error("provider probe failed") }).isEqualTo(-1L)
    }
}
