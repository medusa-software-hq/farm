package software.medusa.farm.cli.config

import com.nimbusds.oauth2.sdk.token.RefreshToken
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Serializes a Nimbus [RefreshToken] as its raw string value — the CLI's on-disk credential form.
 */
object RefreshTokenSerializer : KSerializer<RefreshToken> {
  override val descriptor: SerialDescriptor =
      PrimitiveSerialDescriptor("RefreshToken", PrimitiveKind.STRING)

  override fun serialize(encoder: Encoder, value: RefreshToken) = encoder.encodeString(value.value)

  override fun deserialize(decoder: Decoder): RefreshToken = RefreshToken(decoder.decodeString())
}
