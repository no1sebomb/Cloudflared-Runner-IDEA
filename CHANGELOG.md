<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Cloudflared Runner Changelog

## [Unreleased]

### Added

- Tool window listing `cloudflared` connections, with start/stop and a status column.
- Quick tunnel support (`cloudflared tunnel --url`), showing the generated `trycloudflare.com` hostname.
- Access client support (`cloudflared access tcp`), showing the local bind address.
- Per-connection console streaming stdout and stderr, so login prompts and auth URLs are visible.
- Connections persisted per project; running processes killed when the project closes.
