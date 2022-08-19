# Production Releases

### About semantic version numbers

Select a higher semantic version number.
Semantic versioning: a.b.c
   * a: major breaking changes
   * b: new functionality, new features
   * c: any other small changes

## Publish to Maven

0. Pick a new semantic version number, cd to the maestro repo and run

  `export VERSION="<version number>"`

1. Create a release branch and add a changelog item

Edit and run this script
```
# Create a release branch
git stash --include-untracked
git checkout main
git pull
git checkout -b release

# Update CHANGELOG.md
echo "" >> CHANGELOG.md
echo "## $VERSION - $(date +'%Y-%m-%d')" >> CHANGELOG.md
echo "" >> CHANGELOG.md
cat <<EOT >> CHANGELOG.md
* <describe a change here>
* <describe another change here>
EOT

# Update the version number in gradle.properties
sed -i '' "s/VERSION_NAME=.*/VERSION_NAME=$VERSION/" gradle.properties

git add gradle.properties CHANGELOG.md
git commit -am "Prepare for release $VERSION"

# Push branch to origin
git push --set-upstream origin `git rev-parse --abbrev-ref HEAD`
```

  * Click the link in the terminal and create a PR
  * Verify that the VERSION_NAME and CHANGELOG.md are set correctly
  * Merge the PR after approval

2. Tag the release

Run this script
```
git checkout main
git pull
git branch -d release
git tag -a "v$VERSION" -m "Version $VERSION"
git push --tags
```

3. Publish to maven

  * Open [Sonatype](https://s01.oss.sonatype.org/)
  * Login with the sonatype credentials in 1Password
  * Go to **Staging Repositories**
  * Wait a few minutes for the build to appear (Click the on-page Refresh button for a quick-refresh)
    * If this takes long, check that the [Publish release GH ACtion](https://github.com/mobile-dev-inc/maestro/actions/workflows/publish-release.yml) was successful and that the VERSION_NAME was set correctly in your release PR
  * Select the version you created, Click "Close" then "Confirm"
  * Wait for the signatures to be created (1 minute)
  * Select the version you created, Click "Release" then "Confirm"

It'll now take 5-45 minutes for the release to become available on maven.org

4. Update the version to the next SNAPSHOT

Run this script
```
export NEXT_VERSION=$(echo $VERSION | awk -F. '/[0-9]+\./{$NF++;print}' OFS=.)
git checkout main
git pull
git checkout -b prepare-$NEXT_VERSION-SNAPSHOT

sed -i '' "s/VERSION_NAME=.*/VERSION_NAME=$NEXT_VERSION-SNAPSHOT/" gradle.properties

git add gradle.properties
git commit -am "Prepare next development version"
git push --set-upstream origin `git rev-parse --abbrev-ref HEAD`
```

  * Click the link in the terminal and create a PR
  * Merge the PR after approval

5. Always continue with releasing CLI

## CLI

CLI can be released separately from Maven

- Update the version number in `maestro-cli/gradle.properties`
- Make a PR and Merge
- Manually trigger the [Publish CLI Github action](https://github.com/mobile-dev-inc/maestro/actions/workflows/publish-cli.yml)
