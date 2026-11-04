package com.shilapi.xcertplay.feedback

import com.shilapi.xcertplay.update.InternetNetworkSelector
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackConnectivityTest {
    @Test
    fun requiresBothInternetAndValidatedCapabilities() {
        assertTrue(InternetNetworkSelector.isValidatedInternet(hasInternet = true, validated = true))
        assertFalse(InternetNetworkSelector.isValidatedInternet(hasInternet = true, validated = false))
        assertFalse(InternetNetworkSelector.isValidatedInternet(hasInternet = false, validated = true))
        assertFalse(InternetNetworkSelector.isValidatedInternet(hasInternet = false, validated = false))
    }
}
