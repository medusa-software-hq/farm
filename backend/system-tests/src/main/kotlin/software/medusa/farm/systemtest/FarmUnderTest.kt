package software.medusa.farm.systemtest

/**
 * A farm a system test can drive: where its API answers, and nothing about how it came to be
 * running. One started in this process and one deployed somewhere are the same thing from here,
 * which is what lets the same tests cover both.
 */
class FarmUnderTest(
    val apiHost: String,
    val apiPort: Int,
)
