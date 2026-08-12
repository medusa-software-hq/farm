package software.medusa.farm.worker

import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.temporal.common.converter.DataConverter
import io.temporal.common.converter.DefaultDataConverter
import io.temporal.common.converter.JacksonJsonPayloadConverter

/**
 * Temporal payload conversion that also understands the Kotlin types crossing the activity boundary
 * (Temporal's default Jackson mapper can serialize them but not reconstruct them). Shared by the
 * worker host and the workflow tests so both encode identically.
 */
object FarmDataConverter {
  val instance: DataConverter =
      DefaultDataConverter.newDefaultInstance()
          .withPayloadConverterOverrides(
              JacksonJsonPayloadConverter(
                  JacksonJsonPayloadConverter.newDefaultObjectMapper().registerKotlinModule()
              )
          )
}
