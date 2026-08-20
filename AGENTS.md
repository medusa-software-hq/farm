# Working in this repository

What this repository expects, beyond what its build files already say. Read
[the comment guideline](docs/comment-guideline.md) before writing a comment; it is the thing work
here is most often sent back over.

## Before you finish

**Compile what you changed.** You have a shell, and the compiler is the cheapest reviewer you will
ever get:

```bash
./gradlew ktfmtFormat
./gradlew compileKotlin compileTestKotlin compileIntegrationTestKotlin
```

Run `ktfmtFormat` unscoped — formatting one module while a change touches three fails the check
that guards it. Everything else CI does, CI will do; this is the part worth not finding out about
an hour later.

**Check an API you have not used before against the library, rather than writing what it ought to
be.** A builder that reads exactly like every other builder may simply not exist, and the code that
calls it will look right in a diff and fail to compile. `javap` against the dependency answers it
in seconds.

## Kotlin

**One type per file**, named for the type.

**No `fun interface`.** A named type with a named method survives a second method being added to it.

**No default arguments on constructors.** A default is a decision made once, at the definition, for
every call site that will ever exist — including the ones that should have been made to think.

**Types, not strings.** A value that means something gets a type that says so: a value class over a
`String` that could be any string. The compiler then keeps two of them from being swapped, which no
amount of care at call sites does.

**No broad `catch (Exception)`.** Failing fast beats handling badly: a caught exception that cannot
be handled meaningfully becomes a wrong answer returned confidently, in place of a loud failure
someone would have fixed.

**Do not hand-roll security or protocol code.** Signing, token formats, cryptography, wire
protocols — reach for the library that already does it. Code that looks right and is subtly wrong
is the expensive kind, and this is where it hides.

## Tests

**Pin a `runBlocking` test's return type to `Unit`.** JUnit 5 silently skips a test written

```kotlin
fun `it works`() = runBlocking { … }
```

when its last expression returns a value — `assertNotNull`, `assertFailsWith` and `assertIs` all
do. The test reports as passing without ever having run. Write it as

```kotlin
fun `it works`(): Unit = runBlocking { … }
```

**Integration tests need Docker**, and are their own source set rather than part of `test`.

**The system test costs real money and needs a farm to point at.** It drives one issue round the
whole loop against real GitHub and the real agent, so it is deliberately outside `check` and runs
on demand.

## Comments

[docs/comment-guideline.md](docs/comment-guideline.md) is the standard, in full. The short version
is that a comment is written once and read for years, nothing checks it, and nothing updates it —
so say *why* rather than *what*, name the role a thing plays rather than how it plays it, and
document what the code cannot express. Prefer silence to a comment that will be wrong in a month.
