# Releasing

Releases are a Maven repository on GitHub Pages: the `gh-pages` branch, its `maven/` directory, served at
https://okay-effects.github.io/okay-store/maven. No account and no token is needed to use it, only resolvers: this one and
okay-simple's, which it depends on.

## Using a release

```scala-sbt
resolvers ++= Seq("okay-effects" at "https://okay-effects.github.io/okay-simple/maven",
  "okay-store" at "https://okay-effects.github.io/okay-store/maven")

libraryDependencies ++= Seq(
  "io.github.okay-effects" %% "okay-store"      % "0.1.0",   // the store, Data, Mapping: every platform
  "io.github.okay-effects" %% "okay-store-jdbc" % "0.1.0"    // the store on a database, by JDBC: the JVM
)
```

`%%%` for Scala.js and Scala Native. Built on Scala 3.3 LTS and Scala 2.13.

## Making one

1. On `main`, everything green, on every platform, by both compilers:

   ```sh
   sbt -Dplatforms=jvm,js,native clean +test
   ```

2. The version: `ThisBuild / version` in `build.sbt` without `-SNAPSHOT`, committed, tagged (`v0.1.0`).

3. The repository written into `target/pages-maven/`, for both compilers and all three platforms:

   ```sh
   rm -rf target/pages-maven
   sbt -Dplatforms=jvm,js,native publishPages
   ```

4. Onto `gh-pages`, under `maven/`, beside the releases already there:

   ```sh
   git worktree add /tmp/okay-store-pages gh-pages     # the first time: git worktree add --orphan -b gh-pages /tmp/okay-store-pages
   mkdir -p /tmp/okay-store-pages/maven
   rsync -a target/pages-maven/ /tmp/okay-store-pages/maven/
   touch /tmp/okay-store-pages/.nojekyll
   git -C /tmp/okay-store-pages add -A
   git -C /tmp/okay-store-pages commit -m "release 0.1.0"
   git -C /tmp/okay-store-pages push origin gh-pages
   git push origin v0.1.0
   ```

   The first time, GitHub Pages is turned on for the branch `gh-pages` in the repository's settings.

5. The version back to the next `-SNAPSHOT`.

A release is never rewritten: a version published is what every build that resolved it has.
