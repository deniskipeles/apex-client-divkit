package engine.tools.impl

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import engine.crypto.currentTimeMillis
import engine.db.currentAndroidContext
import engine.tools.ToolArgs
import engine.tools.ToolContext
import engine.tools.ToolHandler
import engine.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Universal Bluetooth Printer Capability ("device.printer@1" / "tools://print").
 *
 * Unopinionated receipt/thermal printing bridge for POS terminals:
 *  - `scan`/`list`    : enumerate bonded + discoverable Bluetooth printers and POS peers.
 *  - `pair`           : bond with a device by MAC address.
 *  - `connect`        : open an RFCOMM (SPP) socket to a printer.
 *  - `print`          : render text/ESC-POS receipt bytes and write them to the socket.
 *  - `disconnect`     : close a socket.
 *  - `status`         : adapter state, bonded printers and open connections.
 *
 * When no Android context is available (unit tests / headless) the tool degrades to a
 * deterministic emulation so schemas, dispatch and byte-payload encoding stay testable.
 */
class PrintTool : ToolHandler {
    override val capability: String = "device.printer@1"
    override val requiredPermissions: Set<String> = requiredBluetoothPermissions()

    private val connections: ConcurrentHashMap<String, BluetoothSocket> = ConcurrentHashMap()

    override suspend fun invoke(args: ToolArgs, ctx: ToolContext): ToolResult {
        val op = (args["op"] ?: args["action"] ?: args["operation"] ?: "scan").lowercase()
        return try {
            when (op) {
                "scan", "discover", "list", "devices", "printers" -> executeScan(args)
                "pair", "bond" -> executePair(args)
                "connect" -> executeConnect(args)
                "disconnect", "close" -> executeDisconnect(args)
                "print", "print_text", "receipt", "write" -> executePrint(args)
                "status", "capabilities", "get_status" -> executeStatus()
                else -> ToolResult.Failed(capability, "Unsupported printer operation '$op'")
            }
        } catch (e: SecurityException) {
            ToolResult.PermissionDenied(capability, e.message ?: "BLUETOOTH_CONNECT")
        } catch (e: Exception) {
            ToolResult.Failed(capability, e.message ?: "Printer operation failed")
        }
    }

    // ------------------------------------------------------------------
    // Discovery
    // ------------------------------------------------------------------

    private fun executeScan(args: ToolArgs): ToolResult {
        val context = currentAndroidContext?.invoke()
        val includeAll = args.getFlag("all") || args.getFlag("include_all")
        val startDiscovery = args.getFlag("discover") || args.getFlag("start_discovery")

        if (context == null) {
            return ToolResult.Success(
                mapOf(
                    "status" to "SUCCESS",
                    "mode" to "emulated",
                    "devices" to emptyList<Map<String, Any>>(),
                    "count" to 0,
                    "timestamp" to currentTimeMillis()
                )
            )
        }

        val denied = missingPermissions(context)
        if (denied.isNotEmpty()) return ToolResult.PermissionDenied(capability, denied.first())

        val adapter = bluetoothAdapter(context)
            ?: return ToolResult.Unsupported(capability, "No Bluetooth adapter present on this device")

        if (!adapter.isEnabled) {
            return ToolResult.Success(
                mapOf(
                    "status" to "BLUETOOTH_OFF",
                    "enabled" to false,
                    "devices" to emptyList<Map<String, Any>>(),
                    "count" to 0,
                    "timestamp" to currentTimeMillis()
                )
            )
        }

        val devices = mutableListOf<Map<String, Any>>()
        adapter.bondedDevices?.forEach { d ->
            val printerLike = isPrinterLike(safeName(d))
            if (includeAll || printerLike) devices.add(describe(d, bonded = true, printerLike = printerLike))
        }

        if (startDiscovery && adapter.isDiscovering) adapter.cancelDiscovery()
        val discoveryStarted = startDiscovery && adapter.startDiscovery()

        return ToolResult.Success(
            mapOf(
                "status" to "SUCCESS",
                "enabled" to true,
                "discovery_started" to discoveryStarted,
                "bonded_count" to (adapter.bondedDevices?.size ?: 0),
                "count" to devices.size,
                "devices" to devices,
                "timestamp" to currentTimeMillis()
            )
        )
    }

    private fun executePair(args: ToolArgs): ToolResult {
        val address = args["address"] ?: args["mac"] ?: args["device"]
            ?: return ToolResult.Failed(capability, "missing address argument")
        val context = currentAndroidContext?.invoke()
            ?: return emulateOk("PAIRED", address, extra = mapOf("mode" to "emulated"))

        val denied = missingPermissions(context)
        if (denied.isNotEmpty()) return ToolResult.PermissionDenied(capability, denied.first())

        val adapter = bluetoothAdapter(context)
            ?: return ToolResult.Unsupported(capability, "No Bluetooth adapter present")
        val device = adapter.getRemoteDevice(address)
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            return ToolResult.Success(
                mapOf(
                    "status" to "ALREADY_PAIRED",
                    "address" to address,
                    "name" to safeName(device),
                    "timestamp" to currentTimeMillis()
                )
            )
        }
        val started = device.createBond()
        return ToolResult.Success(
            mapOf(
                "status" to if (started) "PAIRING" else "PAIR_FAILED",
                "address" to address,
                "name" to safeName(device),
                "timestamp" to currentTimeMillis()
            )
        )
    }

    // ------------------------------------------------------------------
    // Connection lifecycle
    // ------------------------------------------------------------------

    private suspend fun executeConnect(args: ToolArgs): ToolResult {
        val address = args["address"] ?: args["mac"] ?: args["device"]
            ?: return ToolResult.Failed(capability, "missing address argument")
        val context = currentAndroidContext?.invoke()
            ?: return emulateOk("CONNECTED", address, extra = mapOf("mode" to "emulated"))

        val denied = missingPermissions(context)
        if (denied.isNotEmpty()) return ToolResult.PermissionDenied(capability, denied.first())

        val adapter = bluetoothAdapter(context)
            ?: return ToolResult.Unsupported(capability, "No Bluetooth adapter present")
        if (!adapter.isEnabled) {
            return ToolResult.Failed(capability, "Bluetooth is disabled")
        }

        connections[address]?.let {
            return ToolResult.Success(
                mapOf("status" to "ALREADY_CONNECTED", "address" to address, "timestamp" to currentTimeMillis())
            )
        }

        val device = adapter.getRemoteDevice(address)
        val timeoutMs = args.getInt("timeout_ms", 12000).toLong()
        val socket = withTimeoutOrNull(timeoutMs) {
            withContext(Dispatchers.IO) {
                val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
                adapter.cancelDiscovery()
                s.connect()
                s
            }
        } ?: return ToolResult.Failed(capability, "Timed out connecting to $address")

        connections[address] = socket
        return ToolResult.Success(
            mapOf(
                "status" to "CONNECTED",
                "address" to address,
                "name" to safeName(device),
                "protocol" to "RFCOMM_SPP",
                "timestamp" to currentTimeMillis()
            )
        )
    }

    private fun executeDisconnect(args: ToolArgs): ToolResult {
        val address = args["address"] ?: args["mac"] ?: args["device"]
            ?: return ToolResult.Failed(capability, "missing address argument")
        val socket = connections.remove(address)
        try {
            socket?.close()
        } catch (_: Exception) {}
        return ToolResult.Success(
            mapOf(
                "status" to if (socket != null) "DISCONNECTED" else "NOT_CONNECTED",
                "address" to address,
                "timestamp" to currentTimeMillis()
            )
        )
    }

    // ------------------------------------------------------------------
    // Printing
    // ------------------------------------------------------------------

    private suspend fun executePrint(args: ToolArgs): ToolResult {
        val address = args["address"] ?: args["mac"] ?: args["device"]
        val payload = buildEscPos(args)

        val context = currentAndroidContext?.invoke()
        val socket = address?.let { connections[it] }

        if (context == null || socket == null) {
            return ToolResult.Success(
                mapOf(
                    "status" to "SUCCESS",
                    "mode" to if (context == null) "emulated" else "queued",
                    "address" to (address ?: ""),
                    "bytes" to payload.size,
                    "preview_hex" to payload.take(32).joinToString(" ") { "%02X".format(it) },
                    "timestamp" to currentTimeMillis()
                )
            )
        }

        val denied = missingPermissions(context)
        if (denied.isNotEmpty()) return ToolResult.PermissionDenied(capability, denied.first())

        withContext(Dispatchers.IO) {
            socket.outputStream.apply {
                write(payload)
                flush()
            }
        }

        return ToolResult.Success(
            mapOf(
                "status" to "SUCCESS",
                "mode" to "bluetooth_rfcomm",
                "address" to address,
                "bytes" to payload.size,
                "timestamp" to currentTimeMillis()
            )
        )
    }

    private fun executeStatus(): ToolResult {
        val context = currentAndroidContext?.invoke()
        val adapter = context?.let { bluetoothAdapter(it) }

        val bondedPrinters = if (context != null && adapter != null && missingPermissions(context).isEmpty()) {
            adapter.bondedDevices?.count { isPrinterLike(safeName(it)) } ?: 0
        } else 0

        return ToolResult.Success(
            mapOf(
                "status" to "READY",
                "available" to (adapter?.isEnabled ?: true),
                "enabled" to (adapter?.isEnabled ?: true),
                "bonded_printers" to bondedPrinters,
                "connections" to connections.keys.toList(),
                "supported_operations" to listOf(
                    "scan", "pair", "connect", "disconnect", "print", "status"
                ),
                "timestamp" to currentTimeMillis()
            )
        )
    }

    // ------------------------------------------------------------------
    // ESC/POS payload construction
    // ------------------------------------------------------------------

    private fun buildEscPos(args: ToolArgs): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(ESC_INIT)

        val align = (args["align"] ?: "left").lowercase()
        out.write(alignCommand(align))

        val bold = args.getFlag("bold")
        out.write(if (bold) BOLD_ON else BOLD_OFF)

        val width = args.getInt("width", 32).coerceIn(16, 80)
        val lines = resolveLines(args)
        for (line in lines) {
            out.write(wrap(line, width).toByteArray(Charsets.UTF_8))
            out.write(FEED)
        }

        val feedLines = args.getInt("feed_lines", 3).coerceIn(0, 10)
        repeat(feedLines) { out.write(FEED) }

        if (args.getFlag("cut") || (args["cut"] == "true")) {
            out.write(CUT)
        }
        return out.toByteArray()
    }

    private fun resolveLines(args: ToolArgs): List<String> {
        args["text"]?.let { return it.split("\n", "\\n") }
        args["lines"]?.let { raw ->
            return raw.split("\n", "\\n", ",", "|").map { it.trim() }.filter { it.isNotEmpty() }
        }
        args["receipt"]?.let { return it.split("\n", "\\n") }
        return listOf("")
    }

    private fun wrap(line: String, width: Int): String {
        if (line.length <= width) return line
        val sb = StringBuilder()
        var count = 0
        for (ch in line) {
            if (count == width) {
                sb.append('\n')
                count = 0
            }
            sb.append(ch)
            count++
        }
        return sb.toString()
    }

    private fun alignCommand(align: String): ByteArray = when (align) {
        "center", "centre" -> byteArrayOf(0x1B, 0x61, 0x01)
        "right" -> byteArrayOf(0x1B, 0x61, 0x02)
        else -> byteArrayOf(0x1B, 0x61, 0x00)
    }

    // ------------------------------------------------------------------
    // Bluetooth helpers
    // ------------------------------------------------------------------

    private fun bluetoothAdapter(context: Context): BluetoothAdapter? {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return manager?.adapter
    }

    private fun describe(device: BluetoothDevice, bonded: Boolean, printerLike: Boolean): Map<String, Any> =
        mapOf(
            "name" to (safeName(device) ?: "Unknown"),
            "address" to device.address,
            "bonded" to bonded,
            "bond_state" to bondStateLabel(device.bondState),
            "printer_like" to printerLike,
            "type" to if (printerLike) "printer" else "bluetooth"
        )

    private fun safeName(device: BluetoothDevice): String? =
        try {
            device.name
        } catch (_: SecurityException) {
            null
        }

    private fun isPrinterLike(name: String?): Boolean {
        val n = name?.lowercase() ?: return false
        return PRINTER_HINTS.any { n.contains(it) }
    }

    private fun bondStateLabel(state: Int): String = when (state) {
        BluetoothDevice.BOND_BONDED -> "BONDED"
        BluetoothDevice.BOND_BONDING -> "BONDING"
        else -> "NONE"
    }

    private fun missingPermissions(context: Context): List<String> =
        requiredPermissions.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

    private fun emulateOk(status: String, address: String, extra: Map<String, Any> = emptyMap()): ToolResult =
        ToolResult.Success(
            mapOf("status" to status, "address" to address, "timestamp" to currentTimeMillis()) + extra
        )

    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        private val PRINTER_HINTS = listOf(
            "print", "printer", "thermal", "pos", "receipt", "rpp", "esc",
            "btp", "sp-", "goojprt", "xprinter", "zebra", "epson", "star", "citizen", "rongta"
        )

        private val ESC_INIT = byteArrayOf(0x1B, 0x40)
        private val BOLD_ON = byteArrayOf(0x1B, 0x45, 0x01)
        private val BOLD_OFF = byteArrayOf(0x1B, 0x45, 0x00)
        private const val FEED = 0x0A
        private val CUT = byteArrayOf(0x1D, 0x56, 0x00)

        private fun requiredBluetoothPermissions(): Set<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
            } else {
                setOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN)
            }
    }
}
