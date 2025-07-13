package de.schliweb.rebelride

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.core.app.ActivityCompat
import java.util.*

/**
 * Repository for handling Bluetooth operations.
 * Separates Bluetooth logic from the ViewModel and UI.
 */
class BluetoothRepository(private val context: Context) {

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    // Callback for scan results
    private var scanCallback: ScanCallback? = null

    // Timeout for scanning (5 seconds)
    private val SCAN_PERIOD: Long = 5000

    /**
     * Check if Bluetooth is available and enabled
     * @return Triple of (isManagerAvailable, isAdapterAvailable, isEnabled)
     */
    fun checkBluetoothStatus(): Triple<Boolean, Boolean, Boolean> {
        val isManagerAvailable = bluetoothManager != null
        val isAdapterAvailable = bluetoothAdapter != null
        val isEnabled = bluetoothAdapter?.isEnabled == true

        return Triple(isManagerAvailable, isAdapterAvailable, isEnabled)
    }

    /**
     * Get a BluetoothDevice from a MAC address
     * @param mac The MAC address of the device
     * @return The BluetoothDevice or null if the address is invalid
     */
    fun getDeviceFromMac(mac: String): BluetoothDevice? {
        return try {
            bluetoothAdapter?.getRemoteDevice(mac)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /**
     * Check if the app has the required Bluetooth permissions
     * @return true if all required permissions are granted, false otherwise
     */
    fun hasRequiredBluetoothPermissions(): Boolean {
        return ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_SCAN
        ) == PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Start scanning for Bluetooth devices
     * @param callback Callback for scan results
     * @return true if scan started successfully, false if permissions are missing
     */
    fun startScan(callback: ScanCallback): Boolean {
        if (!hasRequiredBluetoothPermissions()) {
            return false
        }

        try {
            scanCallback = callback
            bluetoothAdapter?.bluetoothLeScanner?.startScan(callback)

            // Set a timeout to stop scanning after SCAN_PERIOD
            Handler(Looper.getMainLooper()).postDelayed({
                stopScan()
            }, SCAN_PERIOD)
            return true
        } catch (e: SecurityException) {
            // Permission denied
            return false
        }
    }

    /**
     * Stop scanning for Bluetooth devices
     * @return true if scan stopped successfully, false if permissions are missing
     */
    fun stopScan(): Boolean {
        if (!hasRequiredBluetoothPermissions()) {
            return false
        }

        try {
            scanCallback?.let { callback ->
                bluetoothAdapter?.bluetoothLeScanner?.stopScan(callback)
                scanCallback = null
            }
            return true
        } catch (e: SecurityException) {
            // Permission denied
            return false
        }
    }

    /**
     * Connect to a Bluetooth device
     * @param device The device to connect to
     * @param callback The callback for GATT events
     * @return The BluetoothGatt connection or null if permissions are missing
     */
    fun connectToDevice(device: BluetoothDevice, callback: BluetoothGattCallback): BluetoothGatt? {
        if (!hasRequiredBluetoothPermissions()) {
            return null
        }

        return try {
            device.connectGatt(context, false, callback)
        } catch (e: SecurityException) {
            // Permission denied
            null
        }
    }

    /**
     * Find a characteristic in a service
     * @param gatt The GATT connection
     * @param serviceUuid The UUID of the service
     * @param characteristicUuid The UUID of the characteristic
     * @return The characteristic or null if not found
     */
    fun findCharacteristic(
        gatt: BluetoothGatt,
        serviceUuid: UUID,
        characteristicUuid: UUID
    ): BluetoothGattCharacteristic? {
        val service = gatt.getService(serviceUuid) ?: return null
        return service.getCharacteristic(characteristicUuid)
    }
}
