package software.medusa.farm.server

import io.grpc.Status
import io.grpc.StatusRuntimeException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import software.medusa.farm.shared.InMemoryFibonacciStore
import software.medusa.farm.v1.LinkOrgRequest
import software.medusa.farm.v1.ListFibonacciRequest

class FarmServiceLinkOrgTest {
  private val service =
      FarmServiceImpl(
          InMemoryFibonacciStore(),
          NoOpFibonacciStarter,
          gitHubOrgs = null,
      )

  @Test
  fun `without a configured app, LinkOrg is UNIMPLEMENTED`() = runBlocking {
    val failure =
        assertFailsWith<StatusRuntimeException> {
          service.linkOrg(LinkOrgRequest.newBuilder().setOrgLogin("acme").build())
        }
    assertEquals(Status.Code.UNIMPLEMENTED, failure.status.code)
  }

  @Test
  fun `an unconfigured app leaves the other RPCs working`() = runBlocking {
    assertEquals(
        0,
        service.listFibonacci(ListFibonacciRequest.getDefaultInstance()).numbersCount,
    )
  }
}
