# Production Releases

## Prepare

1. Define the next semantic version

   Semantic versioning: a.b.c

   - a: major breaking changes
   - b: new functionality, new features
   - c: any other small changes

2. Checkout the main branch and make sure it is up-to-date: `git checkout main && git pull`
3. Create a new branch
4. Update the CHANGELOG.md file with changes of this release, you should add a new section with your version number and the relevant updates, like the ones that exist on the previous versions
5. Change the version in `gradle.properties`
6. Change the version in `maestro-cli/gradle.properties`
7. `git commit -am "Prepare for release X.Y.Z."` (where X.Y.Z is the new version)
8. Submit a PR with the changes against the main branch
9. Merge the PR

## Tag

1. `git tag -a vX.Y.Z -m "Version X.Y.Z"` (where X.Y.Z is the new version)
2. `git push git@github.com:mobile-dev-inc/Maestro.git vX.Y.Z` (or `git push https://github.com/mobile-dev-inc/Maestro.git vX.Y.Z` if you don't use ssh - gross)
3. Wait until all Publish actions have completed https://github.com/mobile-dev-inc/maestro/actions

## Wait for publish to Maven Central

 View the triggered [Publish Release action](https://github.com/mobile-dev-inc/maestro/actions/workflows/publish-release.yaml) and wait for it to finish

____________________________________________________________________________________________________________________________________________________
**STAFF:** You should update **Maestro Cloud** version before updating the **CLI**, and **Maestro Studio** immediately after. Instructions in this internal [notion document](https://www.notion.so/Maestro-Release-Run-Book-78159c6f80de4492a6e9e05bb490cf60?pvs=4).
____________________________________________________________________________________________________________________________________________________

## Publish CLI

1. Trigger the [Publish CLI Github action](https://github.com/mobile-dev-inc/Maestro/actions/workflows/publish-cli.yaml)
2. Test installing the cli by running `curl -Ls "https://get.maestro.mobile.dev" | bash`
3. Check the version number `maestro --version`

