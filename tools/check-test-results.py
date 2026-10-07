"""Reject failed, skipped, missing, or incomplete JUnit XML verification results."""
import argparse
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


def check_results(directory: Path, required_tests: list[str]) -> int:
    files = sorted(directory.rglob("TEST-*.xml")) if directory.is_dir() else []
    if not files:
        print(f"No JUnit test reports found in {directory}", file=sys.stderr)
        return 1
    passed = set()
    count = 0
    problems = []
    for path in files:
        try:
            root = ET.parse(path).getroot()
            for suite in root.iter():
                if suite.tag.rsplit("}", 1)[-1] != "testsuite":
                    continue
                for key in ("failures", "errors", "skipped"):
                    if int(suite.get(key, "0")):
                        problems.append(f"{path.name}: {key}={suite.get(key)}")
                cases = [case for case in suite if case.tag.rsplit("}", 1)[-1] == "testcase"]
                nested = any(child.tag.rsplit("}", 1)[-1] == "testsuite" for child in suite)
                if not nested and "tests" in suite.attrib and int(suite.get("tests")) != len(cases):
                    problems.append(f"{path.name}: declared test count differs from recorded cases")
                for case in cases:
                    count += 1
                    name = f"{case.get('classname', suite.get('name', ''))}#{case.get('name', '')}"
                    status = {child.tag.rsplit("}", 1)[-1] for child in case}
                    if status.intersection({"skipped", "failure", "error"}):
                        problems.append(f"Test did not pass: {name}")
                    else:
                        passed.add(name)
        except (ET.ParseError, OSError, ValueError) as error:
            problems.append(f"Cannot read {path}: {error}")
    if count == 0:
        problems.append("Reports contain no test cases")
    for required in required_tests:
        if required not in passed:
            problems.append(f"Required test did not pass: {required}")
    if problems:
        print("\n".join(problems), file=sys.stderr)
        return 1
    print(f"Verified {count} tests: no failures, errors, or skips.")
    return 0


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("--require-test", action="append", default=[])
    args = parser.parse_args()
    sys.exit(check_results(args.directory, args.require_test))
