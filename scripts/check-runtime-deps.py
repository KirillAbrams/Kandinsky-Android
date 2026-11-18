#!/usr/bin/env python3
# Сверяет библиотеки, которые реально попали в мобильную сборку.
# Проверка ловит проверочные зависимости в Андроиде и неожиданные библиотеки в архиве айфона.

from pathlib import Path
import argparse, re, subprocess, json

p = argparse.ArgumentParser()
p.add_argument("--android-report")
p.add_argument("--ios-app")
a = p.parse_args()
if not (a.android_report or a.ios_app):
    p.error("Provide --android-report or --ios-app")
result = {}
if a.android_report:
    text = Path(a.android_report).read_text()
    assert "BUILD SUCCESSFUL" in text
    modules = set(re.findall(r"[\w.-]+:[\w.-]+:[\w.+-]+", text))
    packages = {module.rsplit(":", 1)[0] for module in modules}
    required = {"androidx.core:core", "com.squareup.okhttp3:okhttp", "org.jetbrains.kotlin:kotlin-stdlib"}
    assert required <= packages, f"В отчёте сборки отсутствуют {sorted(required - packages)}"
    test_packages = {p for p in packages if p.startswith(("androidx.test:", "androidx.test.", "junit:", "org.hamcrest:"))}
    assert not test_packages, f"В приложение попали библиотеки проверок: {sorted(test_packages)}"
    result["android_runtime_modules"] = sorted(modules)
if a.ios_app:
    app = Path(a.ios_app)
    binary = app / app.stem
    links = subprocess.check_output(["otool", "-L", str(binary)], text=True)
    libraries = [line.strip().split(" (", 1)[0] for line in links.splitlines()[1:]]
    assert libraries and all(
        x.startswith(("/System/Library/", "/usr/lib/", "@rpath/libswift", "@rpath/Kandinsky"))
        for x in libraries
    ), libraries
    bundled = (
        [x.name for x in (app / "Frameworks").glob("*")] if (app / "Frameworks").exists() else []
    )
    assert all(x.startswith("libswift") for x in bundled), bundled
    result["ios_linked_libraries"] = libraries
    result["ios_bundled_frameworks"] = bundled
print(json.dumps(result, indent=2))
