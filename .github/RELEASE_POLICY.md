# Release Policy

## Versioning

Nexa repositories version independently using Semantic Versioning. While a repository remains pre-1.0, minor versions may contain approved evolution and patch versions contain compatible fixes or documentation changes. A version applies only to the repository that publishes it.

Every release requires an annotated and signed Git tag, CHANGELOG entry, versioned release notes and a GitHub Release. From v0.14.0 onward, the tag signature must pass local verification and GitHub verification before publication. Published tags are immutable: do not retag, modify a published version or force push.

## Tag signing

Release tags from v0.14.0 onward MUST be:

- annotated
- signed with the repository maintainer's registered GPG or SSH signing key
- verified locally with `git verify-tag <version>`
- shown as `Verified` by GitHub after push

The private signing key remains outside the repository. Do not publish a release tag when local or GitHub verification fails; correct signing configuration first. Existing unsigned tags are historical and immutable.

## GitFlow

```text
feature/*
    ↓
develop
    ↓
release/vX.Y.Z
    ↓
main
    ↓
annotated signed tag
    ↓
GitHub Release
    ↓
back-merge to develop
```

## Release cadence

A merged PR is not automatically a release. Accumulate feature and fix PRs on
`develop` while the coherent release scope is being assembled. Create a
release branch only at a real release boundary, then validate and publish one
consumable milestone. Use release candidates only when final validation needs a
candidate freeze. Do not publish calendar-driven versions or one stable
release per implementation PR.

## Release checklist

- [ ] Scope approved.
- [ ] Working tree clean.
- [ ] Tests green.
- [ ] Build/package green.
- [ ] Runtime evidence captured when applicable.
- [ ] Browser evidence captured when applicable.
- [ ] Security review completed.
- [ ] CHANGELOG updated.
- [ ] Release notes reviewed.
- [ ] Version updated.
- [ ] Release branch created.
- [ ] `main` merged with `--no-ff`.
- [ ] Annotated signed tag created.
- [ ] `git verify-tag <version>` passed locally.
- [ ] GitHub tag verification passed.
- [ ] GitHub Release published.
- [ ] `develop` back-merged.

## Rollback and deprecations

Rollback is conceptual and depends on the consuming deployment process; it must not reuse a published tag. Breaking changes require a new compatible release line, migration notes and explicit contract impact. Deprecations require a reason, replacement guidance and a removal trigger.
