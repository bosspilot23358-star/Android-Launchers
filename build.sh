#!/bin/sh
# PlayHack5: сборка APK без Android Studio и Gradle. Нужен только JDK 17.
cd "$(dirname "$0")" || exit 1
if ! command -v java >/dev/null 2>&1; then
  echo "Java не найдена. Установите JDK 17 (например: sudo apt install openjdk-17-jdk)"
  exit 1
fi
exec java Build.java "$@"
