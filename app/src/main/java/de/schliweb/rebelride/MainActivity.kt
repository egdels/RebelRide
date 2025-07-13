package de.schliweb.rebelride

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Observer
import java.util.*

/**
 * MainActivity serves as the entry point of the Android application, responsible for
 * managing the user interface and Bluetooth GATT communication for interacting with a scooter.
 * It includes functionality to wake up, unlock, and lock the scooter using specific Bluetooth commands.
 *
 * This activity implements the logic for performing BLE operations such as
 * connecting to a remote Bluetooth device, discovering services, and sending commands
 * to a BLE characteristic.
 *
 * Key Functionalities:
 * - User inputs the MAC address and password for communication with the scooter.
 * - Provides options through buttons to unlock, lock, and wake up the scooter.
 * - Manages Bluetooth permissions dynamically for successful connections.
 * - Handles BLE service discovery, characteristic write operations, and notifications.
 *
 * Permissions:
 * The activity requires `BLUETOOTH_CONNECT` permission to create and manage GATT connections.
 * If not granted, it dynamically requests the required permission from the user.
 */
class MainActivity : AppCompatActivity() {
    // ViewModel for managing scooter operations and UI state
    private val viewModel: ScooterViewModel by viewModels()

    // Repository for handling Bluetooth operations
    private lateinit var bluetoothRepository: BluetoothRepository

    // UUIDs for the BLE service and characteristics
    private val SERVICE_UUID = UUID.fromString("00002c00-0000-1000-8000-00805f9b34fb")
    private val CHAR_WRITE_UUID = UUID.fromString("00002c01-0000-1000-8000-00805f9b34fb")
    private val CHAR_NOTIFY_UUID = UUID.fromString("00002c03-0000-1000-8000-00805f9b34fb")

    private lateinit var logView: TextView
    private lateinit var editMac: EditText
    private lateinit var editPass: EditText
    private lateinit var listDevices: ListView
    private lateinit var btnScan: Button
    private lateinit var cardViewLog: View
    private lateinit var btnToggleLog: ImageButton

    private var isScanning = false
    private var isLogVisible = false
    private val discoveredDevices = mutableListOf<BluetoothDevice>()
    private lateinit var deviceAdapter: ArrayAdapter<String>
    private lateinit var bluetoothAdapter: BluetoothAdapter

    // Map to store device addresses and names
    private val deviceMap = mutableMapOf<String, BluetoothDevice>()

    // SharedPreferences for saving user inputs
    private lateinit var sharedPreferences: SharedPreferences

    // Keys for SharedPreferences
    private val PREFS_NAME = "RebelRidePrefs"
    private val KEY_MAC_ADDRESS = "mac_address"
    private val KEY_PASSWORD = "password"

    // Operation types for the scooter
    private enum class ScooterOperation(val command: String, val param: String) {
        WAKE_UP("AT+OKSCT=OKAIYLBT,0,2,2,2,2,0000$", ""),
        LOCK("AT+BKSCT=", ",1$"),
        UNLOCK("AT+BKSCT=", ",0$")
    }

    // Request code for scanning permissions
    private val PERMISSION_REQUEST_CODE_SCAN = 2

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Hide the title bar for borderless display
        supportActionBar?.hide()

        // Set the app to use the full screen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }

        // Keep screen on while app is running
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_main)

        // Initialize UI components
        editMac = findViewById(R.id.editMac)
        editPass = findViewById(R.id.editPass)
        val btnUnlock = findViewById<Button>(R.id.btnUnlock)
        val btnLock = findViewById<Button>(R.id.btnLock)
        logView = findViewById(R.id.txtLog)
        btnScan = findViewById(R.id.btnScan)
        listDevices = findViewById(R.id.listDevices)
        cardViewLog = findViewById(R.id.cardViewLog)
        btnToggleLog = findViewById(R.id.btnToggleLog)

        // Initialize SharedPreferences
        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Load saved values
        loadSavedValues()

        // Initialize BluetoothRepository
        bluetoothRepository = BluetoothRepository(this)

        // Set the repository in the ViewModel
        viewModel.setBluetoothRepository(bluetoothRepository)

        // Set up log toggle button
        btnToggleLog.setOnClickListener {
            toggleLogVisibility()
        }

        // Set initial state of log visibility
        logView.visibility = View.GONE
        btnToggleLog.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)

        // Initialize Bluetooth adapter
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        if (btManager != null) {
            bluetoothAdapter = btManager.adapter
        }

        // Observe ViewModel LiveData
        viewModel.logText.observe(this, Observer { logText ->
            logView.text = logText
        })

        viewModel.isScanning.observe(this, Observer { scanning ->
            isScanning = scanning
            btnScan.text = if (scanning) "Stop Scan" else "Scan"
        })

        viewModel.discoveredDevices.observe(this, Observer { devices ->
            deviceMap.clear()
            deviceMap.putAll(devices)
            deviceAdapter.clear()
            devices.forEach { (address, device) ->
                val deviceName = if (ActivityCompat.checkSelfPermission(
                        this,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    device.name ?: "Unknown Device"
                } else {
                    "Unknown Device"
                }
                deviceAdapter.add("$deviceName ($address)")
            }
            deviceAdapter.notifyDataSetChanged()
        })

        viewModel.wakeUpButtonEnabled.observe(this, Observer { enabled ->
            val btnWakeUp = findViewById<Button>(R.id.btnWakeUp)
            btnWakeUp.isEnabled = enabled
            btnWakeUp.alpha = if (enabled) 1.0f else 0.5f
        })

        // Initialize device adapter
        deviceAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf<String>())
        listDevices.adapter = deviceAdapter

        // Set up scan button click listener
        btnScan.setOnClickListener {
            if (!isScanning) {
                clearLog()
                startScan()
            } else {
                stopScan()
            }
        }

        // Set up device selection listener
        listDevices.setOnItemClickListener { _, _, position, _ ->
            val deviceInfo = deviceAdapter.getItem(position) ?: return@setOnItemClickListener
            val macAddress = deviceInfo.substringAfterLast("(").substringBefore(")")
            editMac.setText(macAddress)
            // Save the MAC address (keep existing password)
            val currentPassword = editPass.text.toString().trim()
            saveValues(macAddress, currentPassword)
            log("Selected device: $deviceInfo")
        }

        btnUnlock.setOnClickListener {
            clearLog()
            val mac = editMac.text.toString().trim()
            val pass = editPass.text.toString().trim()
            if (mac.isEmpty() || pass.isEmpty()) {
                toast("Insert MAC address and password")
                return@setOnClickListener
            }
            // Save values
            saveValues(mac, pass)
            unlockScooter(mac, pass)
        }
        btnLock.setOnClickListener {
            clearLog()
            val mac = editMac.text.toString().trim()
            val pass = editPass.text.toString().trim()
            if (mac.isEmpty() || pass.isEmpty()) {
                toast("Insert MAC address and password")
                return@setOnClickListener
            }
            // Save values
            saveValues(mac, pass)
            lockScooter(mac, pass)
        }

        val btnWakeUp = findViewById<Button>(R.id.btnWakeUp)
        btnWakeUp.setOnClickListener {
            clearLog()
            val mac = editMac.text.toString().trim()
            val pass = editPass.text.toString().trim()
            if (mac.isEmpty()) {
                toast("Insert MAC address")
                return@setOnClickListener
            }
            // Save values (also save password if provided)
            saveValues(mac, pass)

            // Disable the wake-up button using the ViewModel
            viewModel.disableWakeUpButtonTemporarily()
            log("Wake Up button disabled for 30 seconds")

            wakeUpScooter(mac)
        }
    }

    /**
     * Common method to setup Bluetooth and perform an operation on the scooter
     * @param mac MAC address of the scooter
     * @param password Password for the scooter (optional, used for lock/unlock operations)
     * @param operation The operation to perform (WAKE_UP, LOCK, UNLOCK)
     */
    private fun performScooterOperation(mac: String, password: String = "", operation: ScooterOperation) {
        // Map MainActivity's ScooterOperation to ViewModel's ScooterOperation
        val viewModelOperation = when (operation) {
            ScooterOperation.WAKE_UP -> ScooterViewModel.ScooterOperation.WAKE_UP
            ScooterOperation.LOCK -> ScooterViewModel.ScooterOperation.LOCK
            ScooterOperation.UNLOCK -> ScooterViewModel.ScooterOperation.UNLOCK
        }

        // Log the operation
        val operationName = when (operation) {
            ScooterOperation.WAKE_UP -> "Wake Up"
            ScooterOperation.LOCK -> "Lock"
            ScooterOperation.UNLOCK -> "Unlock"
        }
        viewModel.addLog("Performing $operationName operation on $mac...")

        // Check Bluetooth status
        val (isManagerAvailable, isAdapterAvailable, isEnabled) = bluetoothRepository.checkBluetoothStatus()

        if (!isManagerAvailable) {
            viewModel.addLog("Bluetooth Manager not available!")
            toast("Bluetooth Manager not available!")
            return
        }

        if (!isAdapterAvailable) {
            viewModel.addLog("Bluetooth Adapter not available!")
            toast("Bluetooth Adapter not available!")
            return
        }

        if (!isEnabled) {
            viewModel.addLog("Bluetooth is disabled!")
            toast("Bluetooth is disabled!")
            return
        }

        // Get device from MAC address
        val device = bluetoothRepository.getDeviceFromMac(mac)
        if (device == null) {
            viewModel.addLog("Invalid MAC address: $mac")
            toast("Invalid MAC address!")
            return
        }

        // Check for Bluetooth permissions
        if (!bluetoothRepository.hasRequiredBluetoothPermissions()) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
            viewModel.addLog("Permission BLUETOOTH_CONNECT requested.")
            return
        }

        // Create GATT callback using ViewModel
        val gattCallback = viewModel.createGattCallback(viewModelOperation, password) { message ->
            viewModel.addLog(message)
        }

        // Connect to the device using the repository
        bluetoothRepository.connectToDevice(device, gattCallback)
    }


    /**
     * Wake up the scooter
     * @param mac MAC address of the scooter
     */
    private fun wakeUpScooter(mac: String) {
        performScooterOperation(mac, "", ScooterOperation.WAKE_UP)
    }

    /**
     * Lock the scooter
     * @param mac MAC address of the scooter
     * @param passwd Password for the scooter
     */
    private fun lockScooter(mac: String, passwd: String) {
        performScooterOperation(mac, passwd, ScooterOperation.LOCK)
    }

    /**
     * Unlock the scooter
     * @param mac MAC address of the scooter
     * @param passwd Password for the scooter
     */
    private fun unlockScooter(mac: String, passwd: String) {
        performScooterOperation(mac, passwd, ScooterOperation.UNLOCK)
    }


    /**
     * Clear the log view
     */
    private fun clearLog() {
        viewModel.clearLog()
    }

    /**
     * Log a message to the UI
     * @param msg The message to log
     */
    private fun log(msg: String) {
        viewModel.addLog(msg)
    }

    /**
     * Show a toast message
     * @param msg The message to display
     */
    private fun toast(msg: String) = runOnUiThread {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    /**
     * Toggle the visibility of the log area
     */
    private fun toggleLogVisibility() {
        isLogVisible = !isLogVisible

        // Update the log visibility
        logView.visibility = if (isLogVisible) View.VISIBLE else View.GONE

        // Update the toggle button icon
        btnToggleLog.setImageResource(
            if (isLogVisible) android.R.drawable.ic_menu_view
            else android.R.drawable.ic_menu_close_clear_cancel
        )
    }

    /**
     * Save MAC address and password to SharedPreferences
     */
    private fun saveValues(mac: String, password: String) {
        sharedPreferences.edit().apply {
            putString(KEY_MAC_ADDRESS, mac)
            putString(KEY_PASSWORD, password)
            apply()
        }
    }

    /**
     * Load saved MAC address and password from SharedPreferences
     */
    private fun loadSavedValues() {
        val savedMac = sharedPreferences.getString(KEY_MAC_ADDRESS, "")
        val savedPassword = sharedPreferences.getString(KEY_PASSWORD, "")

        if (!savedMac.isNullOrEmpty()) {
            editMac.setText(savedMac)
        }

        if (!savedPassword.isNullOrEmpty()) {
            editPass.setText(savedPassword)
        }
    }

    /**
     * Handle permission request results
     */
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == PERMISSION_REQUEST_CODE_SCAN) {
            // Check if all required permissions are granted
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                log("All permissions granted. Starting scan...")
                // Start scanning now that we have permissions
                startScan()
            } else {
                log("Some permissions were denied. Scanning not possible.")
                toast("Permissions required for Bluetooth scan.")
            }
        }
    }

    /**
     * Start scanning for nearby Bluetooth devices
     */
    private fun startScan() {
        // Check for Bluetooth scan permission
        val bluetoothScanPermission = ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.BLUETOOTH_SCAN
        ) == PackageManager.PERMISSION_GRANTED
        // Check for location permissions
        val fineLocationPermission = ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseLocationPermission = ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        // Request all missing permissions
        if (!bluetoothScanPermission || !fineLocationPermission || !coarseLocationPermission) {
            val permissionsToRequest = mutableListOf<String>()

            if (!bluetoothScanPermission) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (!fineLocationPermission) {
                permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (!coarseLocationPermission) {
                permissionsToRequest.add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }

            ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), PERMISSION_REQUEST_CODE_SCAN)
            viewModel.addLog("Required permissions requested: ${permissionsToRequest.joinToString(", ")}")
            return
        }

        // Clear previous results
        viewModel.clearDiscoveredDevices()
        viewModel.clearLog()
        viewModel.addLog("Scanning for devices...")
        viewModel.setScanning(true)

        // Start scan using the repository
        bluetoothRepository.startScan(scanCallback)
    }

    /**
     * Stop scanning for nearby Bluetooth devices
     */
    private fun stopScan() {
        bluetoothRepository.stopScan()
        viewModel.setScanning(false)
        viewModel.addLog("Scan stopped. Found ${deviceMap.size} devices.")
    }

    /**
     * Callback for Bluetooth LE scan results
     */
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device

            // Add the device to the ViewModel
            viewModel.addDiscoveredDevice(device)

            // Log the found device
            val deviceName = if (ActivityCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                device.name ?: "Unknown Device"
            } else {
                "Unknown Device"
            }
            viewModel.addLog("Found device: $deviceName (${device.address})")
        }

        override fun onScanFailed(errorCode: Int) {
            viewModel.addLog("Scan failed with error code: $errorCode")
            viewModel.setScanning(false)
        }
    }
}
