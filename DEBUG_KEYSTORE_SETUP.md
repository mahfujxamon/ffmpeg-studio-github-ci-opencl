# Debug keystore CI fix

The app's `debug` build uses `rootDir/debug.keystore` with the standard Android debug credentials:

- store password: `android`
- alias: `androiddebugkey`
- key password: `android`

GitHub Actions runs on a fresh runner, so v10 generates this keystore before `assembleDebug` with `keytool`. No keystore needs to be committed to the repository.
