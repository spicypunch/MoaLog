package kr.jm.moalog.sync

import kr.jm.moalog.core.contracts.UuidString
import platform.Foundation.NSDate
import platform.Foundation.NSUUID
import platform.Foundation.timeIntervalSince1970

actual object PlatformSyncUuidFactory : kr.jm.moalog.core.database.SyncUuidFactory {
    override fun create(): UuidString = UuidString(NSUUID().UUIDString.lowercase())
}

actual object PlatformSyncClock : SyncClock {
    override fun nowEpochMillis(): Long = (NSDate().timeIntervalSince1970 * 1_000.0).toLong()
}
