package de.schliweb.rebelride

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresPermission
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * ViewModel for managing scooter operations and Bluetooth device scanning.
 * Separates business logic from UI and handles lifecycle-aware data.
 */
class ScooterViewModel : ViewModel() {

    // Repository for handling Bluetooth operations
    private var bluetoothRepository: BluetoothRepository? = null

    /**
     * Set the Bluetooth repository
     * @param repository The repository to use for Bluetooth operations
     */
    fun setBluetoothRepository(repository: BluetoothRepository) {
        bluetoothRepository = repository
    }

    // Operation types for the scooter
    enum class ScooterOperation(val command: String, val param: String) {
        WAKE_UP("AT+OKSCT=OKAIYLBT,0,2,2,2,2,0000$", ""),
        LOCK("AT+BKSCT=", ",1$"),
        UNLOCK("AT+BKSCT=", ",0$")
    }

    // LiveData for UI state
    private val _isScanning = MutableLiveData<Boolean>(false)
    val isScanning: LiveData<Boolean> = _isScanning

    private val _logText = MutableLiveData<String>("")
    val logText: LiveData<String> = _logText

    private val _discoveredDevices = MutableLiveData<Map<String, BluetoothDevice>>(mapOf())
    val discoveredDevices: LiveData<Map<String, BluetoothDevice>> = _discoveredDevices

    private val _wakeUpButtonEnabled = MutableLiveData<Boolean>(true)
    val wakeUpButtonEnabled: LiveData<Boolean> = _wakeUpButtonEnabled

    // UUIDs for the BLE service and characteristics
    val SERVICE_UUID = java.util.UUID.fromString("00002c00-0000-1000-8000-00805f9b34fb")
    val CHAR_WRITE_UUID = java.util.UUID.fromString("00002c01-0000-1000-8000-00805f9b34fb")
    val CHAR_NOTIFY_UUID = java.util.UUID.fromString("00002c03-0000-1000-8000-00805f9b34fb")

    /**
     * Add a log message
     * Using postValue instead of setValue to safely update from any thread
     */
    fun addLog(message: String) {
        val currentLog = _logText.value ?: ""
        _logText.postValue("$currentLog$message\n")
    }

    /**
     * Clear the log
     * Using postValue instead of setValue to safely update from any thread
     */
    fun clearLog() {
        _logText.postValue("")
    }

    /**
     * Set scanning state
     * Using postValue instead of setValue to safely update from any thread
     */
    fun setScanning(scanning: Boolean) {
        _isScanning.postValue(scanning)
    }

    /**
     * Add a discovered device
     * Using postValue instead of setValue to safely update from any thread
     */
    fun addDiscoveredDevice(device: BluetoothDevice) {
        val currentDevices = _discoveredDevices.value?.toMutableMap() ?: mutableMapOf()
        currentDevices[device.address] = device
        _discoveredDevices.postValue(currentDevices)
    }

    /**
     * Clear discovered devices
     * Using postValue instead of setValue to safely update from any thread
     */
    fun clearDiscoveredDevices() {
        _discoveredDevices.postValue(mapOf())
    }

    /**
     * Disable wake up button for 30 seconds
     * Using postValue instead of setValue to safely update from any thread
     */
    fun disableWakeUpButtonTemporarily() {
        _wakeUpButtonEnabled.postValue(false)
        viewModelScope.launch {
            delay(30000) // 30 seconds
            _wakeUpButtonEnabled.postValue(true)
        }
    }

    /**
     * Create a GATT callback for the specified operation
     */
    fun createGattCallback(
        operation: ScooterOperation,
        password: String,
        onLog: (String) -> Unit
    ): BluetoothGattCallback {
        return object : BluetoothGattCallback() {
            @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
            override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
                if (gatt == null) return

                if (newState == BluetoothGatt.STATE_CONNECTED) {
                    onLog("Connected, discovering services...")
                    gatt.discoverServices()
                } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                    onLog("Disconnected.")
                    gatt.close()
                }
            }

            @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
            override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
                if (gatt == null) return

                if (status != BluetoothGatt.GATT_SUCCESS) {
                    onLog("Service discovery failed with status: $status")
                    return
                }

                onLog("Services discovered successfully.")

                // Log all discovered services and characteristics for debugging
                val services = gatt.services
                onLog("Found ${services.size} services:")

                services.forEach { service ->
                    onLog("Service: ${service.uuid}")
                    service.characteristics.forEach { characteristic ->
                        onLog("  Characteristic: ${characteristic.uuid}")
                    }
                }

                // Check if the required service exists
                val service = gatt.getService(SERVICE_UUID)
                if (service == null) {
                    onLog("Service not found! UUID: $SERVICE_UUID")
                    return
                }
                onLog("Required service found: $SERVICE_UUID")

                // Use the repository's findCharacteristic method to get the characteristics
                val charWrite = bluetoothRepository?.findCharacteristic(gatt, SERVICE_UUID, CHAR_WRITE_UUID)
                val charNotify = bluetoothRepository?.findCharacteristic(gatt, SERVICE_UUID, CHAR_NOTIFY_UUID)

                if (charWrite == null) {
                    onLog("Write characteristic not found! UUID: $CHAR_WRITE_UUID")
                    return
                }

                if (charNotify == null) {
                    onLog("Notify characteristic not found! UUID: $CHAR_NOTIFY_UUID")
                    return
                }

                onLog("All required characteristics found. Setting up notification...")

                // Enable notifications
                val notifyResult = gatt.setCharacteristicNotification(charNotify, true)
                onLog("Set notification result: $notifyResult")

                // Prepare command based on operation
                val part1 = when (operation) {
                    ScooterOperation.WAKE_UP -> operation.command
                    ScooterOperation.LOCK, ScooterOperation.UNLOCK -> operation.command + password + operation.param
                }
                val part2 = "\r\n"

                // Send first part
                charWrite.value = part1.toByteArray(Charsets.UTF_8)

                onLog("Attempting to write first part...")

                // Use the appropriate writeCharacteristic method based on Android version
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    // For Android 13+ (API 33+)
                    val writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    val writeResult = gatt.writeCharacteristic(charWrite, charWrite.value, writeType)
                    onLog("Write request result (Android 13+): $writeResult")
                } else {
                    // For Android 12 and below
                    @Suppress("DEPRECATION")
                    val writeResult = gatt.writeCharacteristic(charWrite)
                    onLog("Write request result (Android 12-): $writeResult")
                }

                onLog("Sending chunk 1: $part1")

                // Wait briefly, then send second part
                Handler(Looper.getMainLooper()).postDelayed({
                    charWrite.value = part2.toByteArray(Charsets.UTF_8)

                    onLog("Attempting to write second part...")

                    // Use the appropriate writeCharacteristic method based on Android version
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        // For Android 13+ (API 33+)
                        val writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        val writeResult = gatt.writeCharacteristic(charWrite, charWrite.value, writeType)
                        onLog("Write request result (Android 13+): $writeResult")
                    } else {
                        // For Android 12 and below
                        @Suppress("DEPRECATION")
                        val writeResult = gatt.writeCharacteristic(charWrite)
                        onLog("Write request result (Android 12-): $writeResult")
                    }

                    onLog("Sending chunk 2: \\r\\n")
                }, 100)
            }

            @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt?,
                characteristic: BluetoothGattCharacteristic?
            ) {
                if (gatt == null || characteristic == null) return

                // Get the raw byte array
                val rawData = characteristic.value

                // Try to convert to UTF-8 string for logging
                val value = try {
                    String(rawData, Charsets.UTF_8)
                } catch (e: Exception) {
                    // If conversion fails, show hex representation
                    rawData.joinToString(separator = " ") { String.format("%02X", it) }
                }

                onLog("Notification received: $value")

                // Process the response if needed
                // For example, check if it contains success/failure indicators

                // Disconnect after processing
                gatt.disconnect()
            }

            @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
            override fun onCharacteristicWrite(
                gatt: BluetoothGatt?,
                characteristic: BluetoothGattCharacteristic?,
                status: Int
            ) {
                if (gatt == null || characteristic == null) return

                if (status == BluetoothGatt.GATT_SUCCESS) {
                    // Get the raw byte array that was written
                    val rawData = characteristic.value

                    // Try to convert to UTF-8 string for logging
                    val value = try {
                        String(rawData, Charsets.UTF_8)
                    } catch (e: Exception) {
                        // If conversion fails, show hex representation
                        rawData.joinToString(separator = " ") { String.format("%02X", it) }
                    }

                    onLog("Write successful: $value")
                } else {
                    onLog("Write failed with status: $status")
                }
            }
        }
    }
}
