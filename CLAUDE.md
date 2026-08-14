# CLAUDE.md

Context for building **Cloudflared Runner**, an IntelliJ IDEA plugin that manages
local `cloudflared` processes from a sidebar tool window instead of a terminal tab.

## What this plugin does

The user runs `cloudflared` from the terminal to expose or reach local services and
doesn't want to keep terminal tabs open. This plugin moves that into a managed UI:
spawn the process, stream its output, show status, and stop it with a button. Closing
the project kills any running process so nothing leaks.

There is **no Cloudflare API integration and no credential management**. The plugin
wraps the `cloudflared` CLI and manages child processes. `cloudflared` handles all
auth itself.

## Two connection types (both are just child processes)

1. **Quick tunnel** — `cloudflared tunnel --url <target>`
   - Anonymous, ephemeral. No auth, no login, no account.
   - `<target>` is like `localhost:8080` (http) or `tcp://localhost:5432`.
   - cloudflared prints a random `https://<name>.trycloudflare.com` hostname to
     **stderr**. Scrape it with `https://[-a-z0-9.]+\.trycloudflare\.com` and show it.

2. **Access client** — `cloudflared access tcp --hostname <host> --url <localBind>`
   - Client side of Cloudflare Access. Opens a local listener (e.g. `localhost:5433`)
     that proxies to a service already sitting behind an Access policy. It does NOT
     create a tunnel.
   - Auth is handled by cloudflared: on first use (or after token expiry) it opens a
     browser for SSO and caches a short-lived token under `~/.cloudflared/`. Later
     runs reuse the cached token silently.
   - No `trycloudflare.com` URL to scrape. The useful status is the local bind
     address the user passed in. On expiry in a headless/remote setup, cloudflared
     prints an auth URL to stderr — streaming stderr covers this case so the user can
     click it.

The two differ only in the argument vector and what status to display. Model them
uniformly.

## Key implementation facts

- **Auth is never the plugin's job.** cloudflared opens the browser, catches the
  loopback redirect, and caches the token. The plugin only streams output so the user
  sees prompts and any manual-auth URL. Do NOT try to intercept or reimplement OAuth.
- **Output is mostly on stderr.** The generated URL, "listening on…", and auth
  prompts all come out on stderr. `OSProcessHandler` captures both streams, so this is
  fine — just don't assume stdout.
- **`cloudflared` must be on PATH.** `GeneralCommandLine` throws if not found. A
  settings field for an explicit binary path is a good v2 addition, not needed for v1.
- **Lifecycle:** keep the `OSProcessHandler` per running connection. Register the
  panel as a `Disposable` on the project and `destroyProcess()` in `dispose()` so
  tunnels die when the project closes.
- **Threading:** process callbacks arrive off the EDT. Wrap all Swing updates in
  `SwingUtilities.invokeLater { … }`.

## Architecture

- `plugin.xml` — registers a `com.intellij.toolWindow` extension: id
  `"Cloudflared Runner"`, `anchor="right"`, an icon (e.g. `AllIcons.General.Web`),
  and `factoryClass`.
- `TunnelToolWindowFactory : ToolWindowFactory` — builds the panel and adds it as
  content.
- `TunnelPanel : JPanel, Disposable` — the UI and process management.
- A per-connection model holding: connection type (quick vs access), the argument
  vector, the `OSProcessHandler`, and current status.

### Target shape (multi-connection)

A list/table where each row is one connection (either type), with:
- add ("+") offering *Quick tunnel* or *Access client*
- per-row start/stop and a status column (URL, bind address, or error)
- a log/console view for the selected row (surfaces auth prompts)
- start/stop wired to `OSProcessHandler` with a `ProcessAdapter` streaming
  `onTextAvailable` into the log and updating status

v1 can be a single connection with hardcoded fields; generalize to the list once both
types work.

## Build

- Gradle IntelliJ Plugin (`org.jetbrains.intellij`), Kotlin JVM.
- Target IntelliJ `IC` (Community is fine); `depends` on
  `com.intellij.modules.platform`.
- Set `sinceBuild` / `untilBuild` in `patchPluginXml`.

## Constraints / non-goals

- No named tunnels, no `config.yml`, no `~/.cloudflared/` parsing, no
  `cloudflared tunnel login` flow. The user only uses quick tunnels and `access tcp`.
- No Cloudflare account API calls.
- Keep it as simple as possible — the point is replacing a terminal tab, not building
  a full cloudflared manager.
