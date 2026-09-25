import argparse
import re
import subprocess
import sys
from pathlib import Path

from comment_scan import language_for, scan

CODE_SUFFIXES = (".java", ".ts", ".tsx", ".js", ".jsx", ".mjs")
EXCLUDED = ("node_modules/", "/dist/", "/out/", "/build/", "/target/", "fixtures/", "plugin-bundles/", ".min.js",
            "/generated/", "resources/static/", "/.tmp/")
HUNK = re.compile(r"^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@")


def git(repo, *args):
    return subprocess.run(["git", *args], cwd=repo, capture_output=True, text=True, encoding="utf-8",
                          errors="replace").stdout


def changed_files(repo, base):
    names = set(git(repo, "diff", "--name-only", base).splitlines())
    names |= set(git(repo, "ls-files", "--others", "--exclude-standard").splitlines())
    return sorted(n for n in names if n.endswith(CODE_SUFFIXES) and not any(x in "/" + n for x in EXCLUDED)
                  and (repo / n).is_file())


def added_lines(repo, base, path):
    if git(repo, "cat-file", "-t", f"{base}:{path}").strip() != "blob":
        return None
    lines = set()
    for row in git(repo, "diff", "-U0", base, "--", path).splitlines():
        match = HUNK.match(row)
        if match:
            start = int(match.group(1))
            count = int(match.group(2)) if match.group(2) is not None else 1
            lines.update(range(start, start + count))
    return lines


def policy_comments(source, lang, allowed):
    found = []
    for comment in scan(source, lang):
        if comment.is_directive() or comment.is_license():
            continue
        if allowed is not None and not any(n in allowed for n in range(comment.line, comment.end_line + 1)):
            continue
        found.append(comment)
    return found


def spans_for(source, comments):
    starts = [0] + [i + 1 for i, ch in enumerate(source) if ch == "\n"]
    spans = []
    cursor = 0
    for comment in comments:
        begin = source.find(comment.text, max(starts[comment.line - 1], cursor))
        if begin < 0:
            continue
        cursor = begin + len(comment.text)
        spans.append((begin, cursor))
    return spans


def widen_for_jsx(source, begin, end):
    left = begin - 1
    while left >= 0 and source[left] in " \t":
        left -= 1
    right = end
    while right < len(source) and source[right] in " \t":
        right += 1
    if left >= 0 and source[left] == "{" and right < len(source) and source[right] == "}":
        return left, right + 1
    return begin, end


def strip(source, spans, lang):
    removed = [False] * (len(source) + 1)
    for begin, end in spans:
        if lang == "ts":
            begin, end = widen_for_jsx(source, begin, end)
        for index in range(begin, end):
            removed[index] = True
    output = []
    offset = 0
    for line in source.split("\n"):
        kept = "".join(ch for i, ch in enumerate(line) if not removed[offset + i])
        touched = len(kept) != len(line)
        offset += len(line) + 1
        if touched and not kept.strip() and line.strip():
            continue
        output.append(kept.rstrip() if touched else line)
    return "\n".join(output)


def main():
    parser = argparse.ArgumentParser(description="Section 4 comment policy for lines changed since a base ref.")
    parser.add_argument("base_ref", nargs="?", default="origin/develop")
    parser.add_argument("--strip", action="store_true", help="remove the reported comments in place")
    parser.add_argument("--only", nargs="*", default=[], help="limit to paths starting with these prefixes")
    args = parser.parse_args()
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    repo = Path(git(Path.cwd(), "rev-parse", "--show-toplevel").strip())
    base = git(repo, "merge-base", args.base_ref, "HEAD").strip()
    if not base:
        print(f"comment-policy: could not resolve a merge-base with {args.base_ref}", file=sys.stderr)
        return 1
    total = 0
    files_with_comments = 0
    for path in changed_files(repo, base):
        if args.only and not any(path.startswith(prefix) for prefix in args.only):
            continue
        full = repo / path
        source = full.read_text(encoding="utf-8", errors="replace")
        lang = language_for(path)
        comments = policy_comments(source, lang, added_lines(repo, base, path))
        if not comments:
            continue
        files_with_comments += 1
        total += len(comments)
        if args.strip:
            full.write_text(strip(source, spans_for(source, comments), lang), encoding="utf-8")
        else:
            for comment in comments[:5]:
                print(f"{path}:{comment.line}: {comment.text.splitlines()[0][:100]}")
            if len(comments) > 5:
                print(f"{path}: ... {len(comments) - 5} more")
    verb = "removed" if args.strip else "found"
    print(f"comment-policy: {verb} {total} comment(s) on changed lines in {files_with_comments} file(s) (base {base[:10]})",
          file=sys.stderr)
    return 0 if args.strip or total == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
