## Build the UI test
## TODO: make destination generic for iOS 15 simulator
xcodebuild -project ./maestro-driver-ios/maestro-driver-ios.xcodeproj \
  -scheme maestro-driver-ios -sdk iphonesimulator \
  -destination "platform=iOS Simulator,name=New,OS=15.5" \
  -IDEBuildLocationStyle=Custom \
  -IDECustomBuildLocationType=Absolute \
  -IDECustomBuildProductsPath="$PWD/build/Products" \
  build-for-testing

## Remove intermediates, output and copy runner in client
mv "$PWD"/build/Products/Debug-iphonesimulator/maestro-driver-iosUITests-Runner.app ./maestro-client/src/main/resources/maestro-driver-iosUITests-Runner.app

mv "$PWD"/build/Products/Debug-iphonesimulator/maestro-driver-ios.app ./maestro-client/src/main/resources/maestro-driver-ios.app

mv "$PWD"/build/Products/*.xctestrun ./maestro-client/src/main/resources/maestro-driver-ios-config.xctestrun

(cd ./maestro-client/src/main/resources && zip -r maestro-driver-iosUITests-Runner.zip ./maestro-driver-iosUITests-Runner.app)
(cd ./maestro-client/src/main/resources && zip -r maestro-driver-ios.zip ./maestro-driver-ios.app)
rm -r ./maestro-client/src/main/resources/*.app