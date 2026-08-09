package software.medusa.farm.server

import software.medusa.farm.shared.CounterId
import software.medusa.farm.shared.CounterStore
import software.medusa.farm.shared.FibonacciStore
import software.medusa.farm.v1.DecrementRequest
import software.medusa.farm.v1.DecrementResponse
import software.medusa.farm.v1.FarmServiceGrpcKt
import software.medusa.farm.v1.FibonacciNumber
import software.medusa.farm.v1.GetCountRequest
import software.medusa.farm.v1.GetCountResponse
import software.medusa.farm.v1.IncrementRequest
import software.medusa.farm.v1.IncrementResponse
import software.medusa.farm.v1.ListFibonacciRequest
import software.medusa.farm.v1.ListFibonacciResponse

private val mainCounterId = CounterId("main")

class FarmServiceImpl(
    private val counterStore: CounterStore,
    private val fibonacciStore: FibonacciStore,
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

  override suspend fun listFibonacci(request: ListFibonacciRequest): ListFibonacciResponse =
      ListFibonacciResponse.newBuilder()
          .addAllNumbers(
              fibonacciStore.list().map {
                FibonacciNumber.newBuilder()
                    .setIndex(it.index)
                    .setValue(it.value.toString())
                    .build()
              }
          )
          .build()
}
