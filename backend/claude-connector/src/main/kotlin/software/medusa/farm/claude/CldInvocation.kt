package software.medusa.farm.claude

import java.nio.file.Path

/**
 * Everything needed to launch the subprocess. The environment is **constructed, not inherited**
 * (see [CldProperProcess]): [environment] is the exact set handed to the process — the auth vars,
 * `PATH`, `HOME` (which fixes where sessions persist), and nothing else from the host.
 */
data class CldInvocation(
    val arguments: List<String>,
    val environment: Map<String, String>,
    val workingDirectory: Path,
)
