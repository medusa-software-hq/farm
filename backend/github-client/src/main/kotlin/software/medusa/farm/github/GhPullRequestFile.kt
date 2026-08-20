package software.medusa.farm.github

private val hunkHeader = Regex("""^@@ -\d+(?:,\d+)? \+(\d+)""")

/**
 * A file a pull request changes, with the diff GitHub renders for it. [patch] is absent for the
 * files it does not diff — binaries, and ones too large for it.
 */
data class GhPullRequestFile(val path: String, val patch: String?) {
  /**
   * The first line this file adds, numbered as in the head revision, or null if it adds none.
   *
   * An added line is one a review comment can be left against: GitHub takes a comment only against
   * a line the diff contains, and an added line is in the diff and on the head side of it both.
   */
  fun findFirstAddedLine(): Int? {
    var line = 0
    for (patchLine in (patch ?: return null).lineSequence()) {
      when {
        patchLine.startsWith("@@") ->
            line = hunkHeader.find(patchLine)?.groupValues?.get(1)?.toInt() ?: return null
        patchLine.startsWith("+") -> return line
        // A removed line is on the other side of the diff, and the marker is no line at all.
        patchLine.startsWith("-") || patchLine.startsWith("\\") -> Unit
        else -> line++
      }
    }
    return null
  }
}
