#Changelog

## 1.0.0 - 2022-07-05

* Initial release.

## 1.0.1 - 2022-07-12

* Publish conductor with Java 8 instead of Java 11 
* Adding capability to configure port number for communicating conductor

## 1.1.0 - 2022-07-13

* Wait until view is not obscured by any other views before tapping on it
* Adding tap on point command
* Adjusted hierarchy mapping and timeouts to account for Flutter apps

### 1.1.1 - 2022-07-14

* Removed Uiautomator delays
* Removed InstrumentationThread from AndroidDriver
* Reporting view hierarchy alongside NotFound error

### 1.2.0 - 2022-07-18

* Added swipe command

### 1.3.0 - 2022-07-19

* Added `waitUntilVisible` flag for tap commands
* Added `containsChild` predicate to element selectors