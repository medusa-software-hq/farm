package software.medusa.farm.github

import kotlin.test.Test
import kotlin.test.assertEquals

class GhAppPermissionSetTest {
  @Test
  fun `takes write where read is asked for, but not the other way round`() {
    val held = permissionSet(GhPermissionId.Contents to GhPermissionMode.Write)

    assertEquals(
        GhAppPermissionSet.CoverageResult.Covered,
        GhAppPermissionSet.checkCoverage(
            permissionSet(GhPermissionId.Contents to GhPermissionMode.Read),
            held,
        ),
    )
    assertEquals(
        GhAppPermissionSet.CoverageResult.MissingPermissions(
            permissionSet(GhPermissionId.Issues to GhPermissionMode.Read)
        ),
        GhAppPermissionSet.checkCoverage(
            permissionSet(GhPermissionId.Issues to GhPermissionMode.Read),
            held,
        ),
    )
  }

  @Test
  fun `names a permission held too weakly at the mode it was asked for`() {
    val coverage =
        GhAppPermissionSet.checkCoverage(
            permissionSet(GhPermissionId.Contents to GhPermissionMode.Write),
            permissionSet(GhPermissionId.Contents to GhPermissionMode.Read),
        )

    assertEquals(
        GhAppPermissionSet.CoverageResult.MissingPermissions(
            permissionSet(GhPermissionId.Contents to GhPermissionMode.Write)
        ),
        coverage,
    )
  }

  @Test
  fun `does not mind permissions held and not asked for`() {
    val coverage =
        GhAppPermissionSet.checkCoverage(
            permissionSet(GhPermissionId.Metadata to GhPermissionMode.Read),
            permissionSet(
                GhPermissionId.Metadata to GhPermissionMode.Read,
                GhPermissionId.Administration to GhPermissionMode.Write,
            ),
        )

    assertEquals(GhAppPermissionSet.CoverageResult.Covered, coverage)
  }

  @Test
  fun `reads the missing ones the way the settings page names them`() {
    assertEquals(
        "contents: write, pull_requests: write",
        permissionSet(
                GhPermissionId.PullRequests to GhPermissionMode.Write,
                GhPermissionId.Contents to GhPermissionMode.Write,
            )
            .describe(),
    )
  }

  private fun permissionSet(vararg modes: Pair<GhPermissionId, GhPermissionMode>) =
      GhAppPermissionSet(modes.toMap())
}
