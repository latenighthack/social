package com.latenighthack.social.login.google.service

import com.nimbusds.jose.util.JSONObjectUtils
import java.text.ParseException
import kotlin.test.Test
import kotlin.test.assertFailsWith

class JwtDepthRegressionTest {
    @Test fun `deep untrusted JSON is rejected without overflowing the JVM stack`() {
        val deeplyNested = "{\"x\":".repeat(3000) + "0" + "}".repeat(3000)
        assertFailsWith<ParseException> { JSONObjectUtils.parse(deeplyNested) }
    }
}
