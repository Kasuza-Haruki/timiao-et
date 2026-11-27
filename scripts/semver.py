#!/usr/bin/env python3
"""Version helpers for the kernel tracking workflow.

  semver.py max        read tag names from stdin, print the highest
  semver.py gt A B     exit 0 when A is higher than B

Accepts an optional leading "v". A version without a prerelease suffix is
higher than the same version with one (1.0.0 > 1.0.0-rc1), and prerelease
segments compare numerically (1.0.0-rc10 > 1.0.0-rc9).
"""
import re
import sys

VALID = re.compile(r"^v?(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.]+))?(?:\+[0-9A-Za-z.]+)?$")


def parse(tag):
    m = VALID.match(tag.strip())
    if not m:
        return None
    major, minor, patch = (int(g) for g in m.groups()[:3])
    pre = m.group(4)
    if pre is None:
        return (major, minor, patch, 1, ())
    parts = []
    for part in pre.split("."):
        if part.isdigit():
            parts.append((0, int(part), ""))
        else:
            # Split a trailing number so rc10 sorts above rc9.
            m2 = re.match(r"^(.*?)(\d*)$", part)
            parts.append((1, int(m2.group(2)) if m2.group(2) else 0, m2.group(1)))
    return (major, minor, patch, 0, tuple(parts))


def main(argv):
    if len(argv) >= 2 and argv[1] == "max":
        best = None
        for line in sys.stdin:
            key = parse(line)
            if key is not None and (best is None or key > best[0]):
                best = (key, line.strip())
        if best is None:
            return 1
        print(best[1])
        return 0
    if len(argv) == 4 and argv[1] == "gt":
        a, b = parse(argv[2]), parse(argv[3])
        if a is None or b is None:
            bad = argv[2] if a is None else argv[3]
            sys.stderr.write(f"not a version: {bad}\n")
            return 2
        return 0 if a > b else 1
    sys.stderr.write(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
