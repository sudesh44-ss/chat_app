package com.nexa.app.core.permission

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

data class PermissionItem(
    val permission: String,
    val title: String,
    val description: String,
    val isGranted: Boolean
)

object PermissionHelper {

    fun getRequiredPermissions(): List<String> {
        val permissions = mutableListOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS
        )

        // Notification permission only exists on Android 13+ (API 33)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Bluetooth permission split
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            // Android 7.0 to 11 (API 24 - 30) requires Fine Location to scan for BLE beacons
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        return permissions
    }

    fun isPermissionGranted(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            permission
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun areAllPermissionsGranted(context: Context): Boolean {
        return getRequiredPermissions().all { isPermissionGranted(context, it) }
    }

    fun hasSmsPermissions(context: Context): Boolean {
        return isPermissionGranted(context, Manifest.permission.SEND_SMS) &&
                isPermissionGranted(context, Manifest.permission.RECEIVE_SMS)
    }

    fun getSmsPermissions(): List<String> {
        return listOf(
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_PHONE_STATE
        )
    }

    fun getPermissionDisplayItems(context: Context): List<PermissionItem> {
        val list = mutableListOf<PermissionItem>()

        // 1. Contacts
        list.add(
            PermissionItem(
                permission = Manifest.permission.READ_CONTACTS,
                title = "Contact Matching",
                description = "Find friends already on NEXA via one-way cryptographic phone hashes without uploading your address book.",
                isGranted = isPermissionGranted(context, Manifest.permission.READ_CONTACTS)
            )
        )

        // 2. Data SMS Airwaves
        val hasSms = isPermissionGranted(context, Manifest.permission.SEND_SMS) &&
                isPermissionGranted(context, Manifest.permission.RECEIVE_SMS)
        list.add(
            PermissionItem(
                permission = Manifest.permission.SEND_SMS,
                title = "Port 8901 Data SMS (Offline Fallback)",
                description = "Sends and receives encrypted port-bound binary SMS when internet is dead. Messages stay 100% invisible to default SMS apps.",
                isGranted = hasSms
            )
        )

        // 3. Notifications (API 33+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(
                PermissionItem(
                    permission = Manifest.permission.POST_NOTIFICATIONS,
                    title = "Instant Message Alerts",
                    description = "Receive immediate alerts for direct messages and mesh offline delivery events.",
                    isGranted = isPermissionGranted(context, Manifest.permission.POST_NOTIFICATIONS)
                )
            )
        }

        // 4. Bluetooth / Nearby Mesh
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val allBtGranted = isPermissionGranted(context, Manifest.permission.BLUETOOTH_SCAN) &&
                    isPermissionGranted(context, Manifest.permission.BLUETOOTH_ADVERTISE) &&
                    isPermissionGranted(context, Manifest.permission.BLUETOOTH_CONNECT)

            list.add(
                PermissionItem(
                    permission = Manifest.permission.BLUETOOTH_SCAN,
                    title = "Nearby Bluetooth Mesh",
                    description = "Discover nearby peers and route encrypted messages offline when internet connectivity is unavailable.",
                    isGranted = allBtGranted
                )
            )
        } else {
            // Android 7.0 to 11
            list.add(
                PermissionItem(
                    permission = Manifest.permission.ACCESS_FINE_LOCATION,
                    title = "Nearby Bluetooth Mesh (Location)",
                    description = "Android 7–11 requires Location permission strictly to scan for nearby Bluetooth devices. NEXA never tracks your GPS coordinates.",
                    isGranted = isPermissionGranted(context, Manifest.permission.ACCESS_FINE_LOCATION)
                )
            )
        }

        return list
    }

    fun isLocationNeededForBle(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
    }
}
