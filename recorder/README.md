# :recorder (S5)

Android library build of the ABP Call recorder. **No sources live here**: `recorder/build.gradle.kts` compiles
`app/src/main` (owned by S1). `src` and `schemas` are symlinks to `../app/src` and `../app/schemas` only so that
upstream unit tests, which read `src/main/...` and `schemas/...` relative to the module, keep working.
