package software.medusa.farm.worker

import kotlin.test.Test
import kotlin.test.assertEquals

class FibonacciTest {
  @Test
  fun `sequence starts 0 1 1 2 3 5 8 13`() {
    val first = fibonacci().take(8).map { (index, value) -> index to value.toInt() }.toList()
    assertEquals(listOf(0 to 0, 1 to 1, 2 to 1, 3 to 2, 4 to 3, 5 to 5, 6 to 8, 7 to 13), first)
  }

  @Test
  fun `fib(40) is 102334155`() {
    assertEquals(102334155, fibonacci().first { it.first == 40 }.second.toInt())
  }
}
