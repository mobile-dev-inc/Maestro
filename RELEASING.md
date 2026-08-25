# Releasing Maestro

Releases are cut with the `release` skill in `.claude/skills/release/SKILL.md`. That file is the release doc; it isn't summarised anywhere else so it can't drift.

The short version: the commit being released must already have been deployed to Maestro Cloud and run there for about a day. Then a prep PR bumps the version and changelog, the merge commit is tagged `vX.Y.Z`, and the Publish CLI workflow is triggered. Publishing to Maven Central runs on its own from the tag and nothing waits on it.
