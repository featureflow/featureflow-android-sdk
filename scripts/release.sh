#!/usr/bin/env bash
set -uxe
VERSION=$1
echo "Release $VERSION"

sed -i.bak "s/version[ ]*=.*$/version = '${VERSION}'/" featureflow-android-sdk/build.gradle
rm -f featureflow-android-sdk/build.gradle.bak

./gradlew test sourcesJar javadocJar packageRelease
./gradlew uploadArchives closeAndReleaseRepository
./gradlew publishGhPages
echo "Release complete.."
