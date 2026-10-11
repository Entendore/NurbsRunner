# NURBS Runner

A 3-D endless runner where the player is a **rational NURBS surface** whose
shape, color, pattern and motion are evolved by a **genetic algorithm** at
every checkpoint. The lineage persists: traits, ancestors, deaths and the
hall-of-fame genome survive runs and app restarts. 100% local — no network
in the `local` flavor.

## Build

```bash
./gradlew assembleLocalDebug      # offline edition (F-Droid compatible)
./gradlew assemblePlayRelease     # AdMob edition (replace test ad IDs first!)
./gradlew testDebugUnitTest       # JVM test suite
