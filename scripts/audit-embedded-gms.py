#!/usr/bin/env python3
"""Inspect APK integration boundaries; this is NOT a functional migration test.

No network, credentials, APK changes or release changes are made. Example:
  python3 scripts/audit-embedded-gms.py --microg /path/to/gmscore.apk \
      --aapt2 /path/to/build-tools/aapt2 --output build/gms-integration-audit.json

An optional --candidate reports residual external endpoints and missing Binder
descriptor literals. Presence/absence of a string cannot establish correct IPC,
authentication, account migration, playback or push delivery; test those on-device.
"""
from __future__ import annotations

import argparse
import ast
from collections import Counter
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import struct
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
DEX_NAME = re.compile(r"classes\d*\.dex\Z")
BINDER_NAME = re.compile(r"(?:com\.google\.|org\.microg\.)[\w.$]*\.I[A-Z][\w$]*\Z")
ACTION_END = re.compile(r"(?:\.START|\.BIND|\.BIND_TO_SERVICE|\.GET_GLS|\.REGISTER|\.REGISTRATION|\.RECEIVE|\.TOKEN_REQUEST|\.GetToken|\.login)\Z")


def sha256(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def dex_strings(data: bytes) -> set[str]:
    if len(data) < 112 or not data.startswith(b"dex\n"):
        raise ValueError("Expected standard DEX bytecode")
    count, offset = struct.unpack_from("<II", data, 0x38)
    if offset + count * 4 > len(data):
        raise ValueError("DEX string table extends beyond the file")
    result = set()
    for index in range(count):
        start = struct.unpack_from("<I", data, offset + index * 4)[0]
        # Skip the ULEB128 UTF-16 length, not characters in the string.
        for _ in range(5):
            if start >= len(data):
                raise ValueError("Truncated DEX string length")
            value = data[start]
            start += 1
            if not value & 128:
                break
        else:
            raise ValueError("Invalid DEX string length")
        end = data.find(b"\0", start)
        if end < 0:
            raise ValueError("Unterminated DEX string")
        # Endpoints and descriptors are ASCII. Unrelated MUTF-8 strings are not
        # used as an identity or proof of executable behavior in this audit.
        result.add(data[start:end].decode("utf-8", errors="replace"))
    return result


def class_reader():
    spec = importlib.util.spec_from_file_location("calma_extension_audit", ROOT / "scripts/compile-extension.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.dex_classes


def apk_dex(apk: Path) -> dict:
    classes, strings, duplicates = set(), set(), set()
    read_classes = class_reader()
    entries = []
    with zipfile.ZipFile(apk) as archive:
        for name in sorted(archive.namelist()):
            if not DEX_NAME.fullmatch(name):
                continue
            data = archive.read(name)
            strings.update(dex_strings(data))
            defined = read_classes(data)
            duplicates.update(classes & defined)
            classes.update(defined)
            entries.append({"file": name, "classCount": len(defined)})
    if not entries:
        raise ValueError(f"No standard classes*.dex entries in {apk}")
    return {"classes": classes, "strings": strings, "entries": entries, "duplicateDefinitions": sorted(duplicates)}


def parse_xmltree(text: str) -> dict:
    """Read aapt2's structural tree without conflating sibling components."""
    root = {"name": "document", "attributes": {}, "children": []}
    stack = [(-1, root)]
    for line in text.splitlines():
        element = re.match(r"(\s*)E: ([^\s(]+)", line)
        if element:
            indent = len(element[1])
            while stack[-1][0] >= indent:
                stack.pop()
            node = {"name": element[2], "attributes": {}, "children": []}
            stack[-1][1]["children"].append(node)
            stack.append((indent, node))
        elif re.match(r"\s*A: ", line):
            key, value = line.split("A: ", 1)[1].split("=", 1)
            key = re.sub(r"\(0x[0-9a-fA-F]+\)$", "", key).rsplit(":", 1)[-1]
            quoted = re.match(r'"(.*?)"(?: \(Raw: .*)?$', value)
            stack[-1][1]["attributes"][key] = quoted[1] if quoted else value
    return root


def descendants(node: dict, name: str):
    for child in node["children"]:
        if child["name"] == name:
            yield child
        yield from descendants(child, name)


def manifest_summary(badging: str, xmltree: str) -> dict:
    identity = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'", badging)
    target = re.search(r"targetSdkVersion:'(\d+)'", badging)
    minimum = re.search(r"sdkVersion:'(\d+)'|minSdkVersion:'(\d+)'", badging)
    if not identity or not target:
        raise ValueError("Could not read APK package, version and target SDK")
    tree = parse_xmltree(xmltree)
    app = next(descendants(tree, "application"), None)
    if app is None:
        raise ValueError("APK manifest has no application")
    components = []
    for child in app["children"]:
        if child["name"] in {"activity", "activity-alias", "service", "provider", "receiver"}:
            components.append({"kind": child["name"], **child["attributes"],
                               "actions": [item["attributes"].get("name") for item in descendants(child, "action")],
                               "metadata": [item["attributes"] for item in descendants(child, "meta-data")]})
    return {"package": identity[1], "versionCode": int(identity[2]), "versionName": identity[3],
            "targetSdk": int(target[1]), "minSdk": int(minimum[1] or minimum[2]) if minimum else None,
            "application": app["attributes"], "components": components,
            "metadata": [item["attributes"] for item in app["children"] if item["name"] == "meta-data"],
            "packageQueries": [item["attributes"].get("name") for query in descendants(tree, "queries") for item in descendants(query, "package")]}


def endpoints(strings: set[str], gms_package: str, account_type: str) -> dict:
    gsf_prefix = gms_package.removesuffix(".gms") + ".gsf"
    external = lambda s: s == gms_package or s.startswith(gms_package + ".") or s.startswith(gsf_prefix + ".")
    android_prefix = gms_package.removesuffix(".gms")
    permissions = (android_prefix + ".providers.gsf.permission.", android_prefix + ".c2dm.permission.")
    return {
        "externalPackageLiterals": sorted(s for s in strings if s == gms_package),
        "externalAccountTypeLiterals": sorted(s for s in strings if s == account_type),
        "externalAuthorityAndPermissionLiterals": sorted(s for s in strings if (external(s) and s != gms_package) or s.startswith(permissions)),
        "externalProviderUris": sorted(s for s in strings if s.startswith("content://") and external(s.removeprefix("content://").split("/", 1)[0])),
        "protocolServiceActionsAndComponentNames": sorted(s for s in strings if s.startswith("com.google.") and ACTION_END.search(s)),
        "binderDescriptorLiterals": sorted(s for s in strings if BINDER_NAME.fullmatch(s)),
    }


def inspect_apk(apk: Path, aapt2: Path, gms_package: str, account_type: str) -> tuple[dict, dict]:
    def dump(*args):
        return subprocess.check_output([str(aapt2), "dump", *args], text=True)
    manifest = manifest_summary(dump("badging", str(apk)), dump("xmltree", str(apk), "--file", "AndroidManifest.xml"))
    dex = apk_dex(apk)
    return {"file": str(apk), "sha256": sha256(apk), "sizeBytes": apk.stat().st_size,
            "manifest": manifest, "dex": {"entries": dex["entries"], "classCount": len(dex["classes"]), "duplicateDefinitions": dex["duplicateDefinitions"]},
            "literalReferences": endpoints(dex["strings"], gms_package, account_type)}, dex


def ci_dependency_evidence(path: Path, gms_package: str) -> dict:
    """Find the concrete download -> APK variable -> adb install path using AST.

    This describes the inspected runner, not every possible workflow or shell.
    Non-matching syntax is reported as unproven, never proof of independence.
    """
    if not path.is_file():
        return {"file": str(path), "analyzed": False, "provesIndependence": False}
    source = path.read_text()
    tree = ast.parse(source)
    dependency_vars = set()
    evidence = []
    for node in ast.walk(tree):
        if not isinstance(node, (ast.With, ast.AsyncWith)):
            continue
        urls = [n.value for n in ast.walk(node) if isinstance(n, ast.Constant) and isinstance(n.value, str)
                and n.value.startswith("https://github.com/") and ("/gmscore/" in n.value.lower() or gms_package in n.value)]
        if not urls:
            continue
        for item in node.items:
            call = item.context_expr
            if isinstance(call, ast.Call) and isinstance(call.func, ast.Attribute) and call.func.attr == "open" and isinstance(call.func.value, ast.Name):
                dependency_vars.add(call.func.value.id)
        evidence.extend({"kind": "dependencyDownload", "line": node.lineno, "url": url} for url in urls)
    installs = []
    for node in ast.walk(tree):
        if not isinstance(node, ast.Call) or not isinstance(node.func, ast.Name) or node.func.id != "adb":
            continue
        literal_args = [arg.value for arg in node.args if isinstance(arg, ast.Constant) and isinstance(arg.value, str)]
        variables = [arg.id for arg in node.args if isinstance(arg, ast.Name)]
        if "install" in literal_args and dependency_vars.intersection(variables):
            installs.append({"kind": "externalDependencyInstall", "line": node.lineno, "source": ast.get_source_segment(source, node)})
    evidence.extend(installs)
    return {"file": str(path), "sha256": sha256(path), "analyzed": True,
            "installsExternalGmsCore": bool(installs), "provesIndependence": False, "evidence": evidence,
            "limitation": "Static runner inspection. An external install masks dependency; its absence alone proves no runtime behavior."}


def compare_binder_descriptors(original: dict, candidate: dict) -> dict:
    before = set(original["binderDescriptorLiterals"])
    after = set(candidate["binderDescriptorLiterals"])
    return {"missingOriginalLiterals": sorted(before - after), "allOriginalLiteralsPresent": before <= after,
            "runtimeCompatibilityVerified": False,
            "rule": "Preserve wire-level Binder descriptors; relocate implementation classes separately. Literal presence does not prove parcel compatibility."}


def build_report(host: Path, microg: Path, aapt2: Path, gms_package: str, account_type: str,
                 runner: Path, candidate: Path | None = None) -> dict:
    host_info, host_dex = inspect_apk(host, aapt2, gms_package, account_type)
    microg_info, microg_dex = inspect_apk(microg, aapt2, gms_package, account_type)
    duplicate_classes = sorted(host_dex["classes"] & microg_dex["classes"])
    host_package = host_info["manifest"]["package"]
    services = microg_info["manifest"]["components"]
    target_sdk = host_info["manifest"]["targetSdk"]
    foreground_review = [item for item in services if item["kind"] == "service" and
                         item.get("name", "").endswith((".McsService", ".CheckinService")) and not item.get("foregroundServiceType")]
    report = {"schemaVersion": 1, "auditKind": "static-apk-integration-boundaries",
              "functionalMigrationVerified": False, "networkUsed": False,
              "host": host_info, "gmsCore": microg_info,
              "definedClassCollisions": {"count": len(duplicate_classes), "classes": duplicate_classes,
                                          "namespaceCounts": dict(sorted(Counter("/".join(s.split("/")[:3]) for s in duplicate_classes).items()))},
              "ipc": {"sharedBinderDescriptorLiterals": sorted(set(host_info["literalReferences"]["binderDescriptorLiterals"]) & set(microg_info["literalReferences"]["binderDescriptorLiterals"])),
                      "rule": "Keep Binder interface names and protocol actions unchanged. Explicit component class names require coordinated rewrites when implementations move."},
              "sameInstalledPackageReview": {
                  "installedHostPackage": host_package, "externalGmsPackage": gms_package,
                  "currentPackagesDiffer": host_package != microg_info["manifest"]["package"],
                  "externalAccountType": account_type,
                  "requiredChecks": [
                      "Package-routing rewrites must route bindService/createPackageContext/provider lookups to the host without treating every host call as privileged GMS.",
                      "Client GetServiceRequest/package metadata and backend calling-package checks must still identify the real calling UID/package.",
                      "Use unique provider authorities and account type so existing external GmsCore can remain installed.",
                      "Existing external-authenticator credentials are not copied by merging code; fresh sign-in or an explicitly supported export flow is required.",
                      "Keep the host Application lifecycle; initialize embedded services without replacing YouTube application initialization.",
                      "Cross-classloader Binder calls and Bundle parcelables need actual round-trip tests, even when there are no merged class definitions."
                  ]},
              "foregroundServiceReview": {"hostTargetSdk": target_sdk, "gmsCoreTargetSdk": microg_info["manifest"]["targetSdk"],
                                          "servicesWithoutDeclaredType": foreground_review,
                                          "requiresModernTargetReview": target_sdk >= 34 and bool(foreground_review)},
              "runtimeRunner": ci_dependency_evidence(runner, gms_package),
              "requiredRuntimeEvidence": ["Clean installation without external GmsCore", "Authenticator/service/provider ownership and Binder round trips", "Real sign-in and authenticated playback", "Notifications/background restart", "Upgrade and coexistence with external GmsCore"]}
    lock = ROOT / "base.lock.json"
    if lock.is_file():
        expected = json.loads(lock.read_text())["sha256"]
        report["host"]["baseLock"] = {"expectedSha256": expected, "matches": expected == host_info["sha256"]}
    if candidate:
        info, _ = inspect_apk(candidate, aapt2, gms_package, account_type)
        report["candidate"] = info
        report["candidateIpc"] = compare_binder_descriptors(host_info["literalReferences"], info["literalReferences"])
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--host", type=Path, default=ROOT / "base/youtube-original.apk")
    parser.add_argument("--microg", type=Path, required=True, help="Local official/source-built GmsCore APK; this command does not download it")
    parser.add_argument("--candidate", type=Path)
    parser.add_argument("--aapt2", type=Path, default=ROOT / "tools/android-15/aapt2")
    parser.add_argument("--gms-package", default="app.revanced.android.gms")
    parser.add_argument("--account-type", default="app.revanced")
    parser.add_argument("--runtime-runner", type=Path, default=ROOT / "scripts/runtime-check.py")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    try:
        report = build_report(args.host, args.microg, args.aapt2, args.gms_package, args.account_type, args.runtime_runner, args.candidate)
    except (OSError, ValueError, KeyError, struct.error, zipfile.BadZipFile, subprocess.CalledProcessError) as error:
        print(f"Audit failed: {error}", file=sys.stderr)
        return 1
    output = json.dumps(report, indent=2, ensure_ascii=False) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(output)
        print(f"Static audit written to {args.output}; this does not verify a functional migration.")
    else:
        print(output, end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
