package kr.jm.moalog.server.sync.application

import kr.jm.moalog.core.contracts.SyncEntityType
import kr.jm.moalog.core.contracts.SyncMutationDto
import kr.jm.moalog.core.contracts.SyncOperation
import kr.jm.moalog.server.sync.domain.CanonicalSyncMutation
import kr.jm.moalog.server.sync.domain.SyncAction
import kr.jm.moalog.server.sync.domain.SyncEntityKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

@Component
class SyncCanonicalizer {
    /**
     * The device id is deliberately part of the fingerprint. A mutation id belongs to the device that
     * created it, so replaying that id from another device is treated as conflicting content.
     */
    fun canonicalize(deviceId: UUID, mutation: SyncMutationDto): CanonicalSyncMutation {
        val payload = mutation.payload?.canonicalized()
        val canonicalPayload = payload?.toString()
        val content = buildJsonObject {
            put("baseVersion", mutation.baseVersion?.let(::JsonPrimitive) ?: JsonNull)
            put("deviceId", deviceId.toString())
            put("entityId", mutation.entityId.value)
            put("entityType", mutation.entityType.wireValue())
            put("mutationId", mutation.mutationId.value)
            put("operation", mutation.operation.wireValue())
            put("payload", payload ?: JsonNull)
        }.canonicalized().toString()
        return CanonicalSyncMutation(
            mutationId = UUID.fromString(mutation.mutationId.value),
            deviceId = deviceId,
            entityType = mutation.entityType.toDomain(),
            entityId = UUID.fromString(mutation.entityId.value),
            operation = mutation.operation.toDomain(),
            baseVersion = mutation.baseVersion,
            canonicalPayload = canonicalPayload,
            fingerprint = sha256(content),
            canonicalContentSize = canonicalPayload?.toByteArray(StandardCharsets.UTF_8)?.size ?: 0,
        )
    }

    private fun JsonElement.canonicalized(): JsonElement = when (this) {
        is JsonObject -> JsonObject(entries.sortedBy { it.key }.associate { it.key to it.value.canonicalized() })
        is JsonArray -> JsonArray(map { it.canonicalized() })
        else -> this
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun SyncEntityType.toDomain(): SyncEntityKind = SyncEntityKind.valueOf(name)
    private fun SyncOperation.toDomain(): SyncAction = SyncAction.valueOf(name)
    private fun SyncEntityType.wireValue(): String = name.lowercase()
    private fun SyncOperation.wireValue(): String = name.lowercase()
}
