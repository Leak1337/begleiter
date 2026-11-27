# Licenses and third-party code

Begleiter's independently authored code is available under the Apache License 2.0 in `LICENSE`.
The third-party components below have their own license; see the component notices below for terms.
Both licenses are listed in `fabric.mod.json` to describe the contents, not as alternative
licenses for the Baritone component.

## Baritone world-border helper

- Project: https://github.com/cabaletta/baritone
- Authors: the Baritone contributors; upstream project and license notice retained.
- Upstream revision: `c55a9c5d9a333c9f0336179a96c13a178fcbc2d4`.
- Original source: https://github.com/cabaletta/baritone/blob/c55a9c5d9a333c9f0336179a96c13a178fcbc2d4/src/main/java/baritone/utils/pathing/BetterWorldBorder.java
- Adapted file: `src/main/java/dev/begleiter/thirdparty/baritone/BetterWorldBorder.java`.
- License: **GNU Lesser General Public License, version 3 or any later version**.
  The full texts are in `LICENSES/LGPL-3.0-or-later.txt` and `LICENSES/GPL-3.0-or-later.txt`.

Modified by Leak1337 for Begleiter on 2026-11-02: moved the package, made the class
final and replaced the Minecraft `WorldBorder` constructor parameter with four
numeric bounds. The overlap and placement checks are retained from Baritone.
This library has no Minecraft or Begleiter dependencies. Our navigation context
supplies its bounds; movement planning and execution use the library's interface.

## Baritone binary heap

- Original source, same upstream revision:
  https://github.com/cabaletta/baritone/blob/c55a9c5d9a333c9f0336179a96c13a178fcbc2d4/src/main/java/baritone/pathing/calc/openset/BinaryHeapOpenSet.java
- Adapted file: `src/main/java/dev/begleiter/nav/NodeHeap.java`.
- Author: leijurv and the Baritone contributors; upstream `@author leijurv` retained.
- License: LGPL-3.0-or-later; the same LGPL/GPL license texts listed above apply.

Modified by Leak1337 on 2026-11-02: changed package/class and public method names,
removed `IOpenSet`, substituted the independently authored Begleiter `Node` data
and its `f`/`heapIdx` fields, added membership reporting and rejected capacities
smaller than two. The heap algorithms and cached-cost array are adapted from
Baritone. The library operates on application-owned nodes supplied to its methods;
the other navigation classes are independently licensed under Apache-2.0.

The comparison-only files `tools/benchmarks/dev/begleiter/nav/BaritoneNodeHeap.java`
and `BaritoneCellMap.java` are also LGPL-3.0-or-later adaptations from the same
revision. Their originals are `src/main/java/baritone/pathing/calc/openset/BinaryHeapOpenSet.java`
and the `getNodeAtPosition` method in `src/main/java/baritone/pathing/calc/AbstractNodeCostSearch.java`.
Changes: renamed classes/package, substituted Begleiter's `Node`/field names,
removed unrelated search code and exposed only the operations needed by the comparison.
They are not compiled into the mod. Their source is included for reproducibility.
The independent comparison driver and Gradle script are Apache-2.0.

## Baritone position lookup cache

- Original source, same upstream revision:
  https://github.com/cabaletta/baritone/blob/c55a9c5d9a333c9f0336179a96c13a178fcbc2d4/src/main/java/baritone/utils/BlockStateInterface.java
  (`get0` and search-cache allocation).
- Adapted file: `src/main/java/dev/begleiter/thirdparty/baritone/PositionCache.java`.
- Authors: leijurv and the Baritone contributors.
- License: LGPL-3.0-or-later, with the same license texts and corresponding-source
  distribution described in this document.

Modified by Leak1337 on 2026-11-02: extracted the position lookup into an independent,
generic class, made capacity configurable and bounded, and replaced the sentinel
key with generation tags. This handles every packed coordinate (including `0` and
`-1`) and distinguishes a cached null from a miss. Hash lookup and direct-mapped
collision replacement are adapted from Baritone. The class has no Minecraft or
Begleiter dependencies. Its source is included in every mod's embedded source zip.
The application uses it only during navigation search, not for live movement reads.

The mining-cost memoization in `NavContext` follows Baritone's search-only caching
idea but uses independently implemented, bounded fastutil maps and explicitly
invalidates on world-snapshot and permission-policy changes. It is Apache-2.0.

## Bundled building designs

The bundled schematics are Begleiter's own designs; the scripts `tools/build_request_storage.py`
and `tools/build_survival_storage.py` produce them. The box unloader in the central storage
(`request_storage_128`) follows the mechanism of
[BU003 Small 1x Box Unloader by misterkartoffel and sergyd](https://storagecatalog.org/archives/BU003-Small-1x-Box-Unloader/);
the blocks are placed by Begleiter's generator, not copied from their schematic file.
Item and block names are not bundled: English names come from the game itself, German names
are read from the installed game on the client.

## Gradle wrapper tooling

The corresponding-source archive includes the Gradle wrapper and startup scripts.
Their existing copyright and Apache-2.0 headers are retained. The Apache License
text is included in `LICENSES/Apache-2.0.txt`. These are build tools, not mod runtime code.

## Corresponding source and replacing the library

Each binary mod jar contains **its own corresponding source**, including the adapted
library, application source, resources, Gradle build files and wrapper, at
`META-INF/begleiter-sources.zip`. The same archive is also generated separately as
`build/distributions/begleiter-<version>-sources.zip`. Release publishers should keep
this embedded archive and the license/notice files with the binary. The normal
`-sources.jar` is generated as well.

To build a modified version:

1. Extract `META-INF/begleiter-sources.zip` from the mod jar into an empty directory,
   then extract that zip.
2. Install JDK 25. Edit the LGPL library source listed above, keeping its constructor
   and public methods compatible with the application.
3. Run `./gradlew assemble` on Linux/macOS (`bash ./gradlew assemble` if needed), or
   `.\gradlew.bat assemble` on Windows. Gradle downloads the dependencies listed in
   the build files, including Fabric and Minecraft, if they are not already cached.
4. Replace the installed Begleiter jar with the rebuilt jar from `build/libs`.

No signing key or publisher-only tool is needed to run the modified mod. The LGPL
library can also be compiled on its own using `javac -d classes
src/main/java/dev/begleiter/thirdparty/baritone/BetterWorldBorder.java`.
Modification of the library and reverse engineering to debug those modifications
are permitted as required by LGPL section 4. This permission adds no restriction
to the Apache-2.0-licensed application code.
