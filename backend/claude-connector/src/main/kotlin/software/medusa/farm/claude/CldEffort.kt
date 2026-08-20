package software.medusa.farm.claude

/** How hard the assistant is asked to think, as the levels the CLI takes. */
enum class CldEffort(val wireValue: String) {
  Low("low"),
  Medium("medium"),
  High("high"),
}
