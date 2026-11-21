# Suwayomi converts the APK with dex2jar, which cannot rebuild some types that R8's optimizer leaves implicit
# (java.lang.VerifyError in keiyoushi.source.Generated). Keep shrinking and obfuscation, skip the optimizer.
-dontoptimize
