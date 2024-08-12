# Production Releases

## Prepare

1. Define the next semantic version

   Semantic versioning: a.b.c

   - a: major breaking changes
   - b: new functionality, new features
   - c: any other small changes

2. Checkout the main branch and make sure it is up-to-date: `git checkout main && git pull`
3. Create a new branch
4. Update the CHANGELOG.md file with changes of this release
5. Change the version in `gradle.properties`
6. Change the version in `maestro-cli/gradle.properties`
7. `git commit -am "Prepare for release X.Y.Z."` (where X.Y.Z is the new version)
8. Submit a PR with the changes against the main branch
9. Merge the PR

## Tag

1. `git tag -a vX.Y.Z -m "Version X.Y.Z"` (where X.Y.Z is the new version)
2. `git push --tags`
3. Wait until all Publish actions have completed https://github.com/mobile-dev-inc/maestro/actions

## Publish CLI

1. Trigger the [Publish CLI Github action](https://github.com/mobile-dev-inc/maestro/actions/workflows/publish-cli.yml)
2. Test installing the cli by running `curl -Ls "https://get.maestro.mobile.dev" | bash`
3. Check the version number `maestro --version`

## Publish Maven Central

1. Trigger the [Publish Release action](https://github.com/mobile-dev-inc/maestro/actions/workflows/publish-release.yml)
   - ATTENTION: Wait for it to finish
3. Go to [OSS Sonatype](https://s01.oss.sonatype.org/) and login with user/password
4. Go to Staging Repositories, select the repository uploaded from the trigger above.
5. Click "Close" and then "Release". Each of these operations take a couple minutes to complete
