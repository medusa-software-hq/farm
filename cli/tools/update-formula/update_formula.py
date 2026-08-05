import argparse
import hashlib
import os
import textwrap
from pathlib import Path

from github import Github, GithubException

TAP_REPO = "medusa-software-hq/homebrew-tap"
RELEASES_REPO = "medusa-software-hq/counter-releases"
FORMULA_PATH = "Formula/counter.rb"


def compute_sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def render_formula(version: str, sha256: str) -> str:
    url = f"https://github.com/{RELEASES_REPO}/releases/download/{version}/counter-cli.jar"
    return textwrap.dedent(f"""\
        class Counter < Formula
          desc "Counter CLI"
          homepage "https://github.com/medusa-software-hq/counter"
          url "{url}"
          sha256 "{sha256}"
          version "{version}"

          depends_on "openjdk@21"

          def install
            libexec.install "counter-cli.jar"
            # java_version pins the launcher to the openjdk@21 we depend on and build against, so the
            # CLI never silently runs on a newer JDK (where Clikt's JNA calls print JEP 498 warnings).
            bin.write_jar_script libexec/"counter-cli.jar",
                                 "ms-counter",
                                 "--enable-native-access=ALL-UNNAMED",
                                 java_version: "21"
          end
        end
    """)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--version", required=True)
    parser.add_argument("--jar", required=True, type=Path)
    args = parser.parse_args()

    token = os.environ["GITHUB_TOKEN"]
    sha256 = compute_sha256(args.jar)
    formula = render_formula(args.version, sha256)
    message = f"Update Counter CLI to {args.version}"

    repo = Github(token).get_repo(TAP_REPO)

    try:
        existing = repo.get_contents(FORMULA_PATH)
        repo.update_file(FORMULA_PATH, message, formula, existing.sha)
    except GithubException as e:
        if e.status != 404:
            raise
        repo.create_file(FORMULA_PATH, message, formula)


if __name__ == "__main__":
    main()
