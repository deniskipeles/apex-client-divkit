package com.example

import com.example.enginehost.SduiEngineViewModel
import com.example.enginehost.SduiOnFlyBuilder
import com.example.enginehost.TenantWorkspaceSpec
import com.example.enginehost.WorkspaceProvisioner
import com.example.enginehost.demo.DemoCardRepository
import com.example.enginehost.sortedByUsage
import engine.db.CachedSchemaVersion
import engine.config.EngineConfig
import engine.crypto.CanonicalJson
import engine.state.DivVariableValue
import engine.state.EngineStateBridge
import engine.ui.DivChartParser
import engine.ui.DivChartType
import engine.tools.DivActionUrl
import engine.tools.NativeToolsSuite
import engine.tools.Provenance
import engine.tools.vector.HashedTextEmbedding
import engine.tools.vector.VectorMath
import engine.tools.ProvenanceChecker
import engine.tools.TerminalApp
import engine.tools.ToolArgs
import engine.tools.ToolContext
import engine.tools.ToolResult
import engine.tools.impl.SecureStatusTool
import engine.tools.impl.SecureVerifyIdentityTool
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleUnitTest {
    /** Concatenated JSON of every card in the tenant stack, hub first. */
    private fun allCardsJson(spec: TenantWorkspaceSpec): String =
        WorkspaceProvisioner.buildDivKitCardStack(spec)
            .joinToString("\n") { (_, card) -> DemoCardRepository.encode(card) }

    @Test
    fun canonicalJson_sortsKeysAndFormatsNumbers() {
        val input = mapOf(
            "z" to 10.0,
            "a" to "hello",
            "b" to listOf(true, false, null)
        )
        val canonical = CanonicalJson.canonicalize(input)
        assertEquals("""{"a":"hello","b":[true,false,null],"z":10}""", canonical)
    }

    @Test
    fun engineConfig_validatesTenantAndHttps() {
        val raw = """
            {
              "tenant_id": "acme_pos",
              "schema_version": "1.0",
              "mode": "HYBRID",
              "edge_endpoint": "https://edge.sdui-pos.internal/acme_pos",
              "verify_public_key": "dGVzdGtleQ==",
              "capabilities": ["scanner", "printer"]
            }
        """.trimIndent()
        val cfg = EngineConfig.fromVerifiedPayload(raw)
        assertEquals("acme_pos", cfg.tenantId)
        assertEquals(EngineConfig.Mode.HYBRID, cfg.mode)
        assertTrue("scanner" in cfg.capabilities)
    }

    @Test(expected = IllegalArgumentException::class)
    fun engineStateBridge_rejectsNonUserNamespace() {
        EngineStateBridge.pushUpdate("engine.error", DivVariableValue.Str("compromised"))
    }

    @Test
    fun divChartParser_parsesReactiveOrderJsonAndAggregatesSkus() {
        val spec = WorkspaceProvisioner.defaultWorkspaces.first()
        val chartNode = SduiOnFlyBuilder.buildSduiChartBlock(spec, chartType = "bar", dataSource = "orders")
        val mockOrdersJson = """
            [
              {"sku":"ACM-101","item_name":"Wagyu Smash Burger","price":16.5,"qty":2},
              {"sku":"ACM-102","item_name":"Truffle Parmesan Fries","price":8.0,"qty":1},
              {"sku":"ACM-101","item_name":"Wagyu Smash Burger","price":16.5,"qty":1}
            ]
        """.trimIndent()
        val parsed = DivChartParser.parse(
            node = chartNode,
            variables = mapOf("user.orders_json" to DivVariableValue.Str(mockOrdersJson))
        )
        assertEquals(DivChartType.BAR, parsed.chartType)
        assertEquals(2, parsed.points.size)
        val acm101 = parsed.points.first { it.label == "ACM-101" }
        assertEquals(49.5, acm101.value, 0.01)
    }

    @Test
    fun genericScanner_defaultsToBothFormats() = kotlinx.coroutines.runBlocking {
        val outcome = engine.tools.NativeToolsSuite.execute(
            url = "tools://scan/scan",
            payload = buildJsonObject {
                put("simulated_code", "9780201633610")
                put("format", "EAN_13")
            }
        )
        assertTrue(outcome.success)
        assertEquals("scan", outcome.toolId)
        val rawValue = (outcome.outputVariables["user.scanned_code"] as DivVariableValue.Str).value
        assertEquals("9780201633610", rawValue)
        val format = (outcome.outputVariables["user.scanned_format"] as DivVariableValue.Str).value
        assertEquals("EAN_13", format)
        val type = (outcome.outputVariables["user.scanned_type"] as DivVariableValue.Str).value
        assertEquals("1D", type)
    }

    @Test
    fun genericScanner_rejectsFormatMismatchWhenConstrained() = kotlinx.coroutines.runBlocking {
        // Document requests only 1D barcodes, but an optical QR code is scanned
        val outcome = engine.tools.NativeToolsSuite.execute(
            url = "tools://scan/scan",
            payload = buildJsonObject {
                putJsonObject("options") {
                    put("target_format", "barcode")
                }
                put("simulated_code", "https://hospital.org/patient/10492")
                put("format", "QR_CODE")
            }
        )
        assertFalse(outcome.success)
        val status = outcome.resultData.jsonObject["status"]?.jsonPrimitive?.contentOrNull
        assertEquals("FORMAT_MISMATCH", status)
    }

    @Test
    fun genericScanner_acceptsQrWhenConstrainedTo2D() = kotlinx.coroutines.runBlocking {
        val outcome = engine.tools.NativeToolsSuite.execute(
            url = "tools://scan/scan",
            payload = buildJsonObject {
                putJsonObject("options") {
                    put("target_format", "qr")
                }
                put("simulated_code", "apexpos://connect?device=kiosk_01")
                put("format", "QR_CODE")
            }
        )
        assertTrue(outcome.success)
        val type = (outcome.outputVariables["user.scanned_type"] as DivVariableValue.Str).value
        assertEquals("2D", type)
        val format = (outcome.outputVariables["user.scanned_format"] as DivVariableValue.Str).value
        assertEquals("QR_CODE", format)
    }

    @Test
    fun genericCamera_capturesWithDynamicEncoding() = kotlinx.coroutines.runBlocking {
        val tenant = "tenant_clinical_lab"

        // A real capture needs the platform viewfinder. Install a stand-in host that returns a
        // genuine JPEG so this test exercises encoding/transcoding rather than a fake frame.
        val previous = engine.tools.impl.CameraTool.captureSink
        val sample = renderTestJpeg(1600, 1200)
        engine.tools.impl.CameraTool.captureSink = { sample }
        try {
            val outcome = engine.tools.NativeToolsSuite.execute(
                url = "tools://camera/capture",
                payload = buildJsonObject {
                    putJsonObject("options") {
                        put("facing", "front")
                        put("encoding", "both")
                        put("max_dimension", "800")
                    }
                    putJsonObject("output") {
                        put("uri", "user.patient_photo_uri")
                        put("data_url", "user.patient_photo_data_url")
                        put("width", "user.photo_width")
                    }
                },
                tenantId = tenant
            )

            assertTrue(outcome.success)
            assertEquals("camera", outcome.toolId)

            val uri = (outcome.outputVariables["user.patient_photo_uri"] as DivVariableValue.Str).value
            assertTrue(uri.startsWith("file://") && uri.contains("workspaces/$tenant/media"))

            val dataUrl = (outcome.outputVariables["user.patient_photo_data_url"] as DivVariableValue.Str).value
            assertTrue(dataUrl.startsWith("data:image/jpeg;base64,"))

            val width = (outcome.outputVariables["user.photo_width"] as DivVariableValue.IntVal).value
            assertTrue("captured width must respect max_dimension, was $width", width <= 800)
        } finally {
            engine.tools.impl.CameraTool.captureSink = previous
        }
    }

    /** A non-uniform JPEG, so transcoding tests cannot pass on a blank frame. */
    private fun renderTestJpeg(width: Int, height: Int): ByteArray {
        val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.WHITE)
        val paint = android.graphics.Paint().apply { color = android.graphics.Color.BLACK }
        canvas.drawRect(0f, 0f, width / 2f, height / 2f, paint)
        val stream = java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, stream)
        bitmap.recycle()
        return stream.toByteArray()
    }

    @Test
    fun genericCamera_refusesToFabricateAFrameWithoutAHost() = kotlinx.coroutines.runBlocking {
        // Regression: capture used to return a solid-colour bitmap that looked like a photo.
        val previous = engine.tools.impl.CameraTool.captureSink
        engine.tools.impl.CameraTool.captureSink = null
        try {
            val outcome = engine.tools.NativeToolsSuite.execute(
                url = "tools://camera/capture",
                payload = buildJsonObject { },
                tenantId = "tenant_no_camera"
            )
            assertFalse("capture must not report success without a camera host", outcome.success)
            assertEquals(
                "UNSUPPORTED",
                outcome.resultData.jsonObject["status"]?.jsonPrimitive?.contentOrNull
            )
        } finally {
            engine.tools.impl.CameraTool.captureSink = previous
        }
    }

    @Test
    fun genericCamera_fallsBackToTheSharedImageSourceWithCameraAllowed() =
        kotlinx.coroutines.runBlocking {
            val context = androidx.test.core.app.ApplicationProvider
                .getApplicationContext<android.content.Context>()
            engine.db.currentAndroidContext = { context }
            val file = java.io.File(context.cacheDir, "cam_${System.currentTimeMillis()}.jpg")
            file.writeBytes(renderTestJpeg(64, 64))

            val previousCapture = engine.tools.impl.CameraTool.captureSink
            val previousSource = engine.tools.NativeToolsSuite.imageSourceSink
            var allowCamera: Boolean? = null
            engine.tools.impl.CameraTool.captureSink = null
            engine.tools.NativeToolsSuite.imageSourceSink = { allowed ->
                allowCamera = allowed
                file.absolutePath
            }
            try {
                val outcome = engine.tools.NativeToolsSuite.execute(
                    url = "tools://camera/capture",
                    payload = buildJsonObject { },
                    tenantId = "tenant_camera_fallback"
                )
                assertTrue("fallback capture must succeed", outcome.success)
                assertTrue("fallback must offer the camera, not only files", allowCamera == true)
                assertEquals(
                    "image_source",
                    outcome.resultData.jsonObject["source"]?.jsonPrimitive?.contentOrNull ?: ""
                )
            } finally {
                engine.tools.impl.CameraTool.captureSink = previousCapture
                engine.tools.NativeToolsSuite.imageSourceSink = previousSource
                file.delete()
            }
        }

    @Test
    fun genericScan_usesTheLiveViewfinderWhenNoImageIsSupplied() = kotlinx.coroutines.runBlocking {
        // The scan tool must be able to reach a real camera: with no image and no injected
        // payload it consults the host sink and reports whatever was actually decoded.
        val previous = engine.tools.NativeToolsSuite.liveScanSink
        var opened = 0
        engine.tools.NativeToolsSuite.liveScanSink = {
            opened++
            engine.tools.scan.ScanResult(
                rawValue = "4006381333931",
                format = "EAN_13",
                type = "1D"
            )
        }
        try {
            val outcome = engine.tools.NativeToolsSuite.execute(
                url = "tools://scan/scan",
                payload = buildJsonObject {
                    put("camera", "true")
                    putJsonObject("options") { put("target_format", "barcode") }
                }
            )
            assertEquals("the viewfinder should be opened exactly once", 1, opened)
            assertTrue(outcome.success)
            assertEquals(
                "4006381333931",
                outcome.resultData.jsonObject["raw_value"]?.jsonPrimitive?.contentOrNull
            )
            assertEquals(
                "EAN_13",
                outcome.resultData.jsonObject["format"]?.jsonPrimitive?.contentOrNull
            )
        } finally {
            engine.tools.NativeToolsSuite.liveScanSink = previous
        }
    }

    @Test
    fun genericScan_canBeKeptOffCamera() = kotlinx.coroutines.runBlocking {
        // Headless callers opt out with camera=false and must not open the viewfinder.
        val previous = engine.tools.NativeToolsSuite.liveScanSink
        var opened = 0
        engine.tools.NativeToolsSuite.liveScanSink = { opened++; null }
        try {
            val outcome = engine.tools.NativeToolsSuite.execute(
                url = "tools://scan/scan",
                payload = buildJsonObject { put("camera", "false") }
            )
            assertEquals("camera=false must not open the viewfinder", 0, opened)
            assertFalse("a scan with no input must not report success", outcome.success)
            assertEquals(
                "NO_INPUT",
                outcome.resultData.jsonObject["status"]?.jsonPrimitive?.contentOrNull
            )
        } finally {
            engine.tools.NativeToolsSuite.liveScanSink = previous
        }
    }

    @Test
    fun genericCamera_checksCapabilities() = kotlinx.coroutines.runBlocking {
        val outcome = engine.tools.NativeToolsSuite.execute(
            url = "tools://camera/status",
            payload = buildJsonObject {}
        )
        assertTrue(outcome.success)
        assertEquals("READY", outcome.resultData.jsonObject["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun imageClassifier_ranksRealScoresAndDropsWeakMatches() {
        // Uses the bundled model's own quantization (scale 0.0807, zero point 213), so these
        // bytes are the logits the graph really emits: 255 -> +3.39, 213 -> 0.0, 0 -> -17.2.
        // The values are logits, not probabilities, so ranking must softmax them.
        val labels = listOf("tench", "goldfish", "great_white_shark", "tiger_shark")
        val scale = 0.08068591356277466f
        val zeroPoint = 213
        val scores = byteArrayOf(213.toByte(), 255.toByte(), 213.toByte(), 213.toByte())

        val top = engine.tools.vector.ImageClassifier.rank(
            scores = scores,
            scale = scale,
            zeroPoint = zeroPoint,
            labels = labels,
            topK = 3,
            minConfidence = 0.0
        )

        assertEquals(3, top.size)
        assertEquals("goldfish", top[0].label)
        // e^3.39 / (e^3.39 + 3) - a probability, not the raw 3.39 logit.
        assertEquals(0.908, top[0].confidence, 0.01)
        assertTrue(top[0].confidence > top[1].confidence)
        top.forEach {
            assertTrue("confidence must be a probability, got ${it.confidence}", it.confidence > 0.0)
            assertTrue("confidence must be a probability, got ${it.confidence}", it.confidence <= 1.0)
        }

        // A threshold must be able to suppress weak matches rather than padding the list.
        val strict = engine.tools.vector.ImageClassifier.rank(
            scores = scores,
            scale = scale,
            zeroPoint = zeroPoint,
            labels = labels,
            topK = 3,
            minConfidence = 0.5
        )
        assertEquals(1, strict.size)
        assertEquals("goldfish", strict[0].label)
    }

    @Test
    fun imageClassifier_scoresBundledModelOnRealPixels() = kotlinx.coroutines.runBlocking {
        val context = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.content.Context>()
        engine.db.currentAndroidContext = { context }

        val bitmap = android.graphics.Bitmap.createBitmap(224, 224, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.rgb(128, 128, 128))

        // JVM unit tests cannot load the TFLite native library, so real inference is only
        // available on-device. When the runtime is missing the tool must report FAILED
        // (not crash, and not fabricate a prediction) - that is the behaviour asserted here.
        val payload = buildJsonObject {
            val out = java.io.ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            put("uri", "data:image/png;base64," +
                android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP))
            putJsonObject("options") { put("top_k", "3") }
        }
        bitmap.recycle()

        val outcome = NativeToolsSuite.execute("tools://MobileNetV4-small/classify", payload, tenantId = "acme_pos")
        val result = outcome.resultData.jsonObject
        val status = result["status"]?.jsonPrimitive?.contentOrNull

        if (status == "SUCCESS") {
            // Native runtime present: verify a genuine, model-derived label came back.
            val label = result["prediction_label"]?.jsonPrimitive?.contentOrNull
            assertNotNull("missing prediction_label", label)
            assertFalse("label must not be a canned SKU", label!!.contains("Wagyu", ignoreCase = true))
            assertTrue(
                "model should identify itself",
                result["model"]?.jsonPrimitive?.contentOrNull.orEmpty().contains("ImageNet")
            )
        } else {
            assertEquals("FAILED", status)
            assertNull("must not fabricate a prediction", result["prediction_label"]?.jsonPrimitive?.contentOrNull)
        }
    }

    @Test
    fun nativeTools_mobileNetV4ReportsUnsupportedWithoutImageOrCamera() = kotlinx.coroutines.runBlocking {
        val context = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.content.Context>()
        engine.db.currentAndroidContext = { context }
        // Robolectric installs no camera host, so there is no frame to classify.
        engine.tools.impl.CameraTool.captureSink = null

        val outcome = NativeToolsSuite.execute(
            "tools://MobileNetV4-small/classify",
            buildJsonObject { putJsonObject("options") { put("top_k", "3") } }
        )

        // Must not invent a prediction when it has no pixels to look at.
        assertFalse("classify must not succeed without an image", outcome.success)
        assertEquals("NO_INPUT", outcome.resultData.jsonObject["status"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun nativeTools_mobileNetV4ClassifiesAPickedFileThroughTheImageSourceSink() =
        kotlinx.coroutines.runBlocking {
            val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
            engine.db.currentAndroidContext = { context }

            // Write a real PNG to disk and hand its path back through the sink, standing in for
            // the user picking a file. The classifier must consume those pixels.
            val file = java.io.File(context.cacheDir, "pick_${System.currentTimeMillis()}.png")
            val bmp = android.graphics.Bitmap.createBitmap(320, 320, android.graphics.Bitmap.Config.ARGB_8888)
            bmp.eraseColor(android.graphics.Color.rgb(90, 140, 60))
            file.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()

            val previous = NativeToolsSuite.imageSourceSink
            NativeToolsSuite.imageSourceSink = { file.absolutePath }
            try {
                val outcome = NativeToolsSuite.execute(
                    "tools://MobileNetV4-small/classify",
                    buildJsonObject { putJsonObject("options") { put("top_k", "3") } }
                )
                // On the JVM the TFLite native library is absent, so inference reports FAILED
                // rather than a made-up label. What matters here is that the sink was consulted
                // and the image was read, not that a model ran.
                val status = outcome.resultData.jsonObject["status"]?.jsonPrimitive?.contentOrNull
                assertTrue(
                    "picked image must be consumed, got status=$status",
                    status in setOf("FAILED", "SUCCESS", "NO_CODE_FOUND")
                )
            } finally {
                NativeToolsSuite.imageSourceSink = previous
                file.delete()
            }
        }

    @Test
    fun nativeTools_u2NetReportsUnsupportedInsteadOfFabricatingMask() = kotlinx.coroutines.runBlocking {
        val outcome = NativeToolsSuite.execute(
            "tools://u-2-Net.tflite/segment",
            buildJsonObject {
                putJsonObject("options") {
                    put("tensor_input", "320x320x3")
                    put("saliency_cutoff", "0.45")
                }
            }
        )

        // No image and no host sink, so nothing can be segmented. Critically it must not return
        // a mask uri: the old implementation fabricated one without computing anything.
        assertFalse("segmentation must not succeed without an image", outcome.success)
        val result = outcome.resultData.jsonObject
        assertEquals("NO_INPUT", result["status"]?.jsonPrimitive?.contentOrNull)
        assertNull("must not fabricate a mask uri", result["mask_uri"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun nativeTools_u2NetSegmentsARealImageOrFallsBackCleanly() =
        kotlinx.coroutines.runBlocking {
            val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
            engine.db.currentAndroidContext = { context }

            val file = java.io.File(context.cacheDir, "bg_${System.currentTimeMillis()}.png")
            val bmp = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888)
            bmp.eraseColor(android.graphics.Color.WHITE)
            file.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()

            val outcome = NativeToolsSuite.execute(
                "tools://u-2-Net.tflite/segment",
                buildJsonObject { putJsonObject("options") { put("uri", file.absolutePath) } }
            )
            val result = outcome.resultData.jsonObject
            assertEquals("SUCCESS", result["status"]?.jsonPrimitive?.contentOrNull)
            // The output must be a real written file, not a placeholder.
            val uri = result["uri"]?.jsonPrimitive?.contentOrNull
            assertNotNull("must return the processed image", uri)
            assertTrue("must be a file uri, was $uri", uri!!.startsWith("file://"))
            assertTrue("processed file must exist", java.io.File(uri.removePrefix("file://")).exists())
            // On-device this is the bundled U-2-Netp graph; plain JVM tests cannot load
            // tensorflowlite_jni, so the engine must report the honest fallback instead.
            val algorithm = result["algorithm"]?.jsonPrimitive?.contentOrNull ?: ""
            assertTrue(
                "must identify U-2-Net or its fallback, was $algorithm",
                algorithm.contains("U-2-Net")
            )
            file.delete()
            Unit
        }

    @Test
    fun genericHaptics_executesSemanticPatterns() = kotlinx.coroutines.runBlocking {
        // 1. Success Notification Pattern
        val outcome = engine.tools.NativeToolsSuite.execute(
            url = "tools://haptics/success",
            payload = buildJsonObject {
                putJsonObject("output") {
                    put("status", "user.test_haptic_status")
                    put("pattern", "user.test_haptic_pattern")
                }
            }
        )
        assertTrue(outcome.success)
        assertEquals("haptics", outcome.toolId)
        val status = (outcome.outputVariables["user.test_haptic_status"] as DivVariableValue.Str).value
        assertEquals("SUCCESS", status)
        val pattern = (outcome.outputVariables["user.test_haptic_pattern"] as DivVariableValue.Str).value
        assertEquals("success", pattern)
    }

    @Test
    fun genericHaptics_executesW3CWaveformArray() = kotlinx.coroutines.runBlocking {
        // 2. Custom W3C navigator.vibrate([wait, vibrate, wait, vibrate]) timing array
        val outcome = engine.tools.NativeToolsSuite.execute(
            url = "tools://haptics/perform",
            payload = buildJsonObject {
                putJsonObject("options") {
                    put("timings", "[0, 50, 100, 50]")
                    put("amplitude", "220")
                }
                putJsonObject("output") {
                    put("status", "user.waveform_status")
                }
            }
        )
        assertTrue(outcome.success)
        assertEquals("waveform", outcome.resultData.jsonObject["mode"]?.jsonPrimitive?.content)
    }

    @Test
    fun genericHaptics_cancelsVibration() = kotlinx.coroutines.runBlocking {
        val outcome = engine.tools.NativeToolsSuite.execute(
            url = "tools://haptics/stop",
            payload = buildJsonObject {}
        )
        assertTrue(outcome.success)
        assertEquals("CANCELLED", outcome.resultData.jsonObject["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun provenanceChecker_rejectsUnregisteredPublisherTool() = kotlinx.coroutines.runBlocking {
        val urlStr = "div-action://terminal.tool.invoke?tool=restricted.internal.kernel@1&result_var=kernel_state"
        val result = TerminalApp.router.handle(urlStr, Provenance.Publisher)
        assertTrue(result is ToolResult.PermissionDenied)
    }

    @Test
    fun terminalApp_manifestIncludesAllImplementedCapabilities() {
        val manifest = TerminalApp.registry.manifest()
        val expected = listOf(
            "device.haptics@1",
            "device.beeper@1",
            "vision.scan_code@1",
            "vision.capture_photo@1",
            "vision.identify_product@1",
            "vision.embed_image@1",
            "vision.remove_background@1",
            "secure.send@1",
            "secure.status@1",
            "secure.verify_identity@1",
            "auth.biometrics@1",
            "device.clipboard@1",
            "network.push@1",
            "storage.offline_cache@1",
            "network.realtime@1",
            "network.transfer@1",
            "device.printer@1",
            "vision.generate_code@1"
        )
        for (cap in expected) {
            assertTrue("Manifest must include $cap", manifest.has(cap))
        }
        val missing = manifest.satisfies(expected)
        assertTrue("Manifest should satisfy all required tools without missing", missing.isEmpty())
    }

    @Test
    fun biometricsTool_failsClosedWithoutAVerifier() = kotlinx.coroutines.runBlocking {
        // A gate that cannot refuse is not a gate. With no host prompt installed there is no way
        // to verify a supervisor, so the tool must refuse rather than approve every request.
        engine.tools.impl.BiometricsTool.sink = null
        val urlStr = "div-action://terminal.tool.invoke?tool=auth.biometrics@1&required_role=SUPERVISOR&pin=1234&result_var=bio_state"
        val result = TerminalApp.router.handle(urlStr, Provenance.Publisher)
        assertFalse("unverified supervisor must not be authorized", result is ToolResult.Success)
        assertTrue("expected an explicit unsupported/failure, got $result",
            result is ToolResult.Unsupported || result is ToolResult.Failed)
    }

    @Test
    fun biometricsTool_authorizesOnlyAfterHostVerification() = kotlinx.coroutines.runBlocking {
        engine.tools.impl.BiometricsTool.sink = { request ->
            engine.tools.impl.BiometricsTool.Verified(
                authenticated = request.requiredRole == "SUPERVISOR",
                method = "DEVICE_CREDENTIAL_VERIFIED"
            )
        }
        try {
            val urlStr = "div-action://terminal.tool.invoke?tool=auth.biometrics@1&required_role=SUPERVISOR&result_var=bio_state"
            val result = TerminalApp.router.handle(urlStr, Provenance.Publisher)
            assertTrue("verified supervisor should pass, got $result", result is ToolResult.Success)
            val success = result as ToolResult.Success
            assertEquals(true, success.data["authenticated"])
            assertEquals("SUPERVISOR", success.data["role"])
            assertEquals("DEVICE_CREDENTIAL_VERIFIED", success.data["method"])
        } finally {
            engine.tools.impl.BiometricsTool.sink = null
        }
    }

    @Test
    fun biometricsTool_propagatesHostDenial() = kotlinx.coroutines.runBlocking {
        engine.tools.impl.BiometricsTool.sink = {
            engine.tools.impl.BiometricsTool.Verified(
                authenticated = false,
                method = "DEVICE_CREDENTIAL_DENIED"
            )
        }
        try {
            val urlStr = "div-action://terminal.tool.invoke?tool=auth.biometrics@1&required_role=SUPERVISOR&result_var=bio_state"
            val result = TerminalApp.router.handle(urlStr, Provenance.Publisher)
            assertFalse("a denied prompt must not authorize", result is ToolResult.Success)
        } finally {
            engine.tools.impl.BiometricsTool.sink = null
        }
    }

    @Test
    fun clipboardTool_copiesAndReadsData() = kotlinx.coroutines.runBlocking {
        val urlStr = "div-action://terminal.tool.invoke?tool=device.clipboard@1&op=copy&text=ORDER_RECEIPT_1042&result_var=clip_state"
        val result = TerminalApp.router.handle(urlStr, Provenance.Publisher)
        assertTrue(result is ToolResult.Success)
        val success = result as ToolResult.Success
        assertEquals("ok", success.data["state"])
        assertEquals("ORDER_RECEIPT_1042", success.data["text"])
    }

    @Test
    fun pushAndRealtimeTools_executeViaNativeToolsSuite() = kotlinx.coroutines.runBlocking {
        val pushOutcome = engine.tools.NativeToolsSuite.execute(
            "tools://push/send_alert",
            buildJsonObject { putJsonObject("options") { put("message", "Kitchen Ready") } }
        )
        assertTrue(pushOutcome.success)
        assertEquals("push", pushOutcome.toolId)

        val rtOutcome = engine.tools.NativeToolsSuite.execute(
            "tools://realtime-ws/connect",
            buildJsonObject { putJsonObject("options") { put("channel", "pos_stream") } }
        )
        assertTrue(rtOutcome.success)
        assertEquals("realtime-ws", rtOutcome.toolId)
        assertTrue(rtOutcome.outputVariables.containsKey("user.realtime_connection_state"))
    }

    @Test
    fun genericBeeper_synthesizesPureSineWave() = kotlinx.coroutines.runBlocking {
        val outcome = engine.tools.NativeToolsSuite.execute(
            url = "tools://beeper/tone",
            payload = buildJsonObject {
                putJsonObject("options") {
                    put("frequency_hz", "1760")
                    put("duration_ms", "90")
                    put("waveform", "sine")
                }
                putJsonObject("output") {
                    put("status", "user.tone_status")
                    put("frequency_hz", "user.tone_freq")
                }
            }
        )
        assertTrue(outcome.success)
        assertEquals("beeper", outcome.toolId)
        val status = (outcome.outputVariables["user.tone_status"] as DivVariableValue.Str).value
        assertEquals("SUCCESS", status)
        val freq = (outcome.outputVariables["user.tone_freq"] as DivVariableValue.IntVal).value
        assertEquals(1760L, freq)
    }

    @Test
    fun genericBeeper_playsMelodicSequence() = kotlinx.coroutines.runBlocking {
        val outcome = engine.tools.NativeToolsSuite.execute(
            url = "tools://beeper/play",
            payload = buildJsonObject {
                putJsonObject("options") {
                    put("sequence", "880:60,1320:60,1760:100")
                }
            }
        )
        assertTrue(outcome.success)
        assertEquals("sequence", outcome.resultData.jsonObject["mode"]?.jsonPrimitive?.content)
        assertEquals("3", outcome.resultData.jsonObject["steps"]?.jsonPrimitive?.content)
    }

    @Test
    fun queryEngine_compilesMongoFiltersAndExecutes() = kotlinx.coroutines.runBlocking {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val driver = engine.db.WorkspaceDriverFactory(context).create("test_lms_query")
        val apexEngine = engine.db.ApexKitDatabaseEngine(driver)

        // 1. Setup Collection
        val studentsCol = engine.db.models.Collection(
            id = 1L,
            name = "students",
            schema = engine.db.models.CollectionSchema()
        )
        apexEngine.importTenantSchema(engine.db.models.TenantSchemaPackage(collections = listOf(studentsCol)))

        // 2. Insert records
        apexEngine.insertRecord("students", buildJsonObject {
            put("student_number", "ST-01")
            put("grade_level", "Form 3")
            put("age", 16)
        })
        apexEngine.insertRecord("students", buildJsonObject {
            put("student_number", "ST-02")
            put("grade_level", "Form 4")
            put("age", 18)
        })
        apexEngine.insertRecord("students", buildJsonObject {
            put("student_number", "ST-03")
            put("grade_level", "Form 3")
            put("age", 15)
        })

        // 3. Query with Mongo AST: Age >= 16 AND Grade Level IN ["Form 3", "Form 4"]
        val query = engine.db.query.ApexQuery(
            from = "students",
            where = buildJsonObject {
                put("age", buildJsonObject { put("\$gte", 16) })
                put("grade_level", buildJsonObject {
                    put("\$in", buildJsonArray {
                        add(JsonPrimitive("Form 3"))
                        add(JsonPrimitive("Form 4"))
                    })
                })
            },
            sort = "-age"
        )

        val results = engine.db.query.QueryEngine.execute(driver, query, collectionId = 1L)
        assertEquals(2, results.size)
        assertEquals("ST-02", results[0].jsonObject["data"]?.jsonObject?.get("student_number")?.jsonPrimitive?.content)
        assertEquals("ST-01", results[1].jsonObject["data"]?.jsonObject?.get("student_number")?.jsonPrimitive?.content)
    }

    @Test
    fun queryEngine_executesCumulativeSumPipeline() = kotlinx.coroutines.runBlocking {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val driver = engine.db.WorkspaceDriverFactory(context).create("test_pipeline")
        val apexEngine = engine.db.ApexKitDatabaseEngine(driver)

        val salesCol = engine.db.models.Collection(id = 2L, name = "ledger", schema = engine.db.models.CollectionSchema())
        apexEngine.importTenantSchema(engine.db.models.TenantSchemaPackage(collections = listOf(salesCol)))

        apexEngine.insertRecord("ledger", buildJsonObject { put("amount", 50.0) })
        apexEngine.insertRecord("ledger", buildJsonObject { put("amount", 120.0) })
        apexEngine.insertRecord("ledger", buildJsonObject { put("amount", 30.0) })

        val query = engine.db.query.ApexQuery(
            from = "ledger",
            select = listOf(
                engine.db.query.SelectField.Complex(field = "amount", `as` = "amount")
            ),
            sort = "id",
            pipeline = listOf(
                engine.db.query.PipelineStep.CumulativeSum(field = "amount", output_field = "running_total")
            )
        )

        val results = engine.db.query.QueryEngine.execute(driver, query, collectionId = 2L)
        assertEquals(3, results.size)
        assertEquals(50.0, results[0].jsonObject["running_total"]?.jsonPrimitive?.doubleOrNull ?: 0.0, 0.01)
        assertEquals(170.0, results[1].jsonObject["running_total"]?.jsonPrimitive?.doubleOrNull ?: 0.0, 0.01)
        assertEquals(200.0, results[2].jsonObject["running_total"]?.jsonPrimitive?.doubleOrNull ?: 0.0, 0.01)
    }

    @Test
    fun genericLibSignal_endToEndRatchetCycle() = kotlinx.coroutines.runBlocking {
        val tenant = "tenant_logistics_hub"
        val peer = "courier_van_14"

        // 1. Establish session
        val createOutcome = engine.tools.NativeToolsSuite.execute(
            url = "tools://libsignal/create_session",
            payload = buildJsonObject { put("peer_id", peer) },
            tenantId = tenant
        )
        assertTrue(createOutcome.success)
        val sessionId = (createOutcome.outputVariables["user.signal_session_id"] as DivVariableValue.Str).value
        assertTrue(sessionId.startsWith("sig_"))

        // 2. Encrypt generic string (Logistics package dispatch)
        val rawData = "{\"waybill\":\"WB-88910\",\"lat\":-1.286389,\"lon\":36.817223}"
        val encryptOutcome = engine.tools.NativeToolsSuite.execute(
            url = "tools://libsignal/encrypt",
            payload = buildJsonObject {
                put("peer_id", peer)
                put("data", rawData)
            },
            tenantId = tenant
        )
        assertTrue(encryptOutcome.success)
        val ciphertext = (encryptOutcome.outputVariables["user.signal_ciphertext"] as DivVariableValue.Str).value
        assertNotEquals(rawData, ciphertext)

        // 3. Decrypt generic string
        val decryptOutcome = engine.tools.NativeToolsSuite.execute(
            url = "tools://libsignal/decrypt",
            payload = buildJsonObject {
                put("peer_id", peer)
                put("ciphertext", ciphertext)
            },
            tenantId = tenant
        )
        assertTrue(decryptOutcome.success)
        val decrypted = (decryptOutcome.outputVariables["user.signal_plaintext"] as DivVariableValue.Str).value
        assertEquals(rawData, decrypted)
    }

    @Test
    fun genericLibSignal_reestablishingSessionRepeatsRoundTrip() = kotlinx.coroutines.runBlocking {
        // The demo runs create → encrypt → decrypt. Re-running it after the process
        // restarted must not reuse the previous ratchet and fail on the second decrypt.
        val tenant = "tenant_signal_restart"
        val peer = "till_42"
        val payload = "{\"order\":\"#1042\",\"total\":\"18.50\"}"

        repeat(3) { round ->
            val create = engine.tools.NativeToolsSuite.execute(
                url = "tools://libsignal/create_session",
                payload = buildJsonObject { put("peer_id", peer) },
                tenantId = tenant
            )
            assertTrue("create round $round: ${create.summary}", create.success)

            val encrypt = engine.tools.NativeToolsSuite.execute(
                url = "tools://libsignal/encrypt",
                payload = buildJsonObject {
                    put("peer_id", peer)
                    put("data", payload)
                },
                tenantId = tenant
            )
            assertTrue("encrypt round $round: ${encrypt.summary}", encrypt.success)
            val ciphertext = (encrypt.outputVariables["user.signal_ciphertext"] as DivVariableValue.Str).value

            val decrypt = engine.tools.NativeToolsSuite.execute(
                url = "tools://libsignal/decrypt",
                payload = buildJsonObject {
                    put("peer_id", peer)
                    put("ciphertext", ciphertext)
                },
                tenantId = tenant
            )
            assertTrue("decrypt round $round: ${decrypt.summary}", decrypt.success)
            assertEquals(payload, (decrypt.outputVariables["user.signal_plaintext"] as DivVariableValue.Str).value)
        }
    }

    @Test
    fun officialSignalLibrary_endToEndDoubleRatchetEncryption() = kotlinx.coroutines.runBlocking {
        val tenant = "tenant_secure_hub"
        val peer = "client_device_01"

        // 1. Initialize identity using official KeyHelper & store
        val initOutcome = engine.tools.NativeToolsSuite.execute(
            url = "tools://libsignal/setup_identity",
            payload = buildJsonObject {},
            tenantId = tenant
        )
        assertTrue(initOutcome.success)
        assertEquals("IDENTITY_READY", (initOutcome.outputVariables["user.signal_status"] as DivVariableValue.Str).value)

        // 2. Encrypt generic payload via official SessionCipher (Double Ratchet)
        val originalPayload = "{\"auth_token\":\"secret_token_123\",\"access_level\":\"ROOT\"}"
        val encryptOutcome = engine.tools.NativeToolsSuite.execute(
            url = "tools://libsignal/encrypt",
            payload = buildJsonObject {
                put("peer_id", peer)
                put("data", originalPayload)
            },
            tenantId = tenant
        )
        assertTrue(encryptOutcome.success)
        val ciphertext = (encryptOutcome.outputVariables["user.signal_ciphertext"] as DivVariableValue.Str).value
        assertNotEquals(originalPayload, ciphertext)

        // 3. Verify safety number generator produces official 60-digit Signal fingerprint
        val safetyNumber = (encryptOutcome.outputVariables["user.signal_fingerprint"] as DivVariableValue.Str).value
        assertTrue(safetyNumber.isNotBlank())
    }

    @Test
    fun libSignalVerifyIdentity_recordsAndRevokesTrust() = kotlinx.coroutines.runBlocking {
        val tenant = "tenant_verify_hub"
        val peer = "till_07"

        val establish = engine.tools.NativeToolsSuite.execute(
            url = "tools://libsignal/create_session",
            payload = buildJsonObject { put("peer_id", peer) },
            tenantId = tenant
        )
        assertTrue(establish.success)
        val safetyNumber =
            (establish.outputVariables["user.signal_fingerprint"] as DivVariableValue.Str).value

        suspend fun verify(provided: String) = engine.tools.NativeToolsSuite.execute(
            url = "tools://libsignal/verify_identity",
            payload = buildJsonObject {
                put("peer_id", peer)
                put("safety_number", provided)
            },
            tenantId = tenant
        )

        suspend fun status() = engine.tools.NativeToolsSuite.execute(
            url = "tools://libsignal/status",
            payload = buildJsonObject { put("peer_id", peer) },
            tenantId = tenant
        )

        // Before any comparison the peer is explicitly unverified.
        assertEquals("UNVERIFIED", status().resultData.jsonObject["verification_status"]?.jsonPrimitive?.content)

        // Echoing the peer's own number is the positive case and must persist.
        val matched = verify(safetyNumber)
        assertTrue(matched.success)
        assertEquals("VERIFIED", matched.resultData.jsonObject["status"]?.jsonPrimitive?.content)
        assertEquals("VERIFIED", status().resultData.jsonObject["verification_status"]?.jsonPrimitive?.content)

        // A different number is a mismatch and must withdraw trust.
        val mismatched = verify("00-00-00-00-00-00")
        assertFalse(mismatched.success)
        assertEquals("MISMATCH", mismatched.resultData.jsonObject["status"]?.jsonPrimitive?.content)
        assertEquals("MISMATCH", status().resultData.jsonObject["verification_status"]?.jsonPrimitive?.content)

        // Resetting the session clears the record so the peer is unknown again.
        engine.tools.NativeToolsSuite.execute(
            url = "tools://libsignal/reset_session",
            payload = buildJsonObject { put("peer_id", peer) },
            tenantId = tenant
        )
        assertEquals("UNVERIFIED", status().resultData.jsonObject["verification_status"]?.jsonPrimitive?.content)
    }

    @Test
    fun libSignalStatus_doesNotInventASession() = kotlinx.coroutines.runBlocking {
        val tenant = "tenant_status_without_session"
        val outcome = engine.tools.NativeToolsSuite.execute(
            url = "tools://libsignal/status",
            payload = buildJsonObject { put("peer_id", "ghost") },
            tenantId = tenant
        )
        assertTrue(outcome.success)
        assertEquals("NO_SESSION", outcome.resultData.jsonObject["status"]?.jsonPrimitive?.contentOrNull)
        assertFalse(engine.tools.libsignal.LibSignalEngine.hasSession(tenant, "ghost"))
    }

    @Test
    fun libSignalVerifyIdentity_withoutFingerprintFailsClosed() = kotlinx.coroutines.runBlocking {
        val tenant = "tenant_verify_without_fingerprint"
        val peer = "peer_no_proof"
        engine.tools.NativeToolsSuite.execute(
            url = "tools://libsignal/create_session",
            payload = buildJsonObject { put("peer_id", peer) },
            tenantId = tenant
        )
        val outcome = engine.tools.NativeToolsSuite.execute(
            url = "tools://libsignal/verify_identity",
            payload = buildJsonObject { put("peer_id", peer) },
            tenantId = tenant
        )
        assertFalse(outcome.success)
        assertTrue(outcome.resultData.toString().contains("missing safety_number"))
    }

    @Test
    fun secureStatus_reportsSessionStateWithoutCreatingOne() = kotlinx.coroutines.runBlocking {
        val tenant = "tenant_secure_status"
        val peer = "peer_status"
        val empty = SecureStatusTool().invoke(
            ToolArgs(mapOf("peer_id" to peer)),
            ToolContext(tenant)
        )
        assertTrue(empty is ToolResult.Success)
        assertEquals("NO_SESSION", (empty as ToolResult.Success).data["status"])

        engine.tools.NativeToolsSuite.execute(
            url = "tools://libsignal/create_session",
            payload = buildJsonObject { put("peer_id", peer) },
            tenantId = tenant
        )
        val established = SecureStatusTool().invoke(
            ToolArgs(mapOf("peer_id" to peer)),
            ToolContext(tenant)
        )
        assertTrue(established is ToolResult.Success)
        assertEquals("ESTABLISHED", (established as ToolResult.Success).data["status"])
    }

    @Test
    fun secureVerifyIdentity_withoutFingerprintFailsClosed() = kotlinx.coroutines.runBlocking {
        val result = SecureVerifyIdentityTool().invoke(
            ToolArgs(mapOf("peer_id" to "peer_unproofed")),
            ToolContext("tenant_secure_verify")
        )
        assertTrue(result is ToolResult.Failed)
        assertTrue((result as ToolResult.Failed).message.contains("missing provided_fingerprint"))
    }

    @Test
    fun genericImageManipulation_executesSmoothingAndAdjustment() = kotlinx.coroutines.runBlocking {
        val tenant = "tenant_inspection_hub"

        // Execute Smoothing and Color Adjustment Pipeline
        val outcome = engine.tools.NativeToolsSuite.execute(
            url = "tools://image-manipulation/process",
            payload = buildJsonObject {
                put("uri", "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==")
                putJsonArray("pipeline") {
                    addJsonObject {
                        put("op", "smooth")
                        put("type", "bilateral")
                        put("radius", 6)
                    }
                    addJsonObject {
                        put("op", "adjust_color")
                        put("contrast", 1.2)
                        put("brightness", 0.1)
                    }
                }
                putJsonObject("output") {
                    put("uri", "user.processed_uri")
                    put("status", "user.manipulation_status")
                }
            },
            tenantId = tenant
        )

        assertTrue(outcome.success)
        assertEquals("image-manipulation", outcome.toolId)
        val status = (outcome.outputVariables["user.manipulation_status"] as DivVariableValue.Str).value
        assertEquals("SUCCESS", status)
        val uri = (outcome.outputVariables["user.processed_uri"] as DivVariableValue.Str).value
        assertTrue(uri.startsWith("file://") && uri.contains("workspaces/$tenant/media"))
    }

    @Test
    fun genericImageManipulation_removesBackground() = kotlinx.coroutines.runBlocking {
        val outcome = engine.tools.NativeToolsSuite.execute(
            url = "tools://image-manipulation/remove_background",
            payload = buildJsonObject {
                put("uri", "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==")
                put("threshold", 0.50)
            }
        )
        assertTrue(outcome.success)
        assertEquals("SUCCESS", outcome.resultData.jsonObject["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun tenantUsage_sortsByRecencyThenCountThenOriginalOrder() {
        val tenants = listOf("alpha", "beta", "gamma", "delta").map { id ->
            WorkspaceProvisioner.defaultWorkspaces.first().copy(tenantId = id)
        }
        val usage = mapOf(
            // beta used most recently, alpha used most often but longer ago
            "beta" to com.example.enginehost.TenantUsage(visitCount = 1, lastUsedEpochMs = 500L),
            "alpha" to com.example.enginehost.TenantUsage(visitCount = 9, lastUsedEpochMs = 100L),
            "gamma" to com.example.enginehost.TenantUsage(visitCount = 3, lastUsedEpochMs = 300L)
        )

        val ordered = tenants.sortedByUsage(usage).map { it.tenantId }

        // Most recent first (beta, gamma), then most visited (alpha), then never-used (delta) last.
        assertEquals(listOf("beta", "gamma", "alpha", "delta"), ordered)
    }

    @Test
    fun tenantUsageStore_recordsVisitsAndLastOpened() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = com.example.enginehost.TenantUsageStore(context)

        store.recordVisit("acme_pos", nowMs = 1_000L)
        store.recordVisit("acme_pos", nowMs = 2_000L)
        store.setLastOpened("acme_pos", workspaceOpen = true)

        val usage = store.usage()["acme_pos"]
        assertNotNull(usage)
        assertEquals(2, usage!!.visitCount)
        assertEquals(2_000L, usage.lastUsedEpochMs)
        assertEquals("acme_pos", store.lastOpenedTenantId())
        assertTrue(store.wasWorkspaceOpen())

        // Returning to the launcher clears the resume flag but keeps usage history.
        store.setLastOpened(null, workspaceOpen = false)
        assertNull(store.lastOpenedTenantId())
        assertFalse(store.wasWorkspaceOpen())
        assertEquals(2, store.usage()["acme_pos"]?.visitCount)

        // Disconnecting forgets the tenant entirely.
        store.forget("acme_pos")
        assertNull(store.usage()["acme_pos"])
    }

    // ------------------------------------------------------------------
    // tools://net (Ktor) + tools://print (Bluetooth printer/POS)
    // ------------------------------------------------------------------

    @Test
    fun netTool_isRegisteredAndExposesGenericCapability() {
        val manifest = TerminalApp.registry.manifest()
        assertTrue(manifest.has("network.transfer@1"))
        assertTrue(manifest.has("net"))
        val handler = TerminalApp.registry.resolve("network.transfer@1")
        assertNotNull(handler)
        assertEquals("network.transfer@1", handler!!.capability)
        assertTrue(handler.requiredPermissions.contains("android.permission.INTERNET"))
    }

    @Test
    fun printTool_isRegisteredAndAdvertisesBluetoothPermissions() {
        val manifest = TerminalApp.registry.manifest()
        assertTrue(manifest.has("device.printer@1"))
        assertTrue(manifest.has("print"))
        val handler = TerminalApp.registry.resolve("device.printer@1")
        assertNotNull(handler)
        assertEquals("device.printer@1", handler!!.capability)
        assertTrue(handler.requiredPermissions.isNotEmpty())
    }

    @Test
    fun netTool_schemaIsRegisteredWithAllOperations() {
        val schema = engine.tools.NativeToolsSuite.registeredSchemas.first { it.toolId == "net" }
        assertTrue(schema.supportedOperations.containsAll(
            listOf("request", "get", "post", "download", "upload", "serve", "stop", "status", "share", "list", "unshare")
        ))
    }

    @Test
    fun printTool_schemaIsRegisteredWithAllOperations() {
        val schema = engine.tools.NativeToolsSuite.registeredSchemas.first { it.toolId == "print" }
        assertTrue(schema.supportedOperations.containsAll(
            listOf("scan", "pair", "connect", "disconnect", "print", "status")
        ))
    }

    @Test
    fun netTool_registryAndSuiteShareOneHubInstance() {
        // Two invocation paths reach the hub: `tools://net/serve` (suite) and
        // `div-action://...tool=network.transfer@1` (registry). They must observe the same
        // stateful handler, otherwise a hub started through one path is invisible to the other.
        assertSame(NativeToolsSuite.netTool, TerminalApp.registry.resolve("network.transfer@1"))
        assertSame(NativeToolsSuite.printTool, TerminalApp.registry.resolve("device.printer@1"))
    }

    @Test
    fun netTool_statusReportsIdleHub() = kotlinx.coroutines.runBlocking {
        val result = engine.tools.impl.NetTool().invoke(
            args = ToolArgs(mapOf("op" to "status")),
            ctx = ToolContext()
        )
        assertTrue(result is ToolResult.Success)
        val data = (result as ToolResult.Success).data
        assertEquals("IDLE", data["status"])
        assertEquals(false, data["running"])
    }

    @Test
    fun netTool_shareThenListTracksSharedItems() = kotlinx.coroutines.runBlocking {
        val tool = engine.tools.impl.NetTool()
        val shared = tool.invoke(
            args = ToolArgs(mapOf("op" to "share", "name" to "menu.json", "content" to "{}")),
            ctx = ToolContext()
        )
        assertTrue(shared is ToolResult.Success)
        val sharedId = (shared as ToolResult.Success).data["id"] as String

        val listed = tool.invoke(args = ToolArgs(mapOf("op" to "list")), ctx = ToolContext())
        val data = (listed as ToolResult.Success).data
        assertEquals(1, data["count"])

        val removed = tool.invoke(args = ToolArgs(mapOf("op" to "unshare", "id" to sharedId)), ctx = ToolContext())
        assertEquals("REMOVED", (removed as ToolResult.Success).data["status"])
        val empty = tool.invoke(args = ToolArgs(mapOf("op" to "list")), ctx = ToolContext())
        assertEquals(0, (empty as ToolResult.Success).data["count"])
    }

    @Test
    fun netTool_servesLanHubAndStopsCleanly() = kotlinx.coroutines.runBlocking {
        val tool = engine.tools.impl.NetTool()
        val serve = tool.invoke(args = ToolArgs(mapOf("op" to "serve", "port" to "0")), ctx = ToolContext())
        assertTrue(serve is ToolResult.Success)
        val serveData = (serve as ToolResult.Success).data
        assertEquals("SERVING", serveData["status"])
        assertTrue((serveData["port"] as Int) > 0)

        val status = tool.invoke(args = ToolArgs(mapOf("op" to "status")), ctx = ToolContext())
        assertEquals(true, (status as ToolResult.Success).data["running"])

        val stop = tool.invoke(args = ToolArgs(mapOf("op" to "stop")), ctx = ToolContext())
        assertEquals("STOPPED", (stop as ToolResult.Success).data["status"])
    }

    @Test
    fun printTool_scanDeniedWithoutBluetoothPermission() = kotlinx.coroutines.runBlocking {
        val context = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.app.Application>()
        org.robolectric.Shadows.shadowOf(context).denyPermissions(
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.BLUETOOTH_SCAN
        )
        val result = engine.tools.impl.PrintTool().invoke(
            args = ToolArgs(mapOf("op" to "scan")),
            ctx = ToolContext()
        )
        assertTrue(result is ToolResult.PermissionDenied)
    }

    @Test
    fun printTool_scanListsBondedPrintersWhenPermissionGranted() = kotlinx.coroutines.runBlocking {
        val context = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.app.Application>()
        org.robolectric.Shadows.shadowOf(context).grantPermissions(
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.BLUETOOTH_SCAN
        )
        val adapter = (context.getSystemService(android.content.Context.BLUETOOTH_SERVICE)
            as android.bluetooth.BluetoothManager).adapter
        org.robolectric.Shadows.shadowOf(adapter).setEnabled(true)

        val result = engine.tools.impl.PrintTool().invoke(
            args = ToolArgs(mapOf("op" to "scan")),
            ctx = ToolContext()
        )
        assertTrue(result is ToolResult.Success)
        val data = (result as ToolResult.Success).data
        assertEquals("SUCCESS", data["status"])
        assertEquals(0, data["count"])
    }

    @Test
    fun printTool_encodesEscPosReceiptPayload() = kotlinx.coroutines.runBlocking {
        val result = engine.tools.impl.PrintTool().invoke(
            args = ToolArgs(
                mapOf(
                    "op" to "print",
                    "text" to "ACME POS\nOrder #1042\nTotal 24.50",
                    "align" to "center",
                    "cut" to "true"
                )
            ),
            ctx = ToolContext()
        )
        assertTrue(result is ToolResult.Success)
        val data = (result as ToolResult.Success).data
        assertEquals("SUCCESS", data["status"])
        // ESC @ (init) + ESC a 1 (center) + text + ESC d (cut) all present.
        assertTrue((data["bytes"] as Int) > 0)
        val hex = data["preview_hex"] as String
        assertTrue(hex.startsWith("1B 40 1B 61 01"))
    }

    @Test
    fun netTool_dispatchesThroughSuiteWithHttpStatusMapping() = kotlinx.coroutines.runBlocking {
        val outcome = engine.tools.NativeToolsSuite.execute(
            url = "tools://net/status",
            payload = buildJsonObject { }
        )
        assertEquals("net", outcome.toolId)
        val status = (outcome.outputVariables["user.net_status"] as DivVariableValue.Str).value
        assertEquals("IDLE", status)
    }

    @Test
    fun printTool_dispatchesThroughSuiteWithDeviceCountMapping() = kotlinx.coroutines.runBlocking {
        val context = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.app.Application>()
        org.robolectric.Shadows.shadowOf(context).grantPermissions(
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.BLUETOOTH_SCAN
        )
        val outcome = engine.tools.NativeToolsSuite.execute(
            url = "tools://print/scan",
            payload = buildJsonObject { }
        )
        assertEquals("print", outcome.toolId)
        val count = outcome.outputVariables["user.print_device_count"] as DivVariableValue.IntVal
        assertEquals(0L, count.value)
    }

    // ------------------------------------------------------------------
    // tools://scan OCR mode
    // ------------------------------------------------------------------

    @Test
    fun scanTool_exposesOcrOperationAndDefaults() {
        val schema = engine.tools.NativeToolsSuite.registeredSchemas.first { it.toolId == "scan" }
        assertTrue(schema.supportedOperations.contains("ocr"))
        assertEquals("user.ocr_text", schema.defaultOutputMapping["text"])
        assertEquals("user.ocr_confidence", schema.defaultOutputMapping["confidence"])
    }

    @Test
    fun scanTool_routesOcrAliasToScanCapability() = kotlinx.coroutines.runBlocking {
        val payload = buildJsonObject { put("uri", "not-a-real-image") }
        val outcome = engine.tools.NativeToolsSuite.execute(url = "tools://ocr/read", payload = payload)
        assertEquals("scan", outcome.toolId)
        assertEquals("ocr", outcome.operation)
    }

    @Test
    fun scanTool_ocrWithoutContextDegradesGracefully() = kotlinx.coroutines.runBlocking {
        val previous = engine.db.currentAndroidContext
        engine.db.currentAndroidContext = null
        try {
            val outcome = engine.tools.NativeToolsSuite.execute(
                url = "tools://scan/ocr",
                payload = buildJsonObject { put("uri", "content://missing/image") }
            )
            assertEquals("scan", outcome.toolId)
            assertEquals("UNSUPPORTED", outcome.resultData.jsonObject["status"]?.jsonPrimitive?.contentOrNull)
        } finally {
            engine.db.currentAndroidContext = previous
        }
    }

    // ------------------------------------------------------------------
    // tools://code-generate (ZXing)
    // ------------------------------------------------------------------

    @Test
    fun codeGenerateTool_isRegisteredAndAdvertisesCapability() {
        val manifest = TerminalApp.registry.manifest()
        assertTrue(manifest.has("vision.generate_code@1"))
        assertTrue(manifest.has("code-generate"))
        val handler = TerminalApp.registry.resolve("vision.generate_code@1")
        assertNotNull(handler)
        assertEquals("vision.generate_code@1", handler!!.capability)
    }

    @Test
    fun codeGenerateTool_generatesQrMatrixAndImage() = kotlinx.coroutines.runBlocking {
        val outcome = engine.tools.NativeToolsSuite.execute(
            url = "tools://code-generate/generate",
            payload = buildJsonObject {
                put("content", "https://pos.example/order/42")
                put("format", "qr")
                put("width", "256")
                put("height", "256")
            }
        )
        assertEquals("code-generate", outcome.toolId)
        assertEquals("SUCCESS", outcome.resultData.jsonObject["status"]?.jsonPrimitive?.contentOrNull)
        assertEquals("QR_CODE", outcome.resultData.jsonObject["format"]?.jsonPrimitive?.contentOrNull)
        assertEquals("2D", outcome.resultData.jsonObject["kind"]?.jsonPrimitive?.contentOrNull)

        // The raw module matrix is deliberately not published: at 512x512 it serialises to
        // ~262k characters, which bloats every DivKit state update. Only its row count is bound.
        assertFalse(
            "the full module matrix must not be pushed into a DivKit variable",
            outcome.outputVariables.containsKey("user.code_matrix")
        )
        val rows = (outcome.outputVariables["user.code_matrix_rows"] as DivVariableValue.IntVal).value
        assertEquals("QR_CODE", (outcome.outputVariables["user.code_format"] as DivVariableValue.Str).value)
        assertTrue("QR matrix should report its row count, was $rows", rows > 0)
        assertTrue(outcome.resultData.jsonObject["matrix"]!!.jsonPrimitive.content.contains("1"))
    }

    @Test
    fun codeGenerateTool_rendersOneDimensionalBarcode() = kotlinx.coroutines.runBlocking {
        val outcome = engine.tools.NativeToolsSuite.execute(
            url = "tools://code-generate/barcode",
            payload = buildJsonObject {
                put("content", "1234567890128")
                put("format", "EAN_13")
                put("width", "400")
                put("height", "160")
            }
        )
        assertEquals("code-generate", outcome.toolId)
        assertEquals("EAN_13", outcome.resultData.jsonObject["format"]?.jsonPrimitive?.contentOrNull)
        assertEquals("1D", outcome.resultData.jsonObject["kind"]?.jsonPrimitive?.contentOrNull)
        val uri = (outcome.outputVariables["user.code_image_uri"] as DivVariableValue.Str).value
        assertTrue(uri.startsWith("file://"))
    }

    @Test
    fun codeGenerateTool_barcodeIsScannableBackByTheDecoder() = kotlinx.coroutines.runBlocking {
        // Regression: 1D codes were rendered with a 4-module quiet zone, which ZXing (and real
        // scanners) cannot locate, so generated barcodes decoded to nothing.
        val outcome = engine.tools.NativeToolsSuite.execute(
            url = "tools://code-generate/barcode",
            payload = buildJsonObject {
                put("content", "9780201633610")
                put("format", "EAN_13")
                put("width", "400")
                put("height", "160")
            }
        )
        val uri = (outcome.outputVariables["user.code_image_uri"] as DivVariableValue.Str).value
        val bitmap = engine.tools.scan.BarcodeDecoder.loadBitmap(uri)
        assertNotNull("generated barcode image should be loadable", bitmap)
        try {
            val decoded = engine.tools.scan.BarcodeDecoder.decode(bitmap!!)
            assertNotNull("generated EAN-13 must decode back", decoded)
            assertEquals("9780201633610", decoded!!.rawValue)
            assertEquals("EAN_13", decoded.format)
        } finally {
            bitmap!!.recycle()
        }
    }

    @Test
    fun decodeLuminance_decodesA1DBarcodeOnlyWhenSensorRotationIsApplied() = kotlinx.coroutines.runBlocking {
        // Regression for "QR scans but barcodes never do". A camera sensor is mounted rotated, so
        // the Y plane arrives landscape while the user holds the phone upright. A 1D barcode is a
        // single scan line; without rotating the frame back, that line runs the wrong way and
        // ZXing's linear readers find nothing. QR is square, so it survived - which is exactly the
        // reported symptom.
        val outcome = engine.tools.NativeToolsSuite.execute(
            url = "tools://code-generate/barcode",
            payload = buildJsonObject {
                put("content", "9780201633610")
                put("format", "EAN_13")
                put("width", "400")
                put("height", "160")
            }
        )
        val uri = (outcome.outputVariables["user.code_image_uri"] as DivVariableValue.Str).value
        val bitmap = engine.tools.scan.BarcodeDecoder.loadBitmap(uri)
        assertNotNull("generated barcode should load", bitmap)
        try {
            val w = bitmap!!.width
            val h = bitmap.height

            // Dense luminance of the upright code, as if the sensor had been level with it.
            val upright = ByteArray(w * h)
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            for (i in pixels.indices) {
                val c = pixels[i]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                upright[i] = ((r * 299 + g * 587 + b * 114) / 1000).toByte()
            }

            // Rotate 90 degrees clockwise: this is what the sensor actually delivers.
            val rotatedW = h
            val rotatedH = w
            val rotated = ByteArray(rotatedW * rotatedH)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val nx = h - 1 - y
                    val ny = x
                    rotated[ny * rotatedW + nx] = upright[y * w + x]
                }
            }

            val unrotated = engine.tools.scan.BarcodeDecoder
                .decodeLuminance(rotated, rotatedW, rotatedH, 0)
            assertNull(
                "an unrotated sensor frame must not decode a 1D code; that was the bug",
                unrotated
            )

            val corrected = engine.tools.scan.BarcodeDecoder
                .decodeLuminance(rotated, rotatedW, rotatedH, 270)
            assertNotNull("rotating the frame back must recover the barcode", corrected)
            assertEquals("9780201633610", corrected!!.rawValue)
            assertEquals("EAN_13", corrected.format)
        } finally {
            bitmap!!.recycle()
        }
    }

    @Test
    fun rotateLuminance_leavesChromaTailUntouchedAndRotatesOnlyLuma() {
        // A camera YUV frame is luma followed by chroma, so the buffer is larger than
        // `width * height`. Reversing the whole buffer to rotate 180 degrees would pull chroma
        // bytes into the image and destroy it; only the luma region may be touched.
        val w = 4
        val h = 2
        val luma = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val chroma = ByteArray(4) { 0x40 }
        val frame = luma + chroma

        val rotated = engine.tools.scan.BarcodeDecoder.rotateLuminance(frame, w, h, 180)

        assertEquals("dimensions must be unchanged at 180 degrees", w, rotated.width)
        assertEquals(h, rotated.height)
        assertArrayEquals(
            "luma must be reversed; reversing the whole buffer would pull chroma in here",
            byteArrayOf(8, 7, 6, 5, 4, 3, 2, 1),
            rotated.data.copyOfRange(0, w * h)
        )
    }

    @Test
    fun rotateLuminance_swapsDimensionsForQuarterTurns() {
        // 90 and 270 degrees transpose the frame, so width and height swap. Getting this wrong
        // leaves PlanarYUVLuminanceSource reading past the end of a short row.
        val w = 3
        val h = 2
        val frame = ByteArray(w * h) { it.toByte() }

        val cw = engine.tools.scan.BarcodeDecoder.rotateLuminance(frame, w, h, 90)
        assertEquals(h, cw.width)
        assertEquals(w, cw.height)

        val ccw = engine.tools.scan.BarcodeDecoder.rotateLuminance(frame, w, h, 270)
        assertEquals(h, ccw.width)
        assertEquals(w, ccw.height)
    }

    @Test
    fun codeGenerateEngine_keepsAQuietZoneAroundLinearSymbologies() {
        val narrow = engine.tools.code.CodeGeneratorEngine.effectiveMargin(
            com.google.zxing.BarcodeFormat.EAN_13, 4
        )
        assertTrue("1D codes need at least a 10-module quiet zone, was $narrow", narrow >= 10)
        // 2D codes keep whatever the caller asked for.
        assertEquals(
            4,
            engine.tools.code.CodeGeneratorEngine.effectiveMargin(
                com.google.zxing.BarcodeFormat.QR_CODE, 4
            )
        )
    }

    @Test
    fun codeGenerateTool_rejectsMissingContent() = kotlinx.coroutines.runBlocking {
        val result = engine.tools.impl.CodeGenerateTool().invoke(
            args = ToolArgs(emptyMap()),
            ctx = ToolContext()
        )
        assertTrue(result is ToolResult.Failed)
    }

    @Test
    fun codeGenerateEngine_resolvesSymbologyAliases() {
        assertEquals(
            com.google.zxing.BarcodeFormat.QR_CODE,
            engine.tools.code.CodeGeneratorEngine.resolveFormat("qr")
        )
        assertEquals(
            com.google.zxing.BarcodeFormat.CODE_128,
            engine.tools.code.CodeGeneratorEngine.resolveFormat("code-128")
        )
        assertEquals(
            com.google.zxing.BarcodeFormat.DATA_MATRIX,
            engine.tools.code.CodeGeneratorEngine.resolveFormat("datamatrix")
        )
    }

    // ------------------------------------------------------------------
    // tools://keep-awake (device.keep_awake@1)
    // ------------------------------------------------------------------

    @Test
    fun keepAwakeTool_isRegisteredAndAdvertisesWakeLockPermission() {
        val manifest = TerminalApp.registry.manifest()
        assertTrue(manifest.has("device.keep_awake@1"))
        assertTrue(manifest.has("keep-awake"))
        val handler = TerminalApp.registry.resolve("device.keep_awake@1")
        assertNotNull(handler)
        assertEquals("device.keep_awake@1", handler!!.capability)
        assertTrue(handler.requiredPermissions.contains("android.permission.WAKE_LOCK"))
    }

    @Test
    fun screenTool_schemaIsRegisteredWithAllOperations() {
        val schema = engine.tools.NativeToolsSuite.registeredSchemas.first { it.toolId == "screen" }
        assertEquals(
            listOf("keep-awake", "screenshot", "recording", "status"),
            schema.supportedOperations
        )
    }

    @Test
    fun screenTool_keepAwakeEnablesAndReportsStateThroughSink() = kotlinx.coroutines.runBlocking {
        var sinkState: Boolean? = null
        engine.tools.impl.ScreenTool.keepAwakeSink = { enabled ->
            sinkState = enabled
            true
        }
        try {
            val enable = engine.tools.NativeToolsSuite.execute(
                url = "tools://screen/keep-awake",
                payload = buildJsonObject {
                    put("level", "screen")
                    put("timeout_ms", "300000")
                    put("action", "enable")
                }
            )
            assertEquals("screen", enable.toolId)
            assertEquals("ENABLED", enable.resultData.jsonObject["status"]?.jsonPrimitive?.contentOrNull)
            assertEquals("ENABLED", (enable.outputVariables["user.screen_status"] as DivVariableValue.Str).value)
            assertEquals(true, sinkState)

            val disable = engine.tools.NativeToolsSuite.execute(
                url = "tools://screen/keep-awake",
                payload = buildJsonObject { put("action", "disable") }
            )
            assertEquals("DISABLED", disable.resultData.jsonObject["status"]?.jsonPrimitive?.contentOrNull)
            assertEquals(false, sinkState)
        } finally {
            engine.tools.impl.ScreenTool.keepAwakeSink = null
        }
    }

    @Test
    fun screenTool_screenshotUsesTheHostSink() = kotlinx.coroutines.runBlocking {
        engine.tools.impl.ScreenTool.screenshotSink = { tenantId, format, quality ->
            assertEquals("tenant_screen", tenantId)
            assertEquals("png", format)
            assertEquals(95, quality)
            engine.tools.impl.ScreenTool.Screenshot(
                path = "/tmp/shot.png",
                uri = "file:///tmp/shot.png",
                width = 1080,
                height = 2400,
                bytes = 42_000,
                imageId = "shot_1"
            )
        }
        try {
            val result = engine.tools.NativeToolsSuite.execute(
                url = "tools://screen/screenshot",
                payload = buildJsonObject { put("format", "png"); put("quality", "95") },
                tenantId = "tenant_screen"
            )
            assertEquals("screen", result.toolId)
            assertEquals("CAPTURED", result.resultData.jsonObject["status"]?.jsonPrimitive?.contentOrNull)
            assertEquals("file:///tmp/shot.png", (result.outputVariables["user.screen_media_uri"] as DivVariableValue.Str).value)
            assertEquals(1080L, (result.outputVariables["user.screen_width"] as DivVariableValue.IntVal).value)
            assertEquals("shot_1", (result.outputVariables["user.screen_image_id"] as DivVariableValue.Str).value)
        } finally {
            engine.tools.impl.ScreenTool.screenshotSink = null
        }
    }

    @Test
    fun screenTool_screenshotFailureSurfacesTheReason() = kotlinx.coroutines.runBlocking {
        engine.tools.impl.ScreenTool.screenshotSink = { _, _, _ ->
            throw IllegalStateException("capture host exploded")
        }
        try {
            val result = engine.tools.NativeToolsSuite.execute(
                url = "tools://screen/screenshot",
                payload = buildJsonObject {},
                tenantId = "tenant_screen"
            )
            assertEquals("FAILED", result.resultData.jsonObject["status"]?.jsonPrimitive?.contentOrNull)
            assertEquals(
                "capture host exploded",
                (result.outputVariables["user.screen_error"] as DivVariableValue.Str).value
            )
            assertFalse(result.success)
        } finally {
            engine.tools.impl.ScreenTool.screenshotSink = null
        }
    }

    @Test
    fun screenTool_recordingStartsAndStopsThroughHostSink() = kotlinx.coroutines.runBlocking {
        engine.tools.impl.ScreenTool.recordingStartSink = { tenantId ->
            assertEquals("tenant_screen", tenantId)
            engine.tools.impl.ScreenTool.RecordingStart("rec_1", 1000L, 1080, 2400)
        }
        engine.tools.impl.ScreenTool.recordingStopSink = {
            engine.tools.impl.ScreenTool.RecordingStop(
                path = "/tmp/rec.mp4",
                uri = "file:///tmp/rec.mp4",
                durationMs = 5_000L,
                width = 1080,
                height = 2400,
                bytes = 900_000L,
                id = "rec_1"
            )
        }
        try {
            val start = engine.tools.NativeToolsSuite.execute(
                url = "tools://screen/recording",
                payload = buildJsonObject { put("action", "start") },
                tenantId = "tenant_screen"
            )
            assertEquals("RECORDING", start.resultData.jsonObject["status"]?.jsonPrimitive?.contentOrNull)
            assertEquals("true", start.resultData.jsonObject["recording"]?.jsonPrimitive?.contentOrNull)

            val stop = engine.tools.NativeToolsSuite.execute(
                url = "tools://screen/recording",
                payload = buildJsonObject { put("action", "stop") },
                tenantId = "tenant_screen"
            )
            assertEquals("RECORDED", stop.resultData.jsonObject["status"]?.jsonPrimitive?.contentOrNull)
            assertEquals("file:///tmp/rec.mp4", (stop.outputVariables["user.screen_media_uri"] as DivVariableValue.Str).value)
            assertEquals(5_000L, (stop.outputVariables["user.screen_duration_ms"] as DivVariableValue.IntVal).value)
        } finally {
            engine.tools.impl.ScreenTool.recordingStartSink = null
            engine.tools.impl.ScreenTool.recordingStopSink = null
        }
    }

    @Test
    fun screenTool_withoutHostReportsUnsupported() = kotlinx.coroutines.runBlocking {
        engine.tools.impl.ScreenTool.screenshotSink = null
        engine.tools.impl.ScreenTool.recordingStartSink = null
        engine.tools.impl.ScreenTool.recordingStopSink = null
        val shot = engine.tools.NativeToolsSuite.execute(url = "tools://screen/screenshot", payload = buildJsonObject {})
        assertEquals("UNSUPPORTED", shot.resultData.jsonObject["status"]?.jsonPrimitive?.contentOrNull)
        assertFalse(shot.success)
    }

    @Test
    fun screenTool_statusReportsCapabilities() = kotlinx.coroutines.runBlocking {
        val status = engine.tools.NativeToolsSuite.execute(url = "tools://screen/status", payload = buildJsonObject {})
        assertEquals("screen", status.toolId)
        assertTrue(status.resultData.jsonObject.containsKey("supported_levels"))
        assertTrue(status.resultData.jsonObject.containsKey("supported_operations"))
        assertTrue(status.resultData.jsonObject.containsKey("recording_supported"))
    }

    @Test
    fun demoCard_surfacesScreenBlock() {
        val spec = WorkspaceProvisioner.defaultWorkspaces.first()
        val json = allCardsJson(spec)
        assertTrue(json.contains("tools://screen/keep-awake"))
        assertTrue(json.contains("tools://screen/screenshot"))
        assertTrue(json.contains("tools://screen/recording"))
    }

    // ------------------------------------------------------------------
    // Built-in demo workspace showcase (DivKit card)
    // ------------------------------------------------------------------

    @Test
    fun demoCard_showcasesScannerOcrAndCodeGeneratorBlocks() {
        val spec = WorkspaceProvisioner.defaultWorkspaces.first()
        val json = allCardsJson(spec)

        // OCR is reached through the unified scanner's image-reading mode
        assertTrue(json.contains("tools://scan/ocr"))
        // The barcode/QR generator block is present
        assertTrue(json.contains("tools://code-generate/generate"))
        assertTrue(json.contains("tools://code-generate/barcode"))
        // Legacy split scanner URIs must not appear
        assertFalse(json.contains("tools://scan-barcode"))
        assertFalse(json.contains("tools://scan-qr"))
    }

    @Test
    fun demoCard_surfacesEveryRegisteredNativeTool() {
        val spec = WorkspaceProvisioner.defaultWorkspaces.first()
        val json = allCardsJson(spec)

        // The showcase must exercise the full tool inventory, so every registered
        // tool schema has to appear as a tools:// trigger in the built-in card.
        val missing = NativeToolsSuite.registeredSchemas
            .map { it.toolId }
            .filterNot { json.contains("tools://${it}/") }

        assertTrue("Demo card does not showcase these tools: $missing", missing.isEmpty())
    }

    @Test
    fun demoCard_surfacesNativeIconScheme() {
        val spec = WorkspaceProvisioner.defaultWorkspaces.first()
        val json = allCardsJson(spec)

        // The showcase must exercise the icons:// scheme in both node and action positions.
        assertTrue("Demo card does not reference icons://native/", json.contains("icons://native/"))
        listOf("cart", "bell", "receipt", "payment", "stock", "printer", "scan", "warehouse")
            .forEach { name ->
                assertTrue("Demo card missing icons://native/$name", json.contains("icons://native/$name"))
            }
    }

    @Test
    fun demoCard_declaresEveryReferencedUserVariable() {
        val spec = WorkspaceProvisioner.defaultWorkspaces.first()
        val json = allCardsJson(spec)

        val referenced = Regex("""@\{(user\.[a-zA-Z0-9_.]+)\}""")
            .findAll(json).map { it.groupValues[1] }.toSet()
        // Known = declared variables plus variables written by action output mappings
        // (e.g. db.count pushes user.order_count at runtime).
        val known = Regex(""""(user\.[a-zA-Z0-9_.]+)"""")
            .findAll(json).map { it.groupValues[1] }.toSet()

        val missing = referenced - known
        assertTrue("Demo card references undeclared variables: $missing", missing.isEmpty())
    }

    @Test
    fun cardStack_startsWithEntryHubThenEveryToolCard() {
        val spec = WorkspaceProvisioner.defaultWorkspaces.first()
        val stack = WorkspaceProvisioner.buildDivKitCardStack(spec)

        // Route index 0 is the entry hub.
        assertEquals(DemoCardRepository.hubCardId(spec), stack.first().first)
        // Every tool card follows, and no id repeats.
        assertEquals(stack.size, stack.map { it.first }.toSet().size)
        assertEquals(
            DemoCardRepository.toolCardOrder,
            stack.drop(1).map { (id, _) -> id.removePrefix("tool_") }
        )
    }

    @Test
    fun entryHub_linksToEveryToolCard() {
        val spec = WorkspaceProvisioner.defaultWorkspaces.first()
        val stack = WorkspaceProvisioner.buildDivKitCardStack(spec)
        val hubJson = DemoCardRepository.encode(stack.first().second)

        // The hub must not inline tool markup: it links out instead.
        assertFalse("Hub still inlines tools:// actions", hubJson.contains("tools://"))

        val links = Regex("""nav://push\?id=([a-z0-9_]+)""")
            .findAll(hubJson).map { it.groupValues[1] }.toSet()
        assertEquals(stack.drop(1).map { it.first }.toSet(), links)
    }

    @Test
    fun toolCard_carriesBackActionToHub() {
        val spec = WorkspaceProvisioner.defaultWorkspaces.first()
        val stack = WorkspaceProvisioner.buildDivKitCardStack(spec)

        stack.drop(1).forEach { (id, card) ->
            val json = DemoCardRepository.encode(card)
            assertTrue("$id is missing nav://pop", json.contains("nav://pop"))
        }
    }

    @Test
    fun demoImageManipulationBlock_suppliesDecodableSourceImage() = kotlinx.coroutines.runBlocking {
        val spec = WorkspaceProvisioner.defaultWorkspaces.first()
        val block = SduiOnFlyBuilder.buildImageManipulationToolBlock(spec)

        val buttons = mutableListOf<JsonObject>()
        fun collect(node: JsonElement) {
            when (node) {
                is JsonObject -> {
                    if (node["action"]?.jsonObject?.get("url") != null) buttons.add(node)
                    node.values.forEach { collect(it) }
                }
                is JsonArray -> node.forEach { collect(it) }
                else -> Unit
            }
        }
        collect(block)

        // Every operation the engine supports should be reachable from the card, and the two
        // canonical triggers must always be there.
        val ops = buttons.mapNotNull {
            it["action"]?.jsonObject?.get("payload")?.jsonObject
                ?.get("options")?.jsonObject?.get("op")?.jsonPrimitive?.contentOrNull
        }.toSet()
        assertTrue("card is missing the remove_background trigger", "remove_background" in ops)
        assertTrue("card is missing the smooth trigger", "smooth" in ops)
        assertTrue("card should expose the full op surface, got $ops", ops.size >= 8)

        var sourceBacked = 0
        buttons.forEach { button ->
            val action = button["action"]!!.jsonObject
            val payload = action["payload"]!!.jsonObject
            val options = payload["options"]!!.jsonObject
            val source = options["uri"]?.jsonPrimitive?.contentOrNull

            // One button intentionally omits a source: it prompts the user for a photo.
            if (source == null) {
                assertTrue(
                    "only the photo-prompt trigger may omit a source",
                    button["id"]?.jsonPrimitive?.contentOrNull?.contains("pick") == true
                )
                return@forEach
            }
            sourceBacked++

            assertTrue(
                "source image must be decodable, was '$source'",
                source.startsWith("file://") || source.startsWith("data:image")
            )

            val outcome = NativeToolsSuite.execute(
                url = action["url"]!!.jsonPrimitive.content,
                payload = payload
            )
            assertEquals(
                "image manipulation failed for ${button["id"]}: ${outcome.resultData}",
                "SUCCESS",
                outcome.resultData.jsonObject["status"]?.jsonPrimitive?.contentOrNull
            )
            assertTrue(outcome.outputVariables.containsKey("user.processed_image_uri"))
        }
        assertTrue("expected most triggers to carry a demo source", sourceBacked >= 8)
    }

    @Test
    fun demoCardRefresh_isDrivenByCardContentNotAVersionConstant() {
        fun version(id: Long, json: String, active: Boolean) =
            CachedSchemaVersion(
                versionId = id,
                schemaVersion = "1.4",
                jti = "jti-$id",
                signedEnvelope = "{}",
                verifiedPayload = json,
                receivedAt = id,
                isActive = active,
                summary = "s$id"
            )

        // Stale content on the newest active row -> refresh.
        assertTrue(
            SduiEngineViewModel.needsDemoCardRefresh(
                listOf(version(1, "old-card", true)), "new-card"
            )
        )
        // Already current -> no churn on every launch.
        assertFalse(
            SduiEngineViewModel.needsDemoCardRefresh(
                listOf(version(1, "new-card", true)), "new-card"
            )
        )
        // User rolled back to an older row -> leave their choice alone.
        assertFalse(
            SduiEngineViewModel.needsDemoCardRefresh(
                listOf(version(1, "old-card", true), version(2, "newer-card", false)),
                "new-card"
            )
        )
        // No cached versions at all -> seed.
        assertTrue(SduiEngineViewModel.needsDemoCardRefresh(emptyList(), "new-card"))
    }

    @Test
    fun demoOcrBlock_suppliesDecodableSourceImage() = kotlinx.coroutines.runBlocking {
        val spec = WorkspaceProvisioner.defaultWorkspaces.first()
        val block = SduiOnFlyBuilder.buildScannerToolBlock(spec)

        var ocrButton: JsonObject? = null
        fun collect(node: JsonElement) {
            when (node) {
                is JsonObject -> {
                    val url = node["action"]?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
                    if (url != null && url.contains("/ocr")) ocrButton = node
                    node.values.forEach { collect(it) }
                }
                is JsonArray -> node.forEach { collect(it) }
                else -> Unit
            }
        }
        collect(block)

        val action = ocrButton?.get("action")?.jsonObject
        assertNotNull("expected an OCR trigger in the scanner demo block", action)
        val payload = action!!["payload"]!!.jsonObject
        val options = payload["options"]?.jsonObject ?: buildJsonObject { }

        // The card no longer hardcodes a bundled sample: tapping it asks the user for a photo,
        // either from the camera or an existing file, the same way the scanners work.
        assertNull(
            "OCR card should not pin a sample image; it must ask the user for one",
            options["uri"]?.jsonPrimitive?.contentOrNull
        )

        // Drive the tool the way the host does: install a sink that returns a real file and
        // confirm OCR consumes those pixels instead of failing to find an image.
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        engine.db.currentAndroidContext = { context }
        val file = java.io.File(context.cacheDir, "ocr_${System.currentTimeMillis()}.png")
        val bmp = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888)
        bmp.eraseColor(android.graphics.Color.WHITE)
        file.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()

        val previous = NativeToolsSuite.imageSourceSink
        var sinkCalls = 0
        NativeToolsSuite.imageSourceSink = { sinkCalls++; file.absolutePath }
        try {
            val outcome = NativeToolsSuite.execute(
                url = action["url"]!!.jsonPrimitive.content,
                payload = payload
            )
            assertEquals("the OCR card must go through the image-source sink", 1, sinkCalls)
            // ML Kit is unavailable under Robolectric, so this asserts the tool reached the
            // recognizer with a real image rather than failing to acquire one.
            val status = outcome.resultData.jsonObject["status"]?.jsonPrimitive?.contentOrNull
            val error = outcome.resultData.jsonObject["error"]?.jsonPrimitive?.contentOrNull ?: ""
            assertTrue(
                "OCR demo failed on the source image, not the recognizer: $status / $error",
                status == "SUCCESS" || error.contains("MlKitContext")
            )
        } finally {
            NativeToolsSuite.imageSourceSink = previous
            file.delete()
        }
    }

    @Test
    fun demoCards_supplyTheArgumentsTheirToolsRequire() = kotlinx.coroutines.runBlocking {
        // Guards the class of bug where a card button dispatches a tool without the
        // argument the handler needs (a missing `uri`, `text`, or share payload), so the
        // button silently fails instead of doing what its label promises.
        val spec = WorkspaceProvisioner.defaultWorkspaces.first()
        val missingArgument = Regex("""(missing \S+ argument|requires a \S+)""", RegexOption.IGNORE_CASE)

        val deadEnds = mutableListOf<String>()
        DemoCardRepository.toolCardOrder.forEach { name ->
            val card = DemoCardRepository.toolCard(spec, name) ?: return@forEach
            collectToolActions(card).forEach { action ->
                val url = action["url"]!!.jsonPrimitive.content
                val payload = action["payload"] as? JsonObject ?: buildJsonObject { }
                // Tools that need a device-native library throw here; only argument
                // errors are this test's concern.
                val error = runCatching {
                    NativeToolsSuite.execute(url = url, payload = payload)
                        .resultData.jsonObject["error"]?.jsonPrimitive?.contentOrNull
                }.getOrElse { it.message }
                if (error != null && missingArgument.containsMatchIn(error)) {
                    deadEnds.add("$name -> $url: $error")
                }
            }
        }
        // The LAN hub card really starts a server; leave the shared NetTool idle again.
        runCatching { NativeToolsSuite.execute(url = "tools://net/stop", payload = buildJsonObject { }) }

        assertTrue("Demo buttons dispatch tools without their required arguments: $deadEnds", deadEnds.isEmpty())
    }

    @Test
    fun demoCards_bindOnlyFieldsTheirToolReturns() = kotlinx.coroutines.runBlocking {
        // A card that maps `status` while the tool returns `state` leaves the bound
        // variable permanently blank: the button appears to do nothing. Compare each
        // action's `output` keys against the fields the handler actually produced.
        val spec = WorkspaceProvisioner.defaultWorkspaces.first()
        val unbound = mutableListOf<String>()

        DemoCardRepository.toolCardOrder.forEach { name ->
            val card = DemoCardRepository.toolCard(spec, name) ?: return@forEach
            collectToolActions(card).forEach { action ->
                val url = action["url"]!!.jsonPrimitive.content
                val payload = action["payload"] as? JsonObject ?: return@forEach
                val mapping = payload["output"] as? JsonObject ?: return@forEach
                if (mapping.isEmpty()) return@forEach

                // Tools needing device hardware report UNSUPPORTED here; those carry
                // `status`/`reason` and are not this test's concern.
                val data = runCatching {
                    NativeToolsSuite.execute(url = url, payload = payload).resultData.jsonObject
                }.getOrNull() ?: return@forEach
                if (data["status"]?.jsonPrimitive?.contentOrNull == "UNSUPPORTED") return@forEach
                // A failed run (no camera, no source image, no peer session) returns only
                // `status`/`error`; there is no result to bind, so skip it.
                if (data["error"] != null) return@forEach
                // Permission-gated tools (Bluetooth printing) return `status`/`permission`
                // when the host has not granted access; that is not a card bug.
                if (data["status"]?.jsonPrimitive?.contentOrNull == "DENIED") return@forEach

                mapping.keys.forEach { field ->
                    if (data[field] == null) unbound.add("$name -> $url: no '$field' in ${data.keys}")
                }
            }
        }
        runCatching { NativeToolsSuite.execute(url = "tools://net/stop", payload = buildJsonObject { }) }

        assertTrue("Demo cards bind fields their tools never return: $unbound", unbound.isEmpty())
    }

    /** Every `tools://` action object nested anywhere in [card]. */
    private fun collectToolActions(card: JsonObject): List<JsonObject> {
        val found = mutableListOf<JsonObject>()
        fun walk(node: JsonElement) {
            when (node) {
                is JsonObject -> {
                    val action = node["action"] as? JsonObject
                    val url = action?.get("url")?.jsonPrimitive?.contentOrNull
                    if (url != null && url.startsWith("tools://")) found.add(action)
                    node.values.forEach(::walk)
                }
                is JsonArray -> node.forEach(::walk)
                else -> Unit
            }
        }
        walk(card)
        return found
    }

    @Test
    fun demoToolOutputMappings_referenceFieldsTheToolsActuallyReturn() {
        // Guards the class of bug where a card maps e.g. `capture_uri` while the tool
        // returns `uri`, leaving the bound variable permanently blank.
        val camera = NativeToolsSuite.registeredSchemas.first { it.toolId == "camera" }
        assertEquals(
            setOf("user.camera_capture_uri"),
            camera.defaultOutputMapping.values.toSet()
        )
        assertTrue(
            "camera mapping key must match CameraTool's result field",
            camera.defaultOutputMapping.containsKey("uri")
        )
    }

    @Test
    fun demoCodeGenerateBlock_triggersGeneratorAndMapsOutputs() = kotlinx.coroutines.runBlocking {
        val spec = WorkspaceProvisioner.defaultWorkspaces.first()
        val block = SduiOnFlyBuilder.buildCodeGenerateToolBlock(spec)
        // Locate by URL rather than by position: the card now carries a preview image, so item
        // indices shift whenever the layout changes.
        val action = collectToolActions(block)
            .firstOrNull { it["url"]?.jsonPrimitive?.content == "tools://code-generate/generate" }
            ?: throw AssertionError("generator card has no generate trigger")
        val url = action["url"]!!.jsonPrimitive.content
        val payload = action["payload"]!!.jsonObject

        assertEquals("tools://code-generate/generate", url)

        val outcome = engine.tools.NativeToolsSuite.execute(url = url, payload = payload)
        assertEquals("SUCCESS", outcome.resultData.jsonObject["status"]?.jsonPrimitive?.contentOrNull)
        assertTrue(outcome.outputVariables.containsKey("user.code_image_uri"))
        assertTrue(outcome.outputVariables.containsKey("user.code_format"))
        assertEquals("QR_CODE", (outcome.outputVariables["user.code_format"] as DivVariableValue.Str).value)
    }

    @Test
    fun demoScannerBlock_ocrActionRoutesToScanCapability() = kotlinx.coroutines.runBlocking {
        val spec = WorkspaceProvisioner.defaultWorkspaces.first()
        val block = SduiOnFlyBuilder.buildScannerToolBlock(spec)

        // The card mixes live-camera and sample-decode buttons, so locate the OCR trigger by
        // its URL rather than by position.
        val action = collectToolActions(block)
            .firstOrNull { it["url"]?.jsonPrimitive?.content == "tools://scan/ocr" }
            ?: throw AssertionError("scanner card has no OCR trigger")

        val outcome = engine.tools.NativeToolsSuite.execute(
            url = "tools://scan/ocr",
            payload = action["payload"]!!.jsonObject
        )
        assertEquals("scan", outcome.toolId)
        assertEquals("ocr", outcome.operation)
    }

    @Test
    fun demoCard_scanVariablesAreHydratedByEngineStateBridge() {
        val spec = WorkspaceProvisioner.defaultWorkspaces.first()
        val json = WorkspaceProvisioner.buildDefaultDivKitCardJson(spec)
        // The scanner/OCR/generator variables the card binds must be seeded, not blank
        listOf(
            "user.scanned_code", "user.scanned_format", "user.scanned_type", "user.scan_status",
            "user.ocr_text", "user.ocr_confidence", "user.ocr_block_count",
            "user.code_image_uri", "user.code_format", "user.code_kind", "user.code_content",
            "user.code_id", "user.code_status"
        ).forEach { name ->
            assertTrue("Demo card must declare $name", json.contains("\"$name\""))
        }
    }

    @Test
    fun embeddingSimilarity_scoresIdenticalVectorsAsOne() = kotlinx.coroutines.runBlocking {
        val packed = packedFloats(1f, 2f, 3f)
        val outcome = NativeToolsSuite.execute(
            url = "tools://embedding/similarity",
            payload = buildJsonObject {
                put("a", packed)
                put("b", packed)
            }
        )
        assertTrue("similarity should succeed: ${outcome.summary}", outcome.success)
        assertEquals(1.0, outcome.resultData.jsonObject["score"]!!.jsonPrimitive.double, 1e-5)
        assertEquals(3, outcome.resultData.jsonObject["dim"]!!.jsonPrimitive.int)
        assertEquals("embedding", outcome.toolId)
    }

    @Test
    fun embeddingSimilarity_legacyVectorizeAliasUsesEmbeddingSchema() = kotlinx.coroutines.runBlocking {
        val packed = packedFloats(1f, 0f)
        val outcome = NativeToolsSuite.execute(
            url = "tools://vectorize/similarity",
            payload = buildJsonObject {
                put("a", packed)
                put("b", packed)
            }
        )
        assertTrue(outcome.success)
        assertEquals("embedding", outcome.toolId)
        assertEquals("similarity", outcome.operation)
    }

    @Test
    fun embeddingSimilarity_rejectsMissingAndMismatchedVectors() = kotlinx.coroutines.runBlocking {
        val missing = NativeToolsSuite.execute(
            url = "tools://embedding/similarity",
            payload = buildJsonObject { }
        )
        assertFalse(missing.success)
        assertTrue(missing.resultData.jsonObject["error"]!!.jsonPrimitive.content.contains("requires packed vectors"))

        val mismatch = NativeToolsSuite.execute(
            url = "tools://embedding/similarity",
            payload = buildJsonObject {
                put("a", packedFloats(1f, 2f))
                put("b", packedFloats(1f, 2f, 3f))
            }
        )
        assertFalse(mismatch.success)
        assertTrue(mismatch.resultData.jsonObject["error"]!!.jsonPrimitive.content.contains("dimension mismatch"))
    }

    @Test
    fun embeddingText_rejectsBlankTextBeforeTouchingTheModel() = kotlinx.coroutines.runBlocking {
        val outcome = NativeToolsSuite.execute(
            url = "tools://embedding/text",
            payload = buildJsonObject { put("text", "   ") }
        )
        assertFalse(outcome.success)
        assertTrue(outcome.resultData.jsonObject["error"]!!.jsonPrimitive.content.contains("non-blank"))
    }

    @Test
    fun hashedTextEmbedding_isDeterministicAndNormalized() {
        val a = HashedTextEmbedding.embed("Wagyu Smash Burger")
        val b = HashedTextEmbedding.embed("Wagyu Smash Burger")
        assertArrayEquals(a, b, 0.000001f)
        assertEquals(HashedTextEmbedding.DIM, a.size)
        var norm = 0f
        a.forEach { norm += it * it }
        assertEquals(1f, norm, 0.0001f)
    }

    @Test
    fun hashedTextEmbedding_ranksRelatedTextAboveUnrelatedText() {
        val query = HashedTextEmbedding.embed("Wagyu Smash Burger")
        val related = HashedTextEmbedding.embed("Smash burger with wagyu beef")
        val unrelated = HashedTextEmbedding.embed("Thermal printer paper roll")
        assertTrue(VectorMath.cosine(query, related) > VectorMath.cosine(query, unrelated))
    }

    @Test
    fun embedding_isAllowedForPublisherProvenance() {
        val checker = ProvenanceChecker()
        assertTrue(checker.allows(Provenance.Publisher, "embedding"))
        assertTrue(checker.allows(Provenance.Publisher, "vectorize"))
    }

    @Test
    fun embedding_registersCanonicalHandlerAndKeepsVectorizeContractHandler() {
        assertEquals("embedding", TerminalApp.registry.resolve("embedding")!!.capability)
        assertEquals("vectorize", TerminalApp.registry.resolve("vectorize")!!.capability)
        assertEquals(
            TerminalApp.registry.resolve("vectorize"),
            TerminalApp.registry.resolve("vector")
        )
    }

    private fun packedFloats(vararg values: Float): String {
        val buf = java.nio.ByteBuffer.allocate(values.size * Float.SIZE_BYTES)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        values.forEach { buf.putFloat(it) }
        return java.util.Base64.getEncoder().encodeToString(buf.array())
    }
}
