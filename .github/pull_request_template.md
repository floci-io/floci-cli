## Summary

<!-- What does this PR do? Link any related issues with "Closes #N" -->

## Type of change

- [ ] Bug fix (`fix:`)
- [ ] New feature (`feat:`)
- [ ] Breaking change (`feat!:` or `fix!:`)
- [ ] Docs / chore

## Product trees affected

<!-- Shared command changes must be applied to all three trees (see AGENTS.md) -->

- [ ] AWS (root tree)
- [ ] GCP (`commands/gcp/`)
- [ ] Azure (`commands/az/`)
- [ ] N/A (shared infrastructure, docs, CI)

## Checklist

- [ ] `mvn test` passes locally
- [ ] New or updated tests added (or explained below why not needed)
- [ ] `CHANGELOG.md` entry added under `[Unreleased]` (user-visible changes)
- [ ] `README.md` updated (if a command or flag changed)
- [ ] Native binary verified (`mvn package -Pnative`) — required if Jackson serialization or dependencies changed
- [ ] Commit messages / PR title follow [Conventional Commits](https://www.conventionalcommits.org/)
