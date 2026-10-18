#!/usr/bin/env python3
"""Download and verify the Spotify App Remote artifact required by the build."""

import hashlib
import sys
import urllib.request
from pathlib import Path


VERSION = "0.8.0"
FILENAME = f"spotify-app-remote-release-{VERSION}.aar"
URL = (
    "https://github.com/spotify/android-sdk/releases/download/"
    f"v0.8.0-appremote_v2.1.0-auth/{FILENAME}"
)
SHA256 = "b5a6dd880eaf01f63a871cba9ef7af77c341f8a94ffc8fdf2e9021f9a9d4c198"
MAX_BYTES = 64 * 1024 * 1024


def digest(path):
    result = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(65536), b""):
            result.update(chunk)
    return result.hexdigest()


def main():
    destination = Path(__file__).resolve().parents[1] / "app" / "libs" / FILENAME
    if destination.exists():
        if digest(destination) != SHA256:
            raise RuntimeError("Existing SDK differs from the pinned artifact; inspect it before replacing it.")
        print(f"Verified Spotify App Remote SDK {VERSION} is already available.")
        return

    destination.parent.mkdir(parents=True, exist_ok=True)
    partial = destination.with_suffix(destination.suffix + ".download")
    if partial.exists():
        raise RuntimeError(f"Inspect or remove the previous partial download before retrying: {partial}")
    request = urllib.request.Request(URL, headers={"User-Agent": "NadaKuy-dev-setup"})
    with partial.open("xb") as output:
        with urllib.request.urlopen(request, timeout=30) as response:
            received = 0
            while chunk := response.read(65536):
                received += len(chunk)
                if received > MAX_BYTES:
                    raise RuntimeError("SDK download exceeded the size limit.")
                output.write(chunk)
    if digest(partial) != SHA256:
        raise RuntimeError("SDK checksum did not match; partial download retained for inspection.")
    if destination.exists():
        raise RuntimeError("SDK destination appeared during download; inspect the files before retrying.")
    partial.rename(destination)
    print(f"Downloaded and verified Spotify App Remote SDK {VERSION}.")


if __name__ == "__main__":
    try:
        main()
    except (OSError, RuntimeError) as error:
        print(f"SDK setup failed: {error}", file=sys.stderr)
        sys.exit(1)
