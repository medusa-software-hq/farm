package software.medusa.farm.cli.config

import kotlin.time.Instant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Serializes an [Instant] as its whole-second epoch value — the CLI's on-disk credential format.
 */
object InstantEpochSecondsSerializer : KSerializer<Instant> {
  override val descriptor: SerialDescriptor =
      PrimitiveSerialDescriptor("InstantEpochSeconds", PrimitiveKind.LONG)

  override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeLong(value.epochSeconds)

  override fun deserialize(decoder: Decoder): Instant =
      Instant.fromEpochSeconds(decoder.decodeLong())
}
