# Production Releases

## Maven

1. Checkout the main branch and make sure it is up to date: `git checkout main && git pull`
2. Create a new branch
3. Update the CHANGELOG.md file with changes of this release.
4. Change the version in `gradle.properties` to a non-SNAPSHOT version.
5. Semantic versioning: a.b.c
   * a: major breaking changes
   * b: new functionality, new features
   * c: any other small changes
6. `git commit -am "Prepare for release X.Y.Z."` (where X.Y.Z is the new version)
7. Submit a PR with the changes against the main branch

After merging the PR, tag the release:

7. `git tag -a vX.Y.Z -m "Version X.Y.Z"` (where X.Y.Z is the new version)
8. `git push --tags`
9. Close and release the staging repository published at [Sonatype](https://s01.oss.sonatype.org/).

After this is done, create a new branch to prepare for the next development version:

1. `git checkout main && git pull && git checkout -b prepare-X.Y.Z-SNAPSHOT` (where X.Y.Z is the new development version)
2. Update the `gradle.properties` to the next SNAPSHOT version.
3. `git commit -am "Prepare next development version"`
4. Submit a PR with the changes against the main branch and merge it

## CLI

- Update version in `maestro-cli/gradle.properties`
- Merge the change
- Trigger Publish CLI Github action
