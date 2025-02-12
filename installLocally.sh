#!/bin/sh

./gradlew :maestro-cli:installDist

rm -rf ~/.maestro/bin
rm -rf ~/.maestro/lib

cp -r ./maestro-cli/build/install/maestro/bin ~/.maestro/bin
cp -r ./maestro-cli/build/install/maestro/lib ~/.maestro/lib