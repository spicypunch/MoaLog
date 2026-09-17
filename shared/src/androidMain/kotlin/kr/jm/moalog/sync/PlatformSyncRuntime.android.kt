package kr.jm.moalog.sync

import kr.jm.moalog.core.contracts.UuidString
import java.util.UUID

actual object PlatformSyncUuidFactory : kr.jm.moalog.core.database.SyncUuidFactory {
    override fun create(): UuidString = UuidString(UUID.randomUUID().toString())
}

actual object PlatformSyncClock : SyncClock {
    override fun nowEpochMillis(): Long = System.currentTimeMillis()
}
