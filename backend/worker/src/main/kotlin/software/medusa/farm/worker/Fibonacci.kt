package software.medusa.farm.worker

import java.math.BigInteger

/**
 * The Fibonacci sequence as (index, value) pairs. Infinite — a caller must bound its consumption.
 */
fun fibonacci(): Sequence<Pair<Int, BigInteger>> = sequence {
  var index = 0
  var current = BigInteger.ZERO
  var next = BigInteger.ONE
  while (true) {
    yield(index to current)
    val following = current + next
    current = next
    next = following
    index++
  }
}
