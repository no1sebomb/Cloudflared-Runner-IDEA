# Cloudflared Runner

An IntelliJ IDEA plugin that runs `cloudflared` from a tool window instead of a terminal tab.

Add a connection, hit start, and the plugin spawns the process, streams its output into a console,
and shows the useful bit of status — the generated `trycloudflare.com` hostname or the local bind
address — in a table. Closing the project kills every running process.

## Connection types

| Type | Command | Status shown |
| --- | --- | --- |
| **Quick tunnel** | `cloudflared tunnel --url <target>` | The generated `https://<name>.trycloudflare.com` hostname, scraped from stderr |
| **Access client** | `cloudflared access tcp --hostname <host> --url <bind>` | The local bind address, e.g. `localhost:5433` |

A quick tunnel is anonymous and ephemeral — no account, no login. An access client is the *client*
side of Cloudflare Access: it opens a local listener in front of a service that already sits behind
an Access policy. It does not create a tunnel.

## Authentication

The plugin never touches credentials. `cloudflared` opens the browser, catches the loopback
redirect, and caches its own short-lived token under `~/.cloudflared/`. The plugin only streams
output, so login prompts stay visible — including the manual auth URL cloudflared prints when it
cannot open a browser (headless or remote sessions). That case shows up in the status column as
"Login required — see log".

## Requirements

`cloudflared` must be on `PATH`. If it is not, starting a connection fails with the launch error in
the status column.

## Usage

Open the **Cloudflared Runner** tool window on the right edge.

- **+** adds a connection; pick *Quick tunnel* or *Access client* in the dialog.
- Select a row and use ▶ / ⏸ in the toolbar, or double-click the row, to start and stop it.
- The copy button puts the row's URL or bind address on the clipboard.
- The lower pane is that connection's console — output is per connection and survives stopping.

Connections are stored per project in `.idea/cloudflaredRunner.xml`.

## Non-goals

No Cloudflare API calls, no named tunnels, no `config.yml`, no `~/.cloudflared/` parsing, no
`cloudflared tunnel login` flow. This wraps the CLI and manages child processes; that is all.

## Development

```bash
./gradlew check         # ktlint + unit tests (output parsing, argument vectors)
./gradlew ktlintFormat  # fix formatting violations in place
./gradlew runIde        # sandbox IDE with the plugin installed
./gradlew buildPlugin   # distributable zip in build/distributions
./gradlew verifyPlugin  # IntelliJ Plugin Verifier against recommended IDEs
```

CI (`.github/workflows/build.yml`) runs `check` + `verifyPluginProjectConfiguration` and
builds the plugin on every push and pull request, then runs the Plugin Verifier in a second
job. Pushing a `v*` tag builds the zip and attaches it to a GitHub Release
(`.github/workflows/release.yml`); Marketplace publishing is not wired up.

Source layout:

```
src/main/kotlin/com/noisebomb/cloudflared/
├── model/Connections.kt          Connection config, type, live state
├── service/
│   ├── TunnelService.kt          Project service: connection list, process lifecycle, persistence
│   └── CloudflaredOutput.kt      Parsing of the few meaningful output lines
└── ui/
    ├── TunnelToolWindowFactory.kt
    ├── TunnelPanel.kt            Table of connections + per-connection console
    └── ConnectionDialog.kt       Add/edit form
```

Built with the [IntelliJ Platform Gradle Plugin][gradle-plugin], targeting IntelliJ 2025.3 (build
253) and later.

## Trademarks

Not affiliated with Cloudflare, Inc. "Cloudflare" and "cloudflared" are trademarks of Cloudflare,
Inc.; they are used here only to describe what this plugin runs.

[gradle-plugin]: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
