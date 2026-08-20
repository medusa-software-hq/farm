package software.medusa.farm.github

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GhPullRequestFileTest {
  @Test
  fun `numbers an added line from the hunk it is in`() {
    val file =
        GhPullRequestFile(
            path = "src/Greeter.java",
            patch =
                """
                @@ -10,4 +10,4 @@ class Greeter {
                   String greet() {
                -    return "Hello";
                +    return "Howdy";
                   }
                """
                    .trimIndent(),
        )

    // 10 is the context line the hunk opens on, 11 the removed one, which is on the other side.
    assertEquals(11, file.findFirstAddedLine())
  }

  @Test
  fun `counts from the hunk the addition is in, not the first`() {
    val file =
        GhPullRequestFile(
            path = "src/Greeter.java",
            patch =
                """
                @@ -1,2 +1,2 @@
                 package com.example;
                 
                @@ -30,3 +30,4 @@ class Greeter {
                   }
                +  // added
                """
                    .trimIndent(),
        )

    assertEquals(31, file.findFirstAddedLine())
  }

  @Test
  fun `has no line to offer for a file it only removes from`() {
    val file =
        GhPullRequestFile(
            path = "src/Greeter.java",
            patch = "@@ -10,2 +10,1 @@\n   String greet() {\n-    return \"Hello\";",
        )

    assertNull(file.findFirstAddedLine())
  }

  @Test
  fun `has no line to offer for a file GitHub does not diff`() {
    assertNull(GhPullRequestFile(path = "logo.png", patch = null).findFirstAddedLine())
  }
}
