import re
import sys
from dataclasses import dataclass

DIRECTIVE_PATTERNS = [
    re.compile(r"^\s*(eslint|eslint-disable|eslint-enable|eslint-disable-next-line|eslint-disable-line)\b"),
    re.compile(r"^\s*@ts-(expect-error|ignore|nocheck)\b"),
    re.compile(r"^\s*prettier-ignore\b"),
    re.compile(r"^\s*istanbul ignore\b"),
    re.compile(r"^\s*c8 ignore\b"),
    re.compile(r"^\s*/\s*<reference\b"),
    re.compile(r"^\s*CHECKSTYLE:(OFF|ON)\b"),
    re.compile(r"^\s*NOSONAR\b"),
    re.compile(r"^\s*language=", re.IGNORECASE),
    re.compile(r"@(vitest|jest)-environment\b"),
    re.compile(r"\bwebpack(ChunkName|Mode|Prefetch|Preload|Ignore)\b"),
    re.compile(r"@vite-ignore\b"),
    re.compile(r"[#@]__PURE__"),
    re.compile(r"@jsx(ImportSource|Runtime|Frag)?\b"),
    re.compile(r"^\s*(global|globals|eslint-env)\s"),
    re.compile(r"^\s*noinspection\b"),
    re.compile(r"@formatter:(on|off)"),
    re.compile(r"\bNOPMD\b"),
    re.compile(r"sourceMappingURL="),
    re.compile(r"@(license|preserve)\b"),
]
LICENSE_PATTERN = re.compile(r"(copyright|licen[cs]e|spdx-license-identifier)", re.IGNORECASE)
REGEX_PRECEDERS = set("(,=:[!&|?{};+-*%<>~^")
REGEX_KEYWORDS = {"return", "typeof", "instanceof", "in", "of", "new", "delete", "void", "throw", "case", "do", "else", "yield", "await"}


@dataclass
class Comment:
    line: int
    end_line: int
    text: str
    kind: str

    def is_directive(self) -> bool:
        body = self.text.lstrip("/*").strip()
        return any(p.search(body) for p in DIRECTIVE_PATTERNS)

    def is_license(self) -> bool:
        return self.line <= 5 and bool(LICENSE_PATTERN.search(self.text))


def _previous_significant(source: str, index: int) -> str:
    j = index - 1
    while j >= 0 and source[j] in " \t\r\n":
        j -= 1
    if j < 0:
        return ""
    if source[j].isalnum() or source[j] in "_$":
        k = j
        while k >= 0 and (source[k].isalnum() or source[k] in "_$"):
            k -= 1
        return source[k + 1:j + 1]
    return source[j]


def _regex_allowed(source: str, index: int) -> bool:
    prev = _previous_significant(source, index)
    if prev == "":
        return True
    if prev in REGEX_KEYWORDS:
        return True
    if len(prev) == 1 and prev in REGEX_PRECEDERS:
        return True
    return False


def scan(source: str, language: str) -> list:
    comments = []
    i = 0
    n = len(source)
    line = 1
    template_depth = []

    def advance_to(j):
        nonlocal i, line
        line += source.count("\n", i, j)
        i = j

    while i < n:
        c = source[i]
        nxt = source[i + 1] if i + 1 < n else ""
        if language == "java" and source.startswith('"""', i):
            end = source.find('"""', i + 3)
            advance_to(n if end < 0 else end + 3)
            continue
        if c == "/" and nxt == "/" and not (i > 0 and source[i - 1] == ":"):
            end = source.find("\n", i)
            end = n if end < 0 else end
            comments.append(Comment(line, line, source[i:end], "line"))
            advance_to(end)
            continue
        if c == "/" and nxt == "*":
            end = source.find("*/", i + 2)
            end = n if end < 0 else end + 2
            start_line = line
            text = source[i:end]
            advance_to(end)
            comments.append(Comment(start_line, line, text, "block"))
            continue
        if c in "\"'":
            j = i + 1
            while j < n and source[j] != c and source[j] != "\n":
                j += 2 if source[j] == "\\" else 1
            advance_to(min(j + 1, n))
            continue
        if language == "ts" and c == "`":
            j = i + 1
            while j < n:
                if source[j] == "\\":
                    j += 2
                    continue
                if source[j] == "`":
                    break
                if source.startswith("${", j):
                    template_depth.append(1)
                    advance_to(j + 2)
                    j = None
                    break
                j += 1
            if j is None:
                continue
            advance_to(min(j + 1, n))
            continue
        if language == "ts" and template_depth:
            if c == "{":
                template_depth[-1] += 1
            elif c == "}":
                template_depth[-1] -= 1
                if template_depth[-1] == 0:
                    template_depth.pop()
                    j = i + 1
                    while j < n:
                        if source[j] == "\\":
                            j += 2
                            continue
                        if source[j] == "`":
                            break
                        if source.startswith("${", j):
                            template_depth.append(1)
                            advance_to(j + 2)
                            j = None
                            break
                        j += 1
                    if j is None:
                        continue
                    advance_to(min(j + 1, n))
                    continue
        if language == "ts" and c == "/" and _regex_allowed(source, i):
            j = i + 1
            in_class = False
            while j < n and source[j] != "\n":
                ch = source[j]
                if ch == "\\":
                    j += 2
                    continue
                if ch == "[":
                    in_class = True
                elif ch == "]":
                    in_class = False
                elif ch == "/" and not in_class:
                    break
                j += 1
            if j < n and source[j] == "/":
                advance_to(j + 1)
                continue
        advance_to(i + 1)
    return comments


def language_for(path: str):
    if path.endswith(".java"):
        return "java"
    if path.endswith((".ts", ".tsx", ".js", ".jsx", ".mjs")):
        return "ts"
    return None


if __name__ == "__main__":
    for path in sys.argv[1:]:
        lang = language_for(path)
        if not lang:
            continue
        with open(path, encoding="utf-8", errors="replace") as handle:
            for comment in scan(handle.read(), lang):
                flag = "directive" if comment.is_directive() else "license" if comment.is_license() else "comment"
                print(f"{path}:{comment.line}:{flag}:{comment.text[:80]!r}")
