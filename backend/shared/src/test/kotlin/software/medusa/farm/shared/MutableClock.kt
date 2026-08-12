package software.medusa.farm.shared

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** A [Clock] whose instant the test advances by hand, to drive the soft-orphan watermark. */
class MutableClock(private var current: Instant) : Clock() {
  override fun instant(): Instant = current

  override fun getZone(): ZoneId = ZoneOffset.UTC

  override fun withZone(zone: ZoneId?): Clock = this

  fun advanceMinutes(minutes: Long) {
    current = current.plusSeconds(minutes * 60)
  }
}
