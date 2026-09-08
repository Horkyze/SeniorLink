package family.seniorlink.wearable

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import family.seniorlink.core.BleField
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import java.io.Closeable
import java.util.UUID

data class BleAttribute(val key: String, val service: String, val uuid: String, val properties: Int) {
    val field get() = BleField.find(service, uuid)
    val readable get() = properties and BluetoothGattCharacteristic.PROPERTY_READ != 0
    val notifiable get() = properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
    val indicatable get() = properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
}
data class BleValue(val attribute: BleAttribute, val bytes: ByteArray)

interface BleLink : Closeable {
    val values: ReceiveChannel<BleValue>
    suspend fun discover(): List<BleAttribute>
    suspend fun read(attribute: BleAttribute): ByteArray
    suspend fun subscribe(attribute: BleAttribute)
}

class BleOperationException(message: String) : Exception(message)

/** One operation in flight, bounded callbacks, timeouts, and stale-callback rejection per connection. */
@SuppressLint("MissingPermission")
class AndroidBleLink(private val context: Context, private val address: String) : BleLink {
    private val updates = Channel<BleValue>(256)
    override val values: ReceiveChannel<BleValue> get() = updates
    private val ready = CompletableDeferred<Unit>()
    private var gatt: BluetoothGatt? = null
    @Volatile private var closed = false
    private val lock = Any()
    private data class Pending(val key: String, val completion: CompletableDeferred<ByteArray>)
    private var pending: Pending? = null
    private val attributes = linkedMapOf<String, BluetoothGattCharacteristic>()

    private fun key(c: BluetoothGattCharacteristic): String =
        "${c.service.uuid}:${c.service.instanceId}/${c.uuid}:${c.instanceId}"
    private fun attribute(c: BluetoothGattCharacteristic) = BleAttribute(key(c), c.service.uuid.toString(), c.uuid.toString(), c.properties)
    private fun current(value: BluetoothGatt) = !closed && value === gatt

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (!current(g)) return
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) ready.complete(Unit)
            else if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED)
                fail(BleOperationException("Wearable disconnected (Bluetooth status $status)"))
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (current(g)) complete("discover", status)
        }
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            if (current(g)) complete("read:${key(c)}", status, value.copyOf())
        }
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < 33 && current(g)) complete("read:${key(c)}", status, c.value?.copyOf() ?: byteArrayOf())
        }
        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (current(g) && descriptor.uuid == CCCD) complete("subscribe:${key(descriptor.characteristic)}", status)
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (current(g)) incoming(c, value)
        }
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33 && current(g)) c.value?.let { incoming(c, it) }
        }
    }

    private fun incoming(c: BluetoothGattCharacteristic, bytes: ByteArray) {
        val attr = attribute(c)
        if (attr.field == null || bytes.size !in 1..512) return
        if (updates.trySend(BleValue(attr, bytes.copyOf())).isFailure && !closed)
            fail(BleOperationException("Wearable sent data faster than the phone could save it"))
    }

    private fun complete(key: String, status: Int, bytes: ByteArray = byteArrayOf()) {
        synchronized(lock) {
            val operation = pending?.takeIf { it.key == key } ?: return
            if (status == BluetoothGatt.GATT_SUCCESS) operation.completion.complete(bytes)
            else operation.completion.completeExceptionally(BleOperationException("Bluetooth operation failed ($status); pairing may be required"))
        }
    }

    private fun fail(error: Exception) {
        ready.completeExceptionally(error)
        synchronized(lock) { pending?.completion?.completeExceptionally(error) }
        updates.close(error)
    }

    override suspend fun discover(): List<BleAttribute> {
        check(BleAccess.missingConnectionPermissions(context).isEmpty()) { "Bluetooth permission is missing" }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        check(adapter?.isEnabled == true) { "Turn on Bluetooth to connect the wearable" }
        withContext(Dispatchers.Main.immediate) {
            // Main-handler callbacks cannot run before the returned GATT object is assigned.
            gatt = adapter.getRemoteDevice(address).connectGatt(context, false, callback,
                BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK, android.os.Handler(android.os.Looper.getMainLooper()))
                ?: throw BleOperationException("Android could not open the wearable connection")
        }
        withTimeout(25_000) { ready.await() }
        operation("discover") { it.discoverServices() }
        val result = gatt?.services.orEmpty().flatMap { it.characteristics }.take(256)
        attributes.clear()
        result.forEach { attributes[key(it)] = it }
        return result.map(::attribute)
    }

    override suspend fun read(attribute: BleAttribute): ByteArray = operation("read:${attribute.key}") {
        it.readCharacteristic(requireNotNull(attributes[attribute.key]))
    }

    @Suppress("DEPRECATION")
    override suspend fun subscribe(attribute: BleAttribute) {
        val characteristic = requireNotNull(attributes[attribute.key])
        val descriptor = characteristic.getDescriptor(CCCD) ?: throw BleOperationException("Measurement has no notification descriptor")
        operation("subscribe:${attribute.key}") { link ->
            if (!link.setCharacteristicNotification(characteristic, true)) return@operation false
            val bytes = if (attribute.notifiable) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            if (Build.VERSION.SDK_INT >= 33) link.writeDescriptor(descriptor, bytes) == BluetoothStatusCodes.SUCCESS
            else { descriptor.value = bytes; link.writeDescriptor(descriptor) }
        }
    }

    private suspend fun operation(key: String, start: (BluetoothGatt) -> Boolean): ByteArray {
        val operation = Pending(key, CompletableDeferred())
        synchronized(lock) { check(!closed && pending == null); pending = operation }
        try {
            if (!start(checkNotNull(gatt))) throw BleOperationException("Android could not start the Bluetooth operation")
            return withTimeout(12_000) { operation.completion.await() }
        } catch (e: TimeoutCancellationException) {
            // Late callbacks cannot be confused with a subsequent operation on this link.
            close()
            throw e
        } finally {
            synchronized(lock) { if (pending === operation) pending = null }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        fail(BleOperationException("Wearable connection closed"))
        val old = gatt
        gatt = null
        runCatching { old?.disconnect() }
        runCatching { old?.close() }
    }

    companion object { val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb") }
}
