# EchoVR Installer Changes

This file documents all the changes made to the EchoVR Installer application during the current chat session.

## Changes Made

### Theme and UI
- Forced the application to always use a dark theme to ensure button color consistency between light and dark modes. This was done by changing the parent theme in `app/src/main/res/values/themes.xml` and `app/src/main/res/values-night/themes.xml` to `Theme.MaterialComponents.NoActionBar`.
- Made the Discord link in the "New Player" and "Better Graphics" popups more prominent by changing the clickable `TextView` to a `Button`. This was done in `MainActivity.java`.

### Version Checker
- Added logging to the `isNewVersion` method in `InstallerManager.java` to help diagnose an issue where the app always reports an update is available. The logs will show the current and remote versions being compared.
- The `checkForUpdates` method in `InstallerManager.java` was already correctly implemented to fetch the latest release from GitHub and compare versions. No changes were made to the logic itself.
