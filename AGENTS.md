# Repository workflow

- This workspace serves both GitHub repositories:
  - `origin`: https://github.com/1213109176kgy-png/LianAi-Keyboard
  - `codex-fork`: https://github.com/yang8985883/LianAi-Keyboard
- When the user requests a commit/push, fetch both remotes first. Integrate new remote commits and resolve conflicts while retaining the user's confirmed product behavior. Do not force-push.
- Validate the integrated code with Android unit tests, a debug build, and the connected physical device before creating the final integration commit or pushing. Follow `CONTRIBUTING.md`, including normal-editor and password-field checks. If the device is unavailable, explain the concrete blocker instead of claiming verification.
- Push the same validated commit to `main` in both repositories and verify both remote heads. Keep the current work branch synchronized when it has already been published.
- `admin/` is a local backend project and is intentionally ignored. Preserve its local files and runtime data; never stage it, including with `git add -f`.
- Keep API keys, signing secrets, local configuration, APKs, and private device screenshots out of commits.
