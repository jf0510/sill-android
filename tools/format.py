#!/usr/bin/env python3
"""Format Kotlin sources with a checksum-pinned ktfmt distribution."""
import argparse
import hashlib
import os
from pathlib import Path
import subprocess
import urllib.request

VERSION = "0.59"
SHA256 = "a493271c137074436a133d72e9e323f91604c97e0c40555629ec39598efead54"
ROOT = Path(__file__).resolve().parent.parent
URL = f"https://repo.maven.apache.org/maven2/com/facebook/ktfmt/{VERSION}/ktfmt-{VERSION}-with-dependencies.jar"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="check formatting without writing files")
    args = parser.parse_args()
    jar = ROOT / ".tools" / f"ktfmt-{VERSION}.jar"
    if not jar.exists():
        jar.parent.mkdir(exist_ok=True)
        with urllib.request.urlopen(URL, timeout=60) as response:
            data = response.read()
        if hashlib.sha256(data).hexdigest() != SHA256:
            raise SystemExit("ktfmt checksum mismatch; refusing to run")
        jar.write_bytes(data)
    if hashlib.sha256(jar.read_bytes()).hexdigest() != SHA256:
        raise SystemExit("Cached ktfmt checksum mismatch; remove .tools and retry")
    java_home = os.environ.get("JAVA_HOME")
    java = str(Path(java_home) / "bin" / "java") if java_home else "java"
    files = sorted((ROOT / "app" / "src").rglob("*.kt"))
    files += sorted(ROOT.glob("*.gradle.kts"))
    files += [ROOT / "app" / "build.gradle.kts"]
    command = [java, "-jar", str(jar), "--kotlinlang-style"]
    if args.check:
        command += ["--dry-run", "--set-exit-if-changed"]
    raise SystemExit(subprocess.call(command + [str(path) for path in files]))


if __name__ == "__main__":
    main()
