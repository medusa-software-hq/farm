package software.medusa.farm.claude

/** An amount of money. */
@JvmInline
value class CldCost(
    /** Amount in US dollars. Fractions of a cent are meaningful and are not rounded away. */
    val usdAmount: Double,
)
