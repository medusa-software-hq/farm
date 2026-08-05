package software.medusa.farm.server

import software.medusa.farm.v1.DecrementRequest
import software.medusa.farm.v1.DecrementResponse
import software.medusa.farm.v1.FarmServiceGrpcKt
import software.medusa.farm.v1.GetCountRequest
import software.medusa.farm.v1.GetCountResponse
import software.medusa.farm.v1.IncrementRequest
import software.medusa.farm.v1.IncrementResponse

class FarmServiceImpl(
    private val counterStore: CounterStore,
) : FarmServiceGrpcKt.FarmServiceCoroutineImplBase() {
  override suspend fun getCount(request: GetCountRequest): GetCountResponse =
      GetCountResponse.newBuilder().setCount(counterStore.getCount(mainCounterId)).build()

  override suspend fun increment(request: IncrementRequest): IncrementResponse =
      IncrementResponse.newBuilder()
          .setCount(counterStore.incrementAndGetCount(mainCounterId))
          .build()

  override suspend fun decrement(request: DecrementRequest): DecrementResponse =
      DecrementResponse.newBuilder()
          .setCount(counterStore.decrementAndGetCount(mainCounterId))
          .build()
}
