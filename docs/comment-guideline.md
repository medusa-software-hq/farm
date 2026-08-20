# Code Comment Guideline

## Introduction

A comment is written once and read for years. The compiler never checks it, no test fails when it lies, and no refactoring tool updates it. Code is kept honest by machines; comments only by discipline. So write every comment as if it will outlive you unchanged — because it will likely stay as-is until someone happens to reopen that file for unrelated reasons.

From this, everything follows:

- **Comments are (nearly) forever.** Assume nobody will come back to update this one. If it's only true *today*, it probably doesn't belong in the code.
- **Every word costs.** A comment is a maintenance liability paid for by every future reader. It should earn its keep.
- **Nothing rots faster than prose next to code.** Code is forced to stay true; comments are not.
- **A great comment is priceless; a stale one is worse than none.** Silence is honest. A brief, general, durable comment usually beats a rich, specific, perishable one.

These are rules of thumb, not laws. Good exceptions exist to every one of them — the guideline's job is to make an exception a *conscious choice*, not a default.

## Rules of thumb

1. **Comment the *why* rather than the *what*.** If a comment merely paraphrases the line below it, it's usually best deleted. The code already says what it does.

2. **Name the role, then stop.** When a declaration deserves a comment, state what part it plays in the system — at the highest level of abstraction that still distinguishes it from its siblings — and avoid descending into how it plays it. The *how* changes; the *role* endures. "Entry point of the worker process" is a role; "arg-less, env-configured, connects to Postgres" is three facts waiting to expire.

3. **Document invariants the code can't express.** Two files that must stay in sync, an ordering requirement, a reason a "simpler" approach was rejected — these tend to be the highest-value comments, because the information exists nowhere else.

The rejected-alternative case has a bar, though: the alternative must be one a competent reader would actually reach for, not merely one that exists. Absence is unbounded — noting that you didn't use something nobody would have assumed you had (a paid tier, a service you don't run) documents nothing, and invites the same note about everything else you don't have.

4. **Keep roadmap — and history — out of source.** "X will be added later," "stand-in for Y," "grows into Z" looking forward; "this used to be Z," "retained from the original design," "kept while we migrate" looking back. Both are narrative about a moment, not description of the code, and both belong in the commit message, the PR, or the tracker, where they are timestamped and expected to age. History is the easier one to write and the harder one to notice going stale: the change it describes recedes, the prose stays. Comments in code should be true *now* and likely to stay true.

5. **Never explain a standard practice.** If something is a known engineering concept — a lockfile,
a retry, a cache, dependency injection, why a secret isn't hardcoded — the reader either knows it or
can look it up, and your paragraph is neither the best explanation of it nor kept up to date. Two
corollaries: don't re-document a principle everywhere you apply it (are you going to restate it at
every call site?), and don't announce that you did the only reasonable thing. Comment what is
specific to *this* code — the surprising choice, not the standard one.

6. **Comment at the right layer.** A utility generally shouldn't know how its callers use it; a config class shouldn't describe the process architecture. A comment mentioning concepts from a layer above its file is a sign it may belong elsewhere.

7. **Avoid restating what's already stated.** In the code, an error message, a task description, another comment. Duplicated information is information that will diverge — duplicate only when the payoff is clearly worth that risk.

8. **Be wary of naming other identifiers in comments.** A comment mentioning a class, env var, task, or file breaks on the cheapest, most routine change we make — a rename — and rename tooling updates code, not prose. Prefer describing the role: "the connection string from the environment," rather than "`DATABASE_URL`." *Distance* is what makes a name risky: a token on the very next line cannot dangle, and saying what an argument buys you often earns its keep even though it names that argument. Referring to your own declaration is fine, resolved doc links (`[Foo]`) that tooling checks and refactors are fine, and a bare foreign name can be justified when the reference *is* the point (a rule-3 invariant) — then it helps to say so, so a future renamer knows the mention is deliberate.

9. **Prefer solving repo-wide concerns centrally.** "How to run this," "our conventions," "the schema ownership model" — if it applies broadly, the README, the tooling, or a doc is usually the better home. An ad-hoc note in one file tends to solve the problem nowhere and rot alone. (A local pointer to the central place can still be kind.)

10. **Prefer a mechanism to a comment.** A test name, an assertion, a type, a task `description` field — anything checked or surfaced by tooling generally beats prose. Reach for the comment when no mechanism fits, not before checking whether one does.

11. **Put each explanation next to what it explains.** A comment above a block that explains three
of its arguments is three comments in the wrong place: the reader has to map prose back onto lines,
and each note outlives the line it was about. Attach the explanation to the argument, key or
parameter it concerns, and leave the header to state only the block's role. The tell is a header
essay above a body with no comments in it at all:

```
# Essay covering foo, and bar, and some history, and baz too
resource "thing" "name" {
  foo = …
  bar = …
  baz = …
}
```

Prefer:

```
# What this block is for.
resource "thing" "name" {
  # Why this value.
  foo = …
}
```

Where the syntax won't carry an inline comment, a header is fine — but keep it to what genuinely
spans the block. When what you want to mark is a *run of declarations* with no syntax bracketing it,
prefer a region over a paragraph:

```
#region Deployment environments

# Production environment
resource … { }

# Allow deployments only from the trunk branch
resource … { }

#endregion
```

The region names the group and folds in an editor; each member carries its own short label. That
beats one header essay trying to describe every member at once — and a region needs no explanatory
comment of its own unless there is something real to say.

12. **Scope determines lifespan; write accordingly.** An inline comment is bound to one line and dies with it: low risk. A file-header essay is bound to nothing and dies never: handle with care.

13. **When in doubt, lean toward leaving it out.** A missing comment is rarely a serious problem; a wrong one usually is.

## Interface and implementation

Two different jobs, and the language usually gives each its own place. Keep them apart.

**Interface** — what a caller needs to use the thing without reading it. This is documentation
proper: KDoc, docstrings, `///`. It belongs in the syntax reserved for it, so tooling renders it,
checks its links, and carries it to the call site.

- Use the documentation form wherever the language expects it. A line comment sitting where a doc
  comment belongs gets none of that support, and reads as an oversight.
- Public types and public members generally deserve one. Say what the thing is and what a caller
  gets back — including the parts a signature can't state, like what a never-touched counter returns.
- Private members may have one. Not obligatory; write it when a reader of the *class* would ask.
- None of this means "add one everywhere". An empty doc comment restating the signature is worse
  than none — every other rule here still applies.

**Implementation** — why this code does it this way. That belongs *inside* the body, next to the
lines it concerns, not hoisted into the doc comment where callers will read it and maintainers will
forget it. If a note is about how the work is done rather than what is promised, it goes in the
braces.

A doc comment describing implementation is a common failure: it leaks detail callers shouldn't
depend on, and it goes stale the first time the body changes while the contract doesn't.

## The test suite

Useful questions to run a comment through before committing:

1. **The deletion test.** If this comment were removed or cut to one line, would anything real be lost? Reduce until further reduction loses information the code can't provide — often that leaves one line; sometimes it leaves nothing, and that's fine.

2. **The rot test.** Would a routine, mechanical change — a rename, a moved file, a shipped feature — make this false without anyone noticing? If yes, consider moving the information where it's checked, or dropping it.

3. **The layer test.** Does this comment mention anything its code can't see — callers, the process architecture, other repos, the roadmap? If yes, it may belong in a different file, or a different medium entirely.

4. **The locality test.** Is every sentence about the block as a whole? A sentence about one line
inside it belongs on that line.

5. **The stranger test.** Would someone with no knowledge of our plans, reading this in three years, find every sentence still true and still useful? Comments are read by strangers in the future more than by colleagues in the present.

The deletion test is a good first filter — most comments that fail it make the other tests unnecessary.