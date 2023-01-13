# Maestro samples

`maestro download-samples` provides a set of flows and apps so that users can quickly set up a maestro test, without having to create an app.

download-samples downloads these files and apps from storage.googleapis.com.

# Update the samples

Although the samples are checked in, updating them requires a few manual steps:

* Change the samples in this directory and merge these changes
* Run `maestro download-samples`
* Copy *.yaml to the samples directory created by download-samples
* Run `(cd samples && zip -r "$OLDPWD/samples.zip" . -x "/**/.*" -x "__MACOSX")`
* Open https://console.cloud.google.com/storage/browser/mobile.dev/samples
* Upload samples.zip
* Adjust the permissions of samples.zip to "Public to Internet"
* Run `maestro download-samples` and verify that the change was successful
