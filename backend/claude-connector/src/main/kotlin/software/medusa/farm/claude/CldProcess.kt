package software.medusa.farm.claude

/**
 * A streaming seam over one run of the `claude` CLI subprocess.
 *
 * [spawn] starts the process and hands back a [CldRun] whose stdout is a live message flow. The
 * production implementation is [CldProperProcess]; tests use `FakeCldProcess`, which replays canned
 * messages without touching a real binary.
 */
interface CldProcess {
  fun spawn(invocation: CldInvocation): CldRun
}
