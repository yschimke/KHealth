package com.khealth

import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.datetime.toJavaInstant

actual class KHealth {
    constructor(activity: ComponentActivity) {
        this.activity = activity
        this.coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        this.permissionsChannel = Channel()
    }

    internal constructor(
        client: HealthConnectClient,
        coroutineScope: CoroutineScope,
        isHealthStoreAvailable: Boolean,
        permissionsChannel: Channel<Set<String>>
    ) {
        this.client = client
        this.coroutineScope = coroutineScope
        this.testIsHealthStoreAvailable = isHealthStoreAvailable
        this.permissionsChannel = permissionsChannel
    }

    private var activity: ComponentActivity? = null

    private lateinit var client: HealthConnectClient
    private val coroutineScope: CoroutineScope
    private var testIsHealthStoreAvailable: Boolean? = null
    private val permissionsChannel: Channel<Set<String>>

    private lateinit var permissionsLauncher: ActivityResultLauncher<Set<String>>

    actual fun initialise() {
        if (!::client.isInitialized) client = HealthConnectClient.getOrCreate(activity!!)
        if (!::permissionsLauncher.isInitialized) {
            val permissionContract = PermissionController.createRequestPermissionResultContract()
            permissionsLauncher = activity!!.registerForActivityResult(permissionContract) {
                coroutineScope.launch {
                    permissionsChannel.send(it)
                }
            }
        }
    }

    actual val isHealthStoreAvailable: Boolean
        get() = testIsHealthStoreAvailable
            ?: (HealthConnectClient.getSdkStatus(activity!!) == HealthConnectClient.SDK_AVAILABLE)

    internal actual fun verifyHealthStoreAvailability() {
        if (!isHealthStoreAvailable) throw HealthStoreNotAvailableException
    }

    actual suspend fun checkPermissions(
        vararg permissions: KHPermission
    ): Set<KHPermissionWithStatus> {
        verifyHealthStoreAvailability()
        val grantedPermissions = client.permissionController.getGrantedPermissions()
        return permissions.toPermissionsWithStatuses(grantedPermissions).toSet()
    }

    actual suspend fun requestPermissions(
        vararg permissions: KHPermission
    ): Set<KHPermissionWithStatus> {
        verifyHealthStoreAvailability()
        val permissionSets = permissions.map { entry -> entry.toPermissions() }

        if (::permissionsLauncher.isInitialized) {
            permissionsLauncher.launch(permissionSets.flatten().map { it.first }.toSet())
        } else {
            logError(HealthStoreNotInitialisedException)
        }

        val grantedPermissions = permissionsChannel.receive()
        return permissions.toPermissionsWithStatuses(grantedPermissions).toSet()
    }

    actual suspend fun writeData(vararg records: KHRecord): KHWriteResponse {
        try {
            verifyHealthStoreAvailability()
            val hcRecords = records.mapNotNull { record -> record.toHCRecord() }
            logDebug("Inserting ${hcRecords.size} records...")
            val responseIDs = client.insertRecords(hcRecords).recordIdsList
            logDebug("Inserted ${responseIDs.size} records")
            return when {
                responseIDs.size != hcRecords.size && responseIDs.isEmpty() -> {
                    KHWriteResponse.Failed(Exception("No records were written!"))
                }

                responseIDs.size != hcRecords.size -> KHWriteResponse.SomeFailed

                else -> KHWriteResponse.Success
            }
        } catch (t: Throwable) {
            val parsedThrowable = when (t) {
                is SecurityException -> NoWriteAccessException(t.message?.extractHealthPermission())
                else -> t
            }
            logError(t)
            return KHWriteResponse.Failed(parsedThrowable)
        }
    }

    actual suspend fun readRecords(request: KHReadRequest): List<KHRecord> {
        return try {
            val recordClass = request.dataType.toRecordClass() ?: return emptyList()

            val hcRecords = client.readRecords(
                request = ReadRecordsRequest(
                    recordType = recordClass,
                    timeRangeFilter = TimeRangeFilter.between(
                        startTime = request.startDateTime.toJavaInstant(),
                        endTime = request.endDateTime.toJavaInstant()
                    ),
                )
            ).records

            hcRecords.map { record -> record.toKHRecord(request) }
        } catch (t: Throwable) {
            logError(t)
            emptyList()
        }
    }
}

internal enum class KHPermissionType { Read, Write }
