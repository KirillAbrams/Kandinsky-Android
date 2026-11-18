#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."

fail() {
    printf '%s\n' "$*" >&2
    exit 1
}

if [[ $(uname -s) == Darwin ]]; then
    default_sdk="$HOME/Library/Android/sdk"
    JAVA_HOME=$(/usr/libexec/java_home -v 17 2>/dev/null) || fail 'Установи JDK 17.'
    export JAVA_HOME
    export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:+$JAVA_TOOL_OPTIONS }-Djava.net.preferIPv4Stack=true"
else
    default_sdk="$HOME/Android/Sdk"
fi
if [[ -n "${JAVA_HOME:-}" ]]; then
    java="$JAVA_HOME/bin/java"
else
    java=java
fi
if ! "$java" -version 2>&1 | grep -q 'version "17[."]'; then
    fail 'Для сборки нужен JDK 17.'
fi

export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$default_sdk}}"
sdk="$ANDROID_HOME"
cmdline=
for candidate in "$sdk/cmdline-tools/latest" "$sdk"/cmdline-tools/*; do
    if [[ -x "$candidate/bin/sdkmanager" ]]; then
        cmdline="$candidate/bin"
        break
    fi
done
[[ -n "$cmdline" ]] || fail 'Добавь Android SDK Command-line Tools через SDK Manager.'

case $(uname -m) in
    arm64|aarch64) abi=arm64-v8a; cpu=arm64 ;;
    x86_64) abi=x86_64; cpu=x86_64 ;;
    *) fail 'Поддерживаются Apple Silicon и компьютеры x86-64.' ;;
esac
image="system-images;android-37.0;google_apis_playstore;$abi"
packages=()
[[ -x "$sdk/platform-tools/adb" ]] || packages+=(platform-tools)
[[ -x "$sdk/emulator/emulator" ]] || packages+=(emulator)
[[ -f "$sdk/platforms/android-37.0/android.jar" ]] || packages+=('platforms;android-37.0')
[[ -x "$sdk/build-tools/36.1.0/aapt2" ]] || packages+=('build-tools;36.1.0')
[[ -f "$sdk/system-images/android-37.0/google_apis_playstore/$abi/system.img" ]] ||
    packages+=("$image")
if (( ${#packages[@]} > 0 )); then
    "$cmdline/sdkmanager" --sdk_root="$sdk" --install "${packages[@]}"
fi

adb="$sdk/platform-tools/adb"
emulator="$sdk/emulator/emulator"
state="$PWD/.review"
mkdir -p "$state/avd"
export ANDROID_AVD_HOME="$state/avd"
name_file="$state/device-name"
if [[ ! -s "$name_file" ]]; then
    printf 'Kandinsky_Review_Pixel_7_%s%s\n' "$RANDOM" "$RANDOM" > "$name_file"
fi
name=$(cat "$name_file")
if [[ ! -f "$ANDROID_AVD_HOME/$name.ini" ]]; then
    # Сохраняем проверенный профиль Pixel 7 в папке этой копии проекта.
    profile="$ANDROID_AVD_HOME/$name.avd"
    mkdir -p "$profile"
    printf 'avd.ini.encoding=UTF-8\npath=%s\ntarget=android-37.0\n' "$profile" \
        > "$ANDROID_AVD_HOME/$name.ini"
    cat > "$profile/config.ini" <<PROFILE
AvdId=$name
PlayStore.enabled=true
abi.type=$abi
avd.ini.displayname=Kandinsky Interview Review
avd.ini.encoding=UTF-8
disk.dataPartition.size=6G
fastboot.chosenSnapshotFile=
fastboot.forceChosenSnapshotBoot=no
fastboot.forceColdBoot=no
fastboot.forceFastBoot=yes
hw.accelerometer=yes
hw.arc=false
hw.audioInput=no
hw.audioOutput=no
hw.battery=yes
hw.camera.back=virtualscene
hw.camera.front=emulated
hw.cpu.arch=$cpu
hw.cpu.ncore=4
hw.dPad=no
hw.device.hash2=MD5:2016577e1656e8e7c2adb0fac972beea
hw.device.manufacturer=Google
hw.device.name=pixel_7
hw.gps=yes
hw.gpu.enabled=yes
hw.gpu.mode=host
hw.gyroscope=yes
hw.initialOrientation=portrait
hw.keyboard=yes
hw.lcd.density=420
hw.lcd.height=2400
hw.lcd.width=1080
hw.mainKeys=no
hw.ramSize=2048
hw.sdCard=yes
hw.sensors.light=yes
hw.sensors.magnetic_field=yes
hw.sensors.orientation=yes
hw.sensors.pressure=yes
hw.sensors.proximity=yes
hw.trackBall=no
image.sysdir.1=system-images/android-37.0/google_apis_playstore/$abi/
runtime.network.latency=none
runtime.network.speed=full
sdcard.size=512M
showDeviceFrame=yes
skin.dynamic=yes
skin.name=pixel_7
skin.path=$sdk/skins/pixel_7
tag.display=Google Play
tag.displaynames=Google Play
tag.id=google_apis_playstore
tag.ids=google_apis_playstore
target=android-37.0
vm.heapSize=228
PROFILE
fi

avd_name() {
    "$adb" -s "$1" emu avd name 2>/dev/null | tr -d '\r' | sed -n '1p'
}

serial=
for online in $("$adb" devices | awk '/^emulator-/ && $2 == "device" {print $1}'); do
    if [[ $(avd_name "$online") == "$name" ]]; then
        serial="$online"
        break
    fi
done
./android/gradlew -p android :app:assembleRelease

open_when_ready() {
    local deadline=$((SECONDS + 180))
    until [[ $(avd_name "$serial") == "$name" ]] &&
        [[ $("$adb" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r') == 1 ]]; do
        (( SECONDS < deadline )) || fail 'Не дождался запуска Android Emulator.'
        sleep 2
    done
    "$adb" -s "$serial" install -r android/app/build/outputs/apk/release/app-release.apk
    "$adb" -s "$serial" shell am start -W -n \
        dev.kirill.kandinsky.native/dev.kirill.kandinsky.chat.app.MainActivity
}

if [[ -n "$serial" ]]; then
    open_when_ready
    exit
fi

ports=$("$adb" devices)
for port in $(seq 5556 2 5584); do
    if [[ "$ports" != *"emulator-$port"* ]] &&
        ! lsof -nP -iTCP:"$port" -iTCP:"$((port + 1))" -sTCP:LISTEN >/dev/null 2>&1; then
        serial="emulator-$port"
        break
    fi
done
[[ -n "$serial" ]] || fail 'Нет свободного порта для эмулятора Android.'

open_when_ready &
exec "$emulator" -avd "$name" -port "$port" -no-snapshot -no-boot-anim
