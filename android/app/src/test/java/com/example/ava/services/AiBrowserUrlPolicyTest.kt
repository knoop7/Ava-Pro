package com.example.ava.services

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiBrowserUrlPolicyTest {
    @Test fun acceptsPublicWebUrls() {
        listOf("https://example.com/article?q=test", "http://8.8.8.8/", "https://[2606:4700:4700::1111]/").forEach {
            assertTrue(it, AiBrowserUrlPolicy.allows(it))
        }
    }

    @Test fun rejectsLocalAndNonWebTargets() {
        listOf("file:///etc/passwd", "content://media/1", "javascript:alert(1)",
            "https://user:password@example.com", "http://localhost/", "http://host.local/",
            "http://127.0.0.1/", "http://127.1/", "http://2130706433/", "http://10.0.0.1/",
            "http://172.16.0.1/", "http://192.168.1.1/", "http://169.254.169.254/",
            "http://100.64.0.1/", "http://[::1]/", "http://[fd00::1]/", "http://[::ffff:127.0.0.1]/",
            "https://example.com\\@127.0.0.1/").forEach {
            assertFalse(it, AiBrowserUrlPolicy.allows(it))
        }
    }

    @Test fun blocksConfiguredHaHostDespiteCaseAndTrailingDot() {
        assertFalse(AiBrowserUrlPolicy.allows("https://HA.EXAMPLE.COM./", setOf("ha.example.com")))
        assertTrue(AiBrowserUrlPolicy.allows("https://other.example.com/", setOf("ha.example.com")))
    }
}
