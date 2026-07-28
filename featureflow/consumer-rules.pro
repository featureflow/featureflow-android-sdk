# The SDK reflects over nothing and generates no code, so consumers need no keep rules for it.
# This exists so R8 does not warn on the optional lifecycle integration when an app excludes it.
-dontwarn androidx.lifecycle.**
