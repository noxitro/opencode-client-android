#!/usr/bin/env python3
"""公開リポジトリに個人情報・秘密情報が混入していないかを機械的に検査する。

CI(.github/workflows/privacy-scan.yml)と手元の両方で同じ判定をする。
検出対象:
  - 秘密情報: APIキー/トークンの形、Basic 認証トークン(スタブ用以外)、秘密鍵
  - 個人環境: ホームディレクトリのパス(ユーザー名入り)、メールアドレス、PC のホスト名
  - ネットワーク: プライベート IPv4(エミュレータ用 10.0.2.2 と RFC 例示用を除く)、tailnet の IP、*.ts.net
  - 禁止ファイル: E2E 証跡・ログ・APK・ビルドログ(内容に関係なくコミット自体を禁止)
許可したい行は .github/privacy-allowlist.txt に正規表現で書く(1行1パターン、# はコメント)。
"""
import base64
import fnmatch
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ALLOWLIST = os.path.join(ROOT, ".github", "privacy-allowlist.txt")

FORBIDDEN_PATHS = [
    "e2e-artifacts/*", "*.apk", "*.aab", "*.log", "logcat*", "build-log.txt", "test-log.txt",
    "local.properties", ".env", ".env.*", "*.jks", "*.keystore", "*.p12", "*.pem", "*.key",
]
SKIP_PATHS = ["gradle/wrapper/gradle-wrapper.jar", ".github/privacy-allowlist.txt", "scripts/privacy_scan.py"]

PATTERNS = {
    "api-key": re.compile(r"(sk-[A-Za-z0-9_-]{16,}|sk-ant-[A-Za-z0-9_-]{8,}|AIza[0-9A-Za-z_-]{30,}|gh[pousr]_[A-Za-z0-9]{30,}"
                          r"|xox[baprs]-[A-Za-z0-9-]{10,}|AKIA[0-9A-Z]{16}|tskey-[A-Za-z0-9-]{10,}|-----BEGIN [A-Z ]*PRIVATE KEY)"),
    "home-path": re.compile(r"([A-Za-z]:\\\\?Users\\\\?[A-Za-z0-9._-]+|/Users/[A-Za-z0-9._-]+|/home/[A-Za-z0-9._-]+)"),
    "email": re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}"),
    "hostname": re.compile(r"\b(DESKTOP|LAPTOP)-[A-Z0-9]{5,}\b"),
    "tailnet": re.compile(r"\b100\.(6[4-9]|[7-9][0-9]|1[01][0-9]|12[0-7])\.[0-9]{1,3}\.[0-9]{1,3}\b|[A-Za-z0-9-]+\.[A-Za-z0-9-]+\.ts\.net"),
    "private-ip": re.compile(r"\b(10\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}|192\.168\.[0-9]{1,3}\.[0-9]{1,3}|172\.(1[6-9]|2[0-9]|3[01])\.[0-9]{1,3}\.[0-9]{1,3})\b"),
    "basic-token": re.compile(r"Basic ([A-Za-z0-9+/=]{8,})"),
}
# 常に許可する値(エミュレータのホスト、ループバック、テストで使う例示アドレス)
BUILTIN_ALLOW = [
    r"10\.0\.2\.2\b", r"127\.0\.0\.1", r"0\.0\.0\.0",
    r"100\.64\.0\.[0-9]\b",                      # CGNAT 帯の例示アドレス(テスト・ドキュメント用)
    r"/home/user\b", r"/Users/<user>", r"Users\\\\?<user>",  # プレースホルダ
    r"@users\.noreply\.github\.com", r"@example\.com", r"noreply@anthropic\.com",
    r"[A-Za-z0-9._-]+@dev\.opencode\.android",   # uiautomator の resource-id 形式
]
STUB_BASIC = {"opencode:stub-pass"}


def load_allowlist():
    pats = [re.compile(p) for p in BUILTIN_ALLOW]
    if os.path.exists(ALLOWLIST):
        for line in open(ALLOWLIST, encoding="utf-8"):
            line = line.strip()
            if line and not line.startswith("#"):
                pats.append(re.compile(line))
    return pats


def tracked_files():
    out = subprocess.run(["git", "ls-files", "-z"], cwd=ROOT, capture_output=True, check=True).stdout
    return [p.decode("utf-8", "surrogateescape") for p in out.split(b"\0") if p]


def main():
    allow = load_allowlist()
    findings = []
    for rel in tracked_files():
        if any(fnmatch.fnmatch(rel, g) or fnmatch.fnmatch(os.path.basename(rel), g) for g in FORBIDDEN_PATHS):
            findings.append((rel, 0, "forbidden-file", rel))
            continue
        if rel in SKIP_PATHS:
            continue
        try:
            data = open(os.path.join(ROOT, rel), "rb").read()
        except OSError:
            continue
        if b"\0" in data[:8000]:
            continue  # binary
        text = data.decode("utf-8", "replace")
        for ln, line in enumerate(text.splitlines(), 1):
            for kind, rx in PATTERNS.items():
                for m in rx.finditer(line):
                    hit = m.group(0)
                    if kind == "basic-token":
                        try:
                            decoded = base64.b64decode(m.group(1) + "==").decode("utf-8", "replace")
                        except Exception:
                            decoded = ""
                        if decoded in STUB_BASIC:
                            continue
                        hit = f"Basic <{len(m.group(1))} chars>"
                    if any(a.search(hit) or a.search(line) for a in allow):
                        continue
                    findings.append((rel, ln, kind, hit[:80]))
    if findings:
        print(f"PRIVACY SCAN FAILED: {len(findings)} finding(s)")
        for rel, ln, kind, hit in findings:
            print(f"  {rel}:{ln}: [{kind}] {hit}")
        print("\n許可してよい値なら .github/privacy-allowlist.txt に正規表現で追加する。")
        return 1
    print("privacy scan: clean")
    return 0


if __name__ == "__main__":
    sys.exit(main())
