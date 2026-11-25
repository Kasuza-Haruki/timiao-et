# CI workflow (parked)

The GitHub token on this machine lacks the `workflow` scope, so this file cannot live in `.github/workflows/` yet.
To enable daily upstream tracking:

```
gh auth refresh -s workflow
git mv kernel/ci/track-upstream.yml .github/workflows/track-upstream.yml
git commit -m "ci: enable upstream tracking" && git push
```
