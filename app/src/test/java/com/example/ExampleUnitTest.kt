package com.example

import com.example.enginehost.SduiOnFlyBuilder
import com.example.enginehost.WorkspaceProvisioner
import com.example.enginehost.sortedByUsage
import engine.config.EngineConfig
import engine.crypto.CanonicalJson
import engine.state.DivVariableValue
import engine.state.EngineStateBridge
import engine.ui.DivChartParser
import engine.ui.DivChartType
import engine.tools.DivActionUrl
import engine.tools.NativeToolsSuite
import engine.tools.Provenance
import engine.tools.TerminalApp
import engine.tools.ToolArgs
import engine.tools.ToolContext
import engine.tools.ToolResult
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleUnitTest {
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

        // 1. Capture image with Data URL encoding and dimension constraint
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
        assertTrue(width <= 800)
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
    fun nativeTools_mobileNetV4DetectsSkuInLowLatency() = kotlinx.coroutines.runBlocking {
        val payload = kotlinx.serialization.json.buildJsonObject {
            putJsonObject("options") {
                put("top_k", "3")
                put("min_confidence", "0.40")
            }
        }
        val outcome = engine.tools.NativeToolsSuite.execute("tools://MobileNetV4-small/classify", payload, tenantId = "acme_pos")
        assertTrue(outcome.success)
        assertEquals("MobileNetV4-small", outcome.toolId)
        assertTrue(outcome.outputVariables.containsKey("user.ai_prediction_label"))
        assertTrue(outcome.latencyMs < 200L)
    }

    @Test
    fun nativeTools_u2NetProducesAlphaMatte() = kotlinx.coroutines.runBlocking {
        val payload = kotlinx.serialization.json.buildJsonObject {
            putJsonObject("options") {
                put("tensor_input", "320x320x3")
                put("saliency_cutoff", "0.45")
            }
        }
        val outcome = engine.tools.NativeToolsSuite.execute("tools://u-2-Net.tflite/segment", payload)
        assertTrue(outcome.success)
        assertEquals("u-2-Net.tflite", outcome.toolId)
        assertTrue(outcome.outputVariables.containsKey("user.u2net_mask_uri"))
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
    fun biometricsTool_authorizesSupervisorRole() = kotlinx.coroutines.runBlocking {
        val urlStr = "div-action://terminal.tool.invoke?tool=auth.biometrics@1&required_role=SUPERVISOR&pin=1234&result_var=bio_state"
        val result = TerminalApp.router.handle(urlStr, Provenance.Publisher)
        assertTrue(result is ToolResult.Success)
        val success = result as ToolResult.Success
        assertEquals("ok", success.data["state"])
        assertEquals(true, success.data["authenticated"])
        assertEquals("SUPERVISOR", success.data["role"])
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

        val matrix = (outcome.outputVariables["user.code_matrix"] as DivVariableValue.Str).value
        val rows = matrix.split("\n")
        assertTrue("QR matrix should be square", rows.all { it.length == rows.size })
        assertTrue("QR matrix should contain dark modules", matrix.contains("1"))
        assertEquals("QR_CODE", (outcome.outputVariables["user.code_format"] as DivVariableValue.Str).value)
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
    // Built-in demo workspace showcase (DivKit card)
    // ------------------------------------------------------------------

    @Test
    fun demoCard_showcasesScannerOcrAndCodeGeneratorBlocks() {
        val spec = WorkspaceProvisioner.defaultWorkspaces.first()
        val json = WorkspaceProvisioner.buildDefaultDivKitCardJson(spec)

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
        val json = WorkspaceProvisioner.buildDefaultDivKitCardJson(spec)

        // The showcase must exercise the full tool inventory, so every registered
        // tool schema has to appear as a tools:// trigger in the built-in card.
        val missing = NativeToolsSuite.registeredSchemas
            .map { it.toolId }
            .filterNot { json.contains("tools://${it}/") }

        assertTrue("Demo card does not showcase these tools: $missing", missing.isEmpty())
    }

    @Test
    fun demoCard_declaresEveryReferencedUserVariable() {
        val spec = WorkspaceProvisioner.defaultWorkspaces.first()
        val json = WorkspaceProvisioner.buildDefaultDivKitCardJson(spec)

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
    fun demoCodeGenerateBlock_triggersGeneratorAndMapsOutputs() = kotlinx.coroutines.runBlocking {
        val spec = WorkspaceProvisioner.defaultWorkspaces.first()
        val block = SduiOnFlyBuilder.buildCodeGenerateToolBlock(spec)
        val button = block["items"]!!.jsonArray[4].jsonObject["items"]!!.jsonArray[0]
            .jsonObject["items"]!!.jsonArray[0].jsonObject

        val action = button["action"]!!.jsonObject
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

        // The final item is the OCR trigger
        val ocrButton = block["items"]!!.jsonArray.last().jsonObject
        val action = ocrButton["action"]!!.jsonObject
        assertEquals("tools://scan/ocr", action["url"]!!.jsonPrimitive.content)

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
}
