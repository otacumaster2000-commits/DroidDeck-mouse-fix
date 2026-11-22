package com.droiddeck.launcher.input

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice

/**
 * Reads a USB mouse from its HID interface, detaching it from Android meanwhile. Android only grants pointer
 * capture on the focused display, so an app shown elsewhere, as in REDMAGIC's cast mode, gets a cursor that
 * stops at the screen edge.
 */
class UsbMouse(private val context: Context, private val listener: Listener) {
    interface Listener {
        fun onMove(dx: Int, dy: Int)
        fun onButton(index: Int, down: Boolean)
        fun onWheel(steps: Int)
        fun onReady()
        fun onChanged()
        /** The mouse stopped responding while read here, so buttons held then never got their releases. */
        fun onLost()
    }

    private val usb by lazy { context.getSystemService(Context.USB_SERVICE) as UsbManager }
    private val main = Handler(Looper.getMainLooper())
    private val permissionAction by lazy { context.packageName + ".USB_MOUSE_PERMISSION" }
    private val asked = mutableSetOf<String>()
    private val failed = mutableSetOf<String>()
    private var receiverRegistered = false
    @Volatile private var grab: Grab? = null

    val active: Boolean get() = grab != null
    val ready: Boolean get() = grab?.ready == true

    fun owns(deviceId: Int): Boolean = grab?.let { it.ready && deviceId in it.androidIds } == true

    private val receiver = object : BroadcastReceiver() {
        @Suppress("DEPRECATION")
        override fun onReceive(context: Context, intent: Intent) {
            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
            when (intent.action) {
                permissionAction -> {
                    val granted = usb.hasPermission(device)
                    Log.i(TAG, "USB mouse access ${if (granted) "granted" else "denied"}")
                    if (granted) listener.onChanged()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    asked.remove(device.deviceName)
                    failed.remove(device.deviceName)
                    if (grab?.deviceName == device.deviceName) {
                        release()
                        listener.onLost()
                    }
                }
            }
        }
    }

    fun grab(externalOnly: Boolean = false): Boolean {
        if (grab != null) return true
        val (device, mouse, androidIds) = findMouse(externalOnly) ?: return false
        registerReceiver()
        if (!usb.hasPermission(device)) {
            if (asked.add(device.deviceName)) askPermission(device)
            return false
        }
        val endpoint = (0 until mouse.endpointCount).map(mouse::getEndpoint).firstOrNull {
            it.direction == UsbConstants.USB_DIR_IN && it.type == UsbConstants.USB_ENDPOINT_XFER_INT
        } ?: return false
        val current = Grab(device, mouse, endpoint, androidIds)
        grab = current
        Thread({ synchronized(readerLock) { current.run() } }, "usb-mouse").start()
        return true
    }

    /** Hands the mouse back to Android. */
    fun release() {
        val current = grab ?: return
        grab = null
        current.cancel()
    }

    fun close() {
        release()
        if (receiverRegistered) runCatching { context.unregisterReceiver(receiver) }
        receiverRegistered = false
    }

    private fun findMouse(externalOnly: Boolean): Triple<UsbDevice, UsbInterface, Set<Int>>? {
        val devices = InputDevice.getDeviceIds().asList().mapNotNull { InputDevice.getDevice(it) }
        val mice = devices.filter { device ->
            if (externalOnly) {
                MouseBackInput.isExternalMouse(device) && device.keyboardType != InputDevice.KEYBOARD_TYPE_ALPHABETIC &&
                    devices.none { it.vendorId == device.vendorId && it.productId == device.productId && PadBridge.isFromController(it) }
            } else !device.isVirtual && device.supportsSource(InputDevice.SOURCE_MOUSE)
        }.groupBy { it.vendorId to it.productId }
        val usbDevices = usb.deviceList.values.groupBy { it.vendorId to it.productId }
        return usbDevices.entries.firstNotNullOfOrNull { (id, candidates) ->
            val androidIds = mice[id]?.map { it.id }?.toSet() ?: return@firstNotNullOfOrNull null
            // Android InputDevice has no USB port identifier to distinguish identical receivers.
            if (externalOnly && candidates.size != 1) return@firstNotNullOfOrNull null
            candidates.firstNotNullOfOrNull candidate@ { device ->
                if (device.deviceName in failed) return@candidate null
                (0 until device.interfaceCount).map(device::getInterface).firstOrNull(::isBootMouse)
                    ?.let { Triple(device, it, androidIds) }
            }
        }
    }

    private fun registerReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter(permissionAction).apply { addAction(UsbManager.ACTION_USB_DEVICE_DETACHED) }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else context.registerReceiver(receiver, filter)
        receiverRegistered = true
    }

    private fun askPermission(device: UsbDevice) {
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val intent = PendingIntent.getBroadcast(context, 0, Intent(permissionAction).setPackage(context.packageName), flags)
        runCatching { usb.requestPermission(device, intent) }.onFailure {
            asked.remove(device.deviceName)
            Log.w(TAG, "requestPermission", it)
        }
    }

    private inner class Grab(
        private val device: UsbDevice,
        private val mouse: UsbInterface,
        private val endpoint: UsbEndpoint,
        val androidIds: Set<Int>,
    ) {
        @Volatile private var running = true
        private val claimLock = Any()
        val deviceName: String get() = device.deviceName
        var ready = false

        fun cancel() = synchronized(claimLock) { running = false }

        // The reader owns the connection so it cannot be closed during a transfer.
        fun run() {
            try {
                if (!running) return
                val connection = usb.openDevice(device) ?: return
                var claimed = false
                var boot = false
                try {
                    claimed = synchronized(claimLock) { running && connection.claimInterface(mouse, true) }
                    if (!claimed || !running) return
                    val format = setUp(connection) ?: return
                    boot = format === HidMouseReport.BOOT
                    if (!running) return
                    Log.i(TAG, String.format(java.util.Locale.ROOT, "reading USB mouse %04x:%04x \"%s\" directly (%s)",
                        device.vendorId, device.productId, device.productName, if (boot) "boot protocol" else "report ${format.reportId}"))
                    main.post {
                        if (grab === this) {
                            ready = true
                            listener.onReady()
                        }
                    }
                    read(connection, format)
                } finally {
                    if (claimed) {
                        if (boot) runCatching {
                            connection.controlTransfer(0x21, SET_PROTOCOL, HID_REPORT_PROTOCOL, mouse.id, null, 0, CONTROL_TIMEOUT_MS)
                        }
                        runCatching { connection.releaseInterface(mouse) }
                    }
                    connection.close()
                }
            } catch (e: RuntimeException) {
                Log.w(TAG, "reading USB mouse", e)
            } finally {
                main.post { ended() }
            }
        }

        /** HID 1.11 7.2.6: a host must not assume the protocol a device is in, so it is set either way. */
        private fun setUp(connection: UsbDeviceConnection): HidMouseReport? {
            val descriptor = ByteArray(1024)
            val length = connection.controlTransfer(0x81, GET_DESCRIPTOR, HID_REPORT_DESCRIPTOR shl 8, mouse.id,
                descriptor, descriptor.size, CONTROL_TIMEOUT_MS)
            val parsed = if (length > 0) HidMouseReport.parse(descriptor.copyOf(length)) else null
            if (parsed != null && running &&
                connection.controlTransfer(0x21, SET_PROTOCOL, HID_REPORT_PROTOCOL, mouse.id, null, 0, CONTROL_TIMEOUT_MS) >= 0) return parsed
            val boot = running &&
                connection.controlTransfer(0x21, SET_PROTOCOL, HID_BOOT_PROTOCOL, mouse.id, null, 0, CONTROL_TIMEOUT_MS) >= 0
            return HidMouseReport.BOOT.takeIf { boot }
        }

        private fun read(connection: UsbDeviceConnection, format: HidMouseReport) {
            val report = ByteArray(maxOf(endpoint.maxPacketSize, 8))
            var buttons = 0
            var failures = 0
            while (running) {
                val started = SystemClock.uptimeMillis()
                val length = connection.bulkTransfer(endpoint, report, report.size, READ_TIMEOUT_MS)
                if (length <= 0) {
                    if (SystemClock.uptimeMillis() - started < READ_TIMEOUT_MS / 2 && ++failures >= MAX_FAILURES) return
                    continue
                }
                failures = 0
                val state = format.decode(report, length) ?: continue
                val changed = buttons xor state.buttons
                buttons = state.buttons
                main.post { deliver(state, changed) }
            }
        }

        private fun ended() {
            if (grab !== this) return
            failed += device.deviceName
            Log.i(TAG, if (ready) "USB mouse stopped responding" else "USB mouse could not be set up")
            grab = null
            listener.onLost()
        }

        private fun deliver(state: HidMouseReport.State, changed: Int) {
            if (grab !== this) return
            if (state.dx != 0 || state.dy != 0) listener.onMove(state.dx, state.dy)
            for (i in 0 until BUTTONS) if (changed and (1 shl i) != 0) listener.onButton(i, state.buttons and (1 shl i) != 0)
            if (state.wheel != 0) listener.onWheel(state.wheel)
        }
    }

    companion object {
        private val readerLock = Any()
        private const val TAG = "UsbMouse"
        private const val GET_DESCRIPTOR = 0x06
        private const val SET_PROTOCOL = 0x0b
        private const val HID_BOOT_PROTOCOL = 0
        private const val HID_REPORT_PROTOCOL = 1
        private const val HID_REPORT_DESCRIPTOR = 0x22
        private const val SUBCLASS_BOOT = 1
        private const val PROTOCOL_MOUSE = 2
        private const val CONTROL_TIMEOUT_MS = 1000
        private const val READ_TIMEOUT_MS = 100
        private const val MAX_FAILURES = 5
        private const val BUTTONS = 5

        private fun isBootMouse(iface: UsbInterface) = iface.interfaceClass == UsbConstants.USB_CLASS_HID &&
            iface.interfaceSubclass == SUBCLASS_BOOT && iface.interfaceProtocol == PROTOCOL_MOUSE

        /**
         * A process killed while reading a mouse leaves it with no driver until it is replugged. Claiming without
         * force only succeeds when no driver has the interface, and releasing it reattaches Android's.
         */
        fun returnLeftover(context: Context) {
            Thread({
                synchronized(readerLock) {
                    runCatching {
                        val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
                        for (device in usb.deviceList.values) {
                            if (!usb.hasPermission(device)) continue
                            val mouse = (0 until device.interfaceCount).map(device::getInterface).firstOrNull(::isBootMouse) ?: continue
                            val connection = usb.openDevice(device) ?: continue
                            try {
                                if (connection.claimInterface(mouse, false)) {
                                    connection.releaseInterface(mouse)
                                    Log.i(TAG, "returned USB mouse ${device.productName} to Android")
                                }
                            } finally {
                                connection.close()
                            }
                        }
                    }.onFailure { Log.w(TAG, "returning a USB mouse to Android failed", it) }
                }
            }, "usb-mouse-return").start()
        }
    }
}
