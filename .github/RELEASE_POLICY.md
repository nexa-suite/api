# Release Policy

## Versioning

Nexa repositories version independently using Semantic Versioning. While a repository remains pre-1.0, minor versions may contain approved evolution and patch versions contain compatible fixes or documentation changes. A version applies only to the repository that publishes it.

Every release requires an annotated Git tag, CHANGELOG entry, versioned release notes and a GitHub Release. Published tags are immutable: do not retag, modify a published version or force push.

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
annotated tag
    ↓
GitHub Release
    ↓
back-merge to develop
```

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
- [ ] Annotated tag created.
- [ ] GitHub Release published.
- [ ] `develop` back-merged.

## Rollback and deprecations

Rollback is conceptual and depends on the consuming deployment process; it must not reuse a published tag. Breaking changes require a new compatible release line, migration notes and explicit contract impact. Deprecations require a reason, replacement guidance and a removal trigger.
