<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Cloudflared Runner Changelog

## [Unreleased]

## [1.0.0] - 2026-08-18

### Added

- Tool window listing `cloudflared` connections, with per-row start/stop.
- Quick tunnel support (`cloudflared tunnel --url`), with the generated `trycloudflare.com` hostname
  scraped from the output and shown as a link.
- Access client support (`cloudflared access tcp|ssh|rdp|smb`), with the local listener it opens.
- Route column reading *service → the address that reaches it*, the same way round for both types.
- Per-connection console streaming stdout and stderr, so login prompts and auth URLs stay visible.
- Add/edit dialog with the advanced `cloudflared` flags as real controls, a copyable preview of the
  startup command, and live checks of every address: DNS, reachability, whether a bind port is free,
  and whether the executable is really `cloudflared`.
- Per-connection `Executable` override for a `cloudflared` that is not on `PATH`.
- Optional row colour for telling a long list apart.
- Connections persisted per project; running processes killed when the project closes.

[Unreleased]: https://github.com/no1sebomb/Cloudflared-Runner-IDEA/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/no1sebomb/Cloudflared-Runner-IDEA/commits/v1.0.0
