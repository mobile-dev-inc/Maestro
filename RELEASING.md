#Production Releases

1. Checkout origin/main
2. Update the CHANGELOG.md file with changes of this release.
3. Change the version in `gradle.properties` to a non-SNAPSHOT version.
4. Semantic versioning: a.b.c
   * a: major breaking changes
   * b: new functionality, new features
   * c: any other small changes
5. `git commit -am "Prepare for release X.Y.Z."` (where X.Y.Z is the new version)
6. Submit a PR with the changes against the main branch

After merging the PR, tag the release:

7. `git tag -a vX.Y.Z -m "Version X.Y.Z"` (where X.Y.Z is the new version)
8. `git push --tags`
9. Close and release the staging repository published at [Sonatype](https://s01.oss.sonatype.org/).

After this is done, create a new branch to prepare for the next development version:

1. `git checkout main && git pull && git checkout -b prepare-X.Y.Z-SNAPSHOT` (where X.Y.Z is the new development version)
2. Update the `gradle.properties` to the next SNAPSHOT version.
3. `git commit -am "Prepare next development version"`
4. Submit a PR with the changes against the main branch and merge it
