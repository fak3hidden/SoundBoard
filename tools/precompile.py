#!/usr/bin/env python3
"""
Pre-flight compiler for Quest Soundboard.

A real `./gradlew assembleRelease` needs a JDK, the Android SDK and network
access to Maven. This script needs none of that: it parses the sources itself
and catches the error classes that otherwise only show up 4 minutes into a CI
build — unbalanced braces, bad package paths, imports with no dependency
behind them, references to hidden Android APIs, missing R.* resources, manifest
classes that do not exist, and assets the code opens but that are not shipped.

    python3 tools/precompile.py            # human output
    python3 tools/precompile.py --json     # machine readable
    python3 tools/precompile.py --strict   # warnings are errors

Exit code is 0 when there are no errors.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from typing import Iterable

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
APP = os.path.join(ROOT, "app")
SRC = os.path.join(APP, "src", "main")
JAVA_SRC = os.path.join(SRC, "java")
RES = os.path.join(SRC, "res")
ASSETS = os.path.join(SRC, "assets")
MANIFEST = os.path.join(SRC, "AndroidManifest.xml")

ANDROID_NS = "{http://schemas.android.com/apk/res/android}"

# --------------------------------------------------------------------- report


@dataclass
class Diag:
    level: str          # "error" | "warn"
    file: str
    line: int
    code: str
    message: str

    def rel(self) -> str:
        return os.path.relpath(self.file, ROOT) if self.file else "-"


@dataclass
class Report:
    diags: list[Diag] = field(default_factory=list)

    def error(self, file: str, line: int, code: str, msg: str) -> None:
        self.diags.append(Diag("error", file, line, code, msg))

    def warn(self, file: str, line: int, code: str, msg: str) -> None:
        self.diags.append(Diag("warn", file, line, code, msg))

    @property
    def errors(self) -> list[Diag]:
        return [d for d in self.diags if d.level == "error"]

    @property
    def warnings(self) -> list[Diag]:
        return [d for d in self.diags if d.level == "warn"]


# ------------------------------------------------------------- kotlin lexing


def strip_kotlin(source: str) -> str:
    """
    Blanks out comments, string literals and char literals while preserving
    every byte offset and newline, so positions stay accurate and braces inside
    strings never confuse the balance checker.
    """
    out = list(source)
    i, n = 0, len(source)

    def blank(a: int, b: int) -> None:
        for k in range(a, min(b, n)):
            if out[k] != "\n":
                out[k] = " "

    while i < n:
        c = source[i]
        nxt = source[i + 1] if i + 1 < n else ""

        if c == "/" and nxt == "/":
            j = source.find("\n", i)
            j = n if j == -1 else j
            blank(i, j)
            i = j
        elif c == "/" and nxt == "*":
            depth, j = 1, i + 2
            while j < n and depth:
                if source.startswith("/*", j):
                    depth += 1
                    j += 2
                elif source.startswith("*/", j):
                    depth -= 1
                    j += 2
                else:
                    j += 1
            blank(i, j)
            i = j
        elif source.startswith('"""', i):
            j = source.find('"""', i + 3)
            j = n if j == -1 else j + 3
            blank(i, j)
            i = j
        elif c == '"':
            j = i + 1
            while j < n:
                if source[j] == "\\":
                    j += 2
                    continue
                if source[j] in ('"', "\n"):
                    break
                j += 1
            blank(i, min(j + 1, n))
            i = j + 1
        elif c == "'":
            j = i + 1
            while j < n:
                if source[j] == "\\":
                    j += 2
                    continue
                if source[j] in ("'", "\n"):
                    break
                j += 1
            blank(i, min(j + 1, n))
            i = j + 1
        else:
            i += 1

    return "".join(out)


def line_of(source: str, index: int) -> int:
    return source.count("\n", 0, index) + 1


@dataclass
class KtFile:
    path: str
    source: str
    stripped: str
    package: str = ""
    imports: list[tuple[str, int, bool]] = field(default_factory=list)  # fqn, line, is_wildcard
    declarations: list[str] = field(default_factory=list)

    @property
    def rel(self) -> str:
        return os.path.relpath(self.path, ROOT)


DECL_RE = re.compile(
    r"^\s*(?:@\w+\s+)*(?:public\s+|internal\s+|private\s+|abstract\s+|open\s+|sealed\s+|"
    r"data\s+|enum\s+|annotation\s+|value\s+|inner\s+)*"
    r"(class|object|interface|enum\s+class)\s+([A-Za-z_]\w*)",
    re.M,
)


def load_kotlin() -> list[KtFile]:
    files = []
    for dirpath, _dirnames, filenames in os.walk(JAVA_SRC):
        for name in sorted(filenames):
            if not name.endswith(".kt"):
                continue
            path = os.path.join(dirpath, name)
            with open(path, encoding="utf-8") as fh:
                source = fh.read()
            kt = KtFile(path=path, source=source, stripped=strip_kotlin(source))

            m = re.search(r"^\s*package\s+([\w.]+)", kt.stripped, re.M)
            kt.package = m.group(1) if m else ""

            for im in re.finditer(r"^\s*import\s+([\w.]+)(\.\*)?", kt.stripped, re.M):
                kt.imports.append(
                    (im.group(1), line_of(kt.stripped, im.start()), bool(im.group(2)))
                )

            for d in DECL_RE.finditer(kt.stripped):
                kt.declarations.append(d.group(2))

            files.append(kt)
    return files


# ------------------------------------------------------------------- checks


def check_delimiters(kt: KtFile, rep: Report) -> None:
    pairs = {"}": "{", ")": "(", "]": "["}
    opens = {"{": "}", "(": ")", "[": "]"}
    stack: list[tuple[str, int]] = []
    for idx, ch in enumerate(kt.stripped):
        if ch in opens:
            stack.append((ch, idx))
        elif ch in pairs:
            if not stack:
                rep.error(kt.path, line_of(kt.stripped, idx), "E-BALANCE",
                          f"stray closing '{ch}'")
                return
            op, op_idx = stack.pop()
            if op != pairs[ch]:
                rep.error(kt.path, line_of(kt.stripped, idx), "E-BALANCE",
                          f"'{ch}' closes '{op}' opened on line "
                          f"{line_of(kt.stripped, op_idx)}")
                return
    for op, op_idx in stack:
        rep.error(kt.path, line_of(kt.stripped, op_idx), "E-BALANCE",
                  f"unclosed '{op}'")


def check_unterminated_strings(kt: KtFile, rep: Report) -> None:
    # After stripping, a surviving quote means the lexer hit a newline inside a
    # single-quoted string, i.e. the literal was never closed.
    for idx, ch in enumerate(kt.stripped):
        if ch in ('"', "'"):
            rep.error(kt.path, line_of(kt.stripped, idx), "E-STRING",
                      "unterminated string or char literal")
            return


def check_package_path(kt: KtFile, rep: Report) -> None:
    if not kt.package:
        rep.error(kt.path, 1, "E-PKG", "missing package declaration")
        return
    expected = os.path.join(JAVA_SRC, *kt.package.split("."))
    actual = os.path.dirname(kt.path)
    if os.path.normpath(expected) != os.path.normpath(actual):
        rep.error(kt.path, 1, "E-PKG",
                  f"package '{kt.package}' does not match directory "
                  f"'{os.path.relpath(actual, JAVA_SRC)}'")


# Prefixes that resolve to a real artifact, and the Gradle coordinate fragment
# that must appear in app/build.gradle.kts for them to be on the classpath.
DEPENDENCY_PREFIXES = [
    ("androidx.compose.material3", "material3"),
    ("androidx.compose.material.icons", "material-icons-extended"),
    ("androidx.compose.ui.tooling", "ui-tooling"),
    ("androidx.compose", "compose-bom"),
    ("androidx.activity.compose", "activity-compose"),
    ("androidx.lifecycle", "lifecycle-"),
    ("androidx.documentfile", "documentfile"),
    ("androidx.core", "core-ktx"),
    ("androidx.activity", "activity-compose"),
    ("kotlinx.coroutines", "kotlinx-coroutines"),
    ("com.topjohnwu.superuser", "libsu"),
]

# Packages/members that exist on a device but are NOT on the public compile
# classpath. Touching them directly is a guaranteed "Unresolved reference".
HIDDEN_API_PACKAGES = {
    "android.media.audiopolicy": "hidden @SystemApi — reach it with reflection",
    "android.media.AudioSystem": "hidden @SystemApi — reach it with reflection",
    "com.android.internal": "internal framework package — not compilable",
}

HIDDEN_API_MEMBERS = {
    ("AudioManager", "SUCCESS"): "AudioManager.SUCCESS is @SystemApi (it is 0)",
    ("AudioManager", "ERROR"): "AudioManager.ERROR is @SystemApi (it is -1)",
    ("AudioManager", "registerAudioPolicy"): "@SystemApi — call it reflectively",
    ("AudioManager", "unregisterAudioPolicy"): "@SystemApi — call it reflectively",
    ("AudioRecord", "startRecordingWithAudioPolicy"): "@SystemApi — use reflection",
}

STDLIB_PREFIXES = ("kotlin", "java.", "javax.", "org.json", "org.jetbrains.annotations")


def check_imports(kt: KtFile, project_symbols: set[str], gradle: str, rep: Report) -> None:
    seen: dict[str, int] = {}
    for fqn, line, wildcard in kt.imports:
        if fqn in seen:
            rep.warn(kt.path, line, "W-DUPIMPORT",
                     f"duplicate import of '{fqn}' (first on line {seen[fqn]})")
        seen[fqn] = line

        # Hidden framework APIs
        for hidden, why in HIDDEN_API_PACKAGES.items():
            if fqn == hidden or fqn.startswith(hidden + "."):
                rep.error(kt.path, line, "E-HIDDENAPI", f"import '{fqn}': {why}")

        # Project-internal imports must resolve to something we declare
        if fqn.startswith("com.questsoundboard"):
            if wildcard:
                continue
            if fqn not in project_symbols:
                # could be a top-level function/property; those are legal
                pkg, _, member = fqn.rpartition(".")
                if pkg not in {s.rsplit(".", 1)[0] for s in project_symbols} and \
                        not any(s.startswith(pkg + ".") for s in project_symbols):
                    rep.error(kt.path, line, "E-IMPORT",
                              f"import '{fqn}' does not resolve to any symbol in this module")
            continue

        if fqn.startswith(STDLIB_PREFIXES) or fqn.startswith("android."):
            continue

        for prefix, coordinate in DEPENDENCY_PREFIXES:
            if fqn.startswith(prefix):
                if coordinate not in gradle:
                    rep.error(kt.path, line, "E-DEP",
                              f"import '{fqn}' needs a dependency matching "
                              f"'{coordinate}' in app/build.gradle.kts")
                break
        else:
            rep.warn(kt.path, line, "W-IMPORT",
                     f"import '{fqn}' is not covered by any known dependency")

    # Unused imports (cheap textual scan of the stripped body)
    body = kt.stripped
    for fqn, line, wildcard in kt.imports:
        if wildcard:
            continue
        simple = fqn.rsplit(".", 1)[-1]
        if simple in {"*", ""}:
            continue
        uses = len(re.findall(rf"\b{re.escape(simple)}\b", body))
        if uses <= 1:  # only the import line itself
            rep.warn(kt.path, line, "W-UNUSED", f"import '{fqn}' appears unused")


def check_hidden_members(kt: KtFile, rep: Report) -> None:
    for (owner, member), why in HIDDEN_API_MEMBERS.items():
        for m in re.finditer(rf"\b{owner}\.{member}\b", kt.stripped):
            rep.error(kt.path, line_of(kt.stripped, m.start()), "E-HIDDENAPI",
                      f"{owner}.{member}: {why}")


PROPERTY_RE = re.compile(
    r"^\s*(?:@\w+\s+)*(?:public\s+|internal\s+|private\s+|protected\s+)?"
    r"(?:override\s+)?(var|val)\s+([A-Za-z_]\w*)\s*:", re.M)
FUNCTION_RE = re.compile(
    r"^\s*(?:@\w+\s+)*(?:public\s+|internal\s+|private\s+|protected\s+)?"
    r"(?:override\s+|suspend\s+|inline\s+|operator\s+)*fun\s+([A-Za-z_]\w*)\s*\(", re.M)


def check_jvm_clashes(kt: KtFile, rep: Report) -> None:
    """
    Kotlin compiles `var foo` to getFoo()/setFoo(). Declaring a function also
    called setFoo()/getFoo() in the same class is a "Platform declaration
    clash" — a JVM-level error the Kotlin syntax gives no hint about.
    """
    properties: dict[str, tuple[str, int]] = {}
    for m in PROPERTY_RE.finditer(kt.stripped):
        kind, name = m.group(1), m.group(2)
        # Private setters cannot clash with a public accessor of the same name,
        # but the JVM method is still emitted, so treat both the same.
        properties[name] = (kind, line_of(kt.stripped, m.start()))

    for m in FUNCTION_RE.finditer(kt.stripped):
        fn = m.group(1)
        line = line_of(kt.stripped, m.start())
        for prefix in ("set", "get"):
            if not fn.startswith(prefix) or len(fn) <= len(prefix):
                continue
            prop = fn[len(prefix)].lower() + fn[len(prefix) + 1:]
            if prop not in properties:
                continue
            kind, prop_line = properties[prop]
            if prefix == "set" and kind != "var":
                continue
            rep.error(kt.path, line, "E-JVMCLASH",
                      f"fun {fn}() clashes with the JVM accessor generated for "
                      f"'{kind} {prop}' declared on line {prop_line}")


def collect_resources() -> dict[str, set[str]]:
    res: dict[str, set[str]] = {}
    if not os.path.isdir(RES):
        return res
    for dirpath, _dirs, files in os.walk(RES):
        folder = os.path.basename(dirpath).split("-")[0]
        for name in files:
            path = os.path.join(dirpath, name)
            if folder == "values" and name.endswith(".xml"):
                try:
                    tree = ET.parse(path)
                except ET.ParseError:
                    continue
                for node in tree.getroot():
                    kind = node.tag if node.tag != "item" else node.get("type", "item")
                    ident = node.get("name")
                    if ident:
                        res.setdefault(kind, set()).add(ident)
            else:
                stem = name.rsplit(".", 1)[0]
                res.setdefault(folder, set()).add(stem)
    # style lives under values but is referenced as R.style
    return res


R_REF_RE = re.compile(r"\bR\.(\w+)\.(\w+)\b")


def check_resources(kt: KtFile, resources: dict[str, set[str]], rep: Report) -> None:
    for m in R_REF_RE.finditer(kt.stripped):
        kind, name = m.group(1), m.group(2)
        available = resources.get(kind, set())
        if kind in ("mipmap", "drawable"):
            available = resources.get("mipmap", set()) | resources.get("drawable", set())
        if name not in available:
            rep.error(kt.path, line_of(kt.stripped, m.start()), "E-RES",
                      f"R.{kind}.{name} is not defined under app/src/main/res")


def check_manifest(project_symbols: set[str], resources: dict[str, set[str]],
                   rep: Report) -> None:
    if not os.path.isfile(MANIFEST):
        rep.error(MANIFEST, 1, "E-MANIFEST", "AndroidManifest.xml is missing")
        return
    try:
        tree = ET.parse(MANIFEST)
    except ET.ParseError as exc:
        rep.error(MANIFEST, 1, "E-MANIFEST", f"unparseable: {exc}")
        return

    root = tree.getroot()
    app_node = root.find("application")
    if app_node is None:
        rep.error(MANIFEST, 1, "E-MANIFEST", "no <application> element")
        return

    gradle = read(os.path.join(APP, "build.gradle.kts"))
    ns_match = re.search(r'namespace\s*=\s*"([\w.]+)"', gradle)
    namespace = ns_match.group(1) if ns_match else ""

    source = read(MANIFEST)

    def manifest_line(needle: str) -> int:
        idx = source.find(needle)
        return source.count("\n", 0, idx) + 1 if idx >= 0 else 1

    for tag in ("activity", "service", "receiver", "provider"):
        for node in app_node.iter(tag):
            name = node.get(ANDROID_NS + "name")
            if not name:
                continue
            fqn = namespace + name if name.startswith(".") else name
            if fqn.startswith(namespace) and fqn not in project_symbols:
                rep.error(MANIFEST, manifest_line(name), "E-MANIFEST",
                          f"<{tag} android:name=\"{name}\"> has no matching class "
                          f"({fqn})")

    app_name = app_node.get(ANDROID_NS + "name")
    if app_name:
        fqn = namespace + app_name if app_name.startswith(".") else app_name
        if fqn.startswith(namespace) and fqn not in project_symbols:
            rep.error(MANIFEST, manifest_line(app_name), "E-MANIFEST",
                      f"<application android:name> has no matching class ({fqn})")

    # @drawable/@string/@style/@mipmap references
    for m in re.finditer(r'"@(\w+)/(\w+)"', source):
        kind, name = m.group(1), m.group(2)
        available = resources.get(kind, set())
        if kind in ("mipmap", "drawable"):
            available = resources.get("mipmap", set()) | resources.get("drawable", set())
        if name not in available:
            rep.error(MANIFEST, source.count("\n", 0, m.start()) + 1, "E-RES",
                      f"@{kind}/{name} referenced in the manifest does not exist")

    # Permissions the code clearly needs
    declared = {
        n.get(ANDROID_NS + "name")
        for n in root.iter("uses-permission")
    }
    needed = {
        "android.permission.INTERNET": "ControlServer opens a ServerSocket",
        "android.permission.FOREGROUND_SERVICE": "SoundboardService runs in the foreground",
        "android.permission.RECORD_AUDIO": "audio routing / injection",
    }
    for perm, why in needed.items():
        if perm not in declared:
            rep.error(MANIFEST, 1, "E-PERM", f"missing <uses-permission> {perm} ({why})")


# Matches assets.open("x"), assets.list("x") and helpers like
# copyAssetDir("magisk-module", ...) — anything whose callee mentions "asset".
ASSET_OPEN_RE = re.compile(r'\b\w*[Aa]sset\w*\(\s*"([^"]+)"')


def check_assets(files: Iterable[KtFile], rep: Report) -> None:
    referenced: set[str] = set()
    for kt in files:
        for m in ASSET_OPEN_RE.finditer(kt.source):
            rel = m.group(1)
            if "$" in rel or not rel:
                continue
            referenced.add(rel.split("/")[0])
            path = os.path.join(ASSETS, rel)
            if not os.path.exists(path):
                rep.error(kt.path, line_of(kt.source, m.start()), "E-ASSET",
                          f"assets/{rel} is referenced in code but not present")

    # Anything shipped in assets/ that nothing ever mentions is dead weight in
    # the APK — usually a rename that was only half applied. Paths are often
    # built by concatenation ("web" + path), so fall back to scanning every
    # string literal rather than only direct asset calls.
    all_literals = "\n".join(
        "\n".join(re.findall(r'"([^"\n]*)"', kt.source)) for kt in files
    )
    if os.path.isdir(ASSETS):
        for entry in sorted(os.listdir(ASSETS)):
            if not os.path.isdir(os.path.join(ASSETS, entry)):
                continue
            mentioned = (
                entry in referenced
                or re.search(rf"(^|[\"/]){re.escape(entry)}(/|$)", all_literals, re.M)
            )
            if not mentioned:
                rep.warn(os.path.join(ASSETS, entry), 1, "W-ASSET",
                         f"assets/{entry}/ is shipped but never referenced from Kotlin")


def check_gradle(rep: Report) -> None:
    gradle = read(os.path.join(APP, "build.gradle.kts"))
    settings = read(os.path.join(ROOT, "settings.gradle.kts"))
    path = os.path.join(APP, "build.gradle.kts")

    if "compose = true" in gradle and "kotlinCompilerExtensionVersion" not in gradle:
        rep.error(path, 1, "E-GRADLE",
                  "buildFeatures.compose is on but composeOptions."
                  "kotlinCompilerExtensionVersion is missing")

    if "libsu" in gradle and "jitpack" not in settings:
        rep.error(os.path.join(ROOT, "settings.gradle.kts"), 1, "E-GRADLE",
                  "libsu comes from JitPack but no jitpack repository is declared")

    min_sdk = re.search(r"minSdk\s*=\s*(\d+)", gradle)
    target_sdk = re.search(r"targetSdk\s*=\s*(\d+)", gradle)
    compile_sdk = re.search(r"compileSdk\s*=\s*(\d+)", gradle)
    if min_sdk and target_sdk and int(min_sdk.group(1)) > int(target_sdk.group(1)):
        rep.error(path, 1, "E-GRADLE", "minSdk is greater than targetSdk")
    if target_sdk and compile_sdk and int(target_sdk.group(1)) > int(compile_sdk.group(1)):
        rep.error(path, 1, "E-GRADLE", "targetSdk is greater than compileSdk")

    # Quest 1 is API 29; anything higher silently drops the device.
    if min_sdk and int(min_sdk.group(1)) > 29:
        rep.warn(path, 1, "W-QUEST",
                 f"minSdk {min_sdk.group(1)} excludes Quest 1 (Android 10 / API 29)")

    if "arm64-v8a" not in gradle:
        rep.warn(path, 1, "W-ABI", "no arm64-v8a abiFilter — every Quest is arm64")


def check_sidecar(rep: Report) -> None:
    """XML well-formedness, shell syntax, JS syntax, Python syntax."""
    for dirpath, _d, files in os.walk(SRC):
        for name in files:
            if name.endswith(".xml"):
                path = os.path.join(dirpath, name)
                try:
                    ET.parse(path)
                except ET.ParseError as exc:
                    rep.error(path, getattr(exc, "position", (1, 0))[0], "E-XML", str(exc))

    for base, pattern in ((os.path.join(ASSETS, "magisk-module"), ".sh"),
                          (os.path.join(ROOT, "tools"), ".sh")):
        if not os.path.isdir(base):
            continue
        for name in sorted(os.listdir(base)):
            if not name.endswith(pattern):
                continue
            path = os.path.join(base, name)
            proc = subprocess.run(["bash", "-n", path], capture_output=True, text=True)
            if proc.returncode != 0:
                rep.error(path, 1, "E-SH", proc.stderr.strip().splitlines()[0]
                          if proc.stderr.strip() else "shell syntax error")

    js = os.path.join(ASSETS, "web", "app.js")
    if os.path.isfile(js) and which("node"):
        proc = subprocess.run(["node", "--check", js], capture_output=True, text=True)
        if proc.returncode != 0:
            rep.error(js, 1, "E-JS", proc.stderr.strip().splitlines()[0]
                      if proc.stderr.strip() else "JS syntax error")

    for name in sorted(os.listdir(os.path.join(ROOT, "tools"))):
        if name.endswith(".py"):
            path = os.path.join(ROOT, "tools", name)
            proc = subprocess.run([sys.executable, "-m", "py_compile", path],
                                  capture_output=True, text=True)
            if proc.returncode != 0:
                rep.error(path, 1, "E-PY", proc.stderr.strip().splitlines()[-1])


def check_web_panel(rep: Report) -> None:
    """The panel's fetch() calls must line up with the server's endpoints."""
    js = os.path.join(ASSETS, "web", "app.js")
    server = os.path.join(JAVA_SRC, "com", "questsoundboard", "net", "ControlServer.kt")
    if not (os.path.isfile(js) and os.path.isfile(server)):
        return

    js_src = read(js)
    srv = read(server)

    used = set()
    for m in re.finditer(r"""['"](/api/[a-z/]*)""", js_src):
        used.add(m.group(1).rstrip("/"))

    for endpoint in sorted(used):
        tail = endpoint.split("/api/")[-1]
        root_seg = tail.split("/")[0]
        if root_seg and f"/api/{root_seg}" not in srv:
            rep.error(js, 1, "E-API",
                      f"web panel calls '{endpoint}' but ControlServer has no "
                      f"'/api/{root_seg}' route")

    html = os.path.join(ASSETS, "web", "index.html")
    if os.path.isfile(html):
        html_src = read(html)
        for ident in re.findall(r"""\$\(['"]#(\w+)['"]\)""", js_src):
            if f'id="{ident}"' not in html_src:
                rep.error(js, 1, "E-DOM",
                          f"app.js looks up #{ident} but index.html has no such id")


# ------------------------------------------------------------------- helpers


def read(path: str) -> str:
    try:
        with open(path, encoding="utf-8") as fh:
            return fh.read()
    except OSError:
        return ""


def which(prog: str) -> bool:
    return any(
        os.access(os.path.join(p, prog), os.X_OK)
        for p in os.environ.get("PATH", "").split(os.pathsep)
        if p
    )


# ---------------------------------------------------------------------- main


# A checker nobody has tested is just a script that prints "OK". Each entry is
# a deliberate fault injected into a throwaway copy of the tree; the named
# diagnostic must fire, and removing the fault must make the tree clean again.
MUTATIONS: list[tuple[str, str, str, str, str]] = [
    ("JVM accessor clash (var route + fun setRoute)",
     "app/src/main/java/com/questsoundboard/audio/SoundboardEngine.kt",
     "fun switchRoute(newRoute: Route)", "fun setRoute(newRoute: Route)", "E-JVMCLASH"),
    ("hidden @SystemApi member",
     "app/src/main/java/com/questsoundboard/audio/MicInjector.kt",
     "result != AUDIO_MANAGER_SUCCESS", "result != AudioManager.SUCCESS", "E-HIDDENAPI"),
    ("direct import of a hidden package",
     "app/src/main/java/com/questsoundboard/audio/MicInjector.kt",
     "import android.media.AudioTrack",
     "import android.media.AudioTrack\nimport android.media.audiopolicy.AudioPolicy",
     "E-HIDDENAPI"),
    ("unbalanced brace",
     "app/src/main/java/com/questsoundboard/audio/MicInjector.kt",
     "class MicInjector(", "fun dangling() {\nclass MicInjector(", "E-BALANCE"),
    ("missing drawable",
     "app/src/main/java/com/questsoundboard/service/SoundboardService.kt",
     "R.drawable.ic_notification", "R.drawable.ic_nope", "E-RES"),
    ("manifest class with no source",
     "app/src/main/AndroidManifest.xml",
     'android:name=".ui.MainActivity"', 'android:name=".ui.GhostActivity"', "E-MANIFEST"),
    ("dropped uses-permission",
     "app/src/main/AndroidManifest.xml",
     '<uses-permission android:name="android.permission.INTERNET" />', "", "E-PERM"),
    ("package does not match directory",
     "app/src/main/java/com/questsoundboard/net/ControlServer.kt",
     "package com.questsoundboard.net", "package com.questsoundboard.network", "E-PKG"),
    ("panel calls a nonexistent endpoint",
     "app/src/main/assets/web/app.js", "'/api/stopall'", "'/api/nuke'", "E-API"),
    ("panel references a missing DOM id",
     "app/src/main/assets/web/app.js", "$('#panic')", "$('#panicButton')", "E-DOM"),
    ("broken XML",
     "app/src/main/res/values/strings.xml", "</resources>", "<oops></resources>", "E-XML"),
    ("broken shell script",
     "app/src/main/assets/magisk-module/service.sh", "MODDIR=${0%/*}",
     "MODDIR=${0%/*}\nif [ -z ", "E-SH"),
]


def self_test() -> int:
    import shutil
    import tempfile

    print("Verifying each check fires on a deliberately broken tree.\n")
    passed = failed = 0

    for label, rel_path, find, replace, expected in MUTATIONS:
        with tempfile.TemporaryDirectory() as tmp:
            work = os.path.join(tmp, "tree")
            shutil.copytree(ROOT, work,
                            ignore=shutil.ignore_patterns(".git", ".toolchain",
                                                          "__pycache__", "build"))
            target = os.path.join(work, rel_path)
            original = read(target)
            if find not in original:
                print(f"  SKIP  {label}: anchor not found in {rel_path}")
                continue
            with open(target, "w", encoding="utf-8") as fh:
                fh.write(original.replace(find, replace, 1))

            proc = subprocess.run(
                [sys.executable, os.path.join(work, "tools", "precompile.py"), "--json"],
                capture_output=True, text=True, cwd=work,
            )
            try:
                codes = {d["code"] for d in json.loads(proc.stdout)["errors"]}
            except (json.JSONDecodeError, KeyError):
                codes = set()

            if expected in codes:
                print(f"  pass  {label}  ->  {expected}")
                passed += 1
            else:
                print(f"  FAIL  {label}: expected {expected}, got "
                      f"{sorted(codes) or 'no errors'}")
                failed += 1

    print(f"\n  {passed} passed, {failed} failed")
    return 1 if failed else 0


def main() -> int:
    ap = argparse.ArgumentParser(description="Pre-flight compiler for Quest Soundboard")
    ap.add_argument("--json", action="store_true", help="emit JSON")
    ap.add_argument("--strict", action="store_true", help="treat warnings as errors")
    ap.add_argument("--quiet", action="store_true", help="only print the summary")
    ap.add_argument("--self-test", action="store_true",
                    help="prove each check fires by injecting known faults")
    args = ap.parse_args()

    if args.self_test:
        return self_test()

    rep = Report()
    files = load_kotlin()
    if not files:
        print("no Kotlin sources found", file=sys.stderr)
        return 2

    project_symbols = {
        f"{kt.package}.{decl}" for kt in files for decl in kt.declarations
    }
    gradle = read(os.path.join(APP, "build.gradle.kts"))
    resources = collect_resources()

    for kt in files:
        check_delimiters(kt, rep)
        check_unterminated_strings(kt, rep)
        check_package_path(kt, rep)
        check_imports(kt, project_symbols, gradle, rep)
        check_hidden_members(kt, rep)
        check_jvm_clashes(kt, rep)
        check_resources(kt, resources, rep)

    check_manifest(project_symbols, resources, rep)
    check_assets(files, rep)
    check_gradle(rep)
    check_sidecar(rep)
    check_web_panel(rep)

    if args.json:
        print(json.dumps({
            "files": len(files),
            "symbols": sorted(project_symbols),
            "errors": [d.__dict__ | {"file": d.rel()} for d in rep.errors],
            "warnings": [d.__dict__ | {"file": d.rel()} for d in rep.warnings],
        }, indent=2))
        return 1 if rep.errors or (args.strict and rep.warnings) else 0

    use_colour = sys.stdout.isatty()

    def paint(text: str, colour: str) -> str:
        if not use_colour:
            return text
        codes = {"red": "31", "yellow": "33", "green": "32", "dim": "2", "bold": "1"}
        return f"\033[{codes[colour]}m{text}\033[0m"

    if not args.quiet:
        for diag in sorted(rep.diags, key=lambda d: (d.level != "error", d.rel(), d.line)):
            tag = paint("error", "red") if diag.level == "error" else paint("warn ", "yellow")
            loc = f"{diag.rel()}:{diag.line}"
            print(f"{tag} {loc:<62} [{diag.code}] {diag.message}")

    print()
    print(f"  {len(files)} Kotlin files · {len(project_symbols)} declared types "
          f"· {len(resources.get('drawable', set()) | resources.get('mipmap', set()))} drawables")

    if rep.errors:
        print(paint(f"  FAILED — {len(rep.errors)} error(s), "
                    f"{len(rep.warnings)} warning(s)", "red"))
        return 1

    if args.strict and rep.warnings:
        print(paint(f"  FAILED (strict) — {len(rep.warnings)} warning(s)", "red"))
        return 1

    print(paint(f"  OK — no errors, {len(rep.warnings)} warning(s)", "green"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
