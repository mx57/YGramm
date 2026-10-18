#!/usr/bin/env bash
# Скрипт автоматической сборки основного APK (Узел #1) и Клона APK (Узел #2) в корень репозитория
set -euo pipefail

if [ -f debug.keystore.base64 ] && [ ! -f debug.keystore ]; then
  base64 -d debug.keystore.base64 > debug.keystore
fi

echo "[1/2] Сборка клона YggGram-clone-node2.apk (applicationId: com.aistudio.ygggram.vxkprm.node2)..."
sed -i 's/applicationId = "com.aistudio.ygggram.vxkprm"/applicationId = "com.aistudio.ygggram.vxkprm.node2"/' app/build.gradle.kts
sed -i 's/<string name="app_name">YggGram<\/string>/<string name="app_name">YggGram Клон #2<\/string>/' app/src/main/res/values/strings.xml
./gradlew :app:assembleDebug
cp app/build/outputs/apk/debug/app-debug.apk YggGram-clone-node2.apk
mkdir -p apk-releases
cp YggGram-clone-node2.apk apk-releases/YggGram-clone-node2.apk

echo "[2/2] Восстановление конфигурации и сборка основного YggGram-main.apk (applicationId: com.aistudio.ygggram.vxkprm)..."
sed -i 's/applicationId = "com.aistudio.ygggram.vxkprm.node2"/applicationId = "com.aistudio.ygggram.vxkprm"/' app/build.gradle.kts
sed -i 's/<string name="app_name">YggGram Клон #2<\/string>/<string name="app_name">YggGram<\/string>/' app/src/main/res/values/strings.xml
./gradlew :app:assembleDebug
cp app/build/outputs/apk/debug/app-debug.apk YggGram-main.apk
cp YggGram-main.apk apk-releases/YggGram-main.apk

echo "Готово! Файлы в корне проекта:"
ls -lh YggGram-main.apk YggGram-clone-node2.apk apk-releases/
