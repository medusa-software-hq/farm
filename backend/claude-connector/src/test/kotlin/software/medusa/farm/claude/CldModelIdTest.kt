package software.medusa.farm.claude

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CldModelIdTest {
  @Test
  fun `an exact id matches itself`() {
    assertTrue(CldModelId("claude-opus-4-5").matches(CldModelId("claude-opus-4-5")))
  }

  @Test
  fun `a dated variant matches the base id`() {
    assertTrue(CldModelId("claude-opus-4-5").matches(CldModelId("claude-opus-4-5-20251101")))
  }

  @Test
  fun `a different model whose id starts with the requested one does not match`() {
    assertFalse(CldModelId("claude-opus-4").matches(CldModelId("claude-opus-4-5")))
  }
}
