---
name: No server restart for non-route changes
description: Server only needs restart for new routes. CSS/views/config changes don't require restart.
type: feedback
---

Do NOT restart the server for CSS, view, or config changes. Only restart for new route additions.

**Why:** The REPL is not in the same JVM as the web server. CSS is served fresh from disk each request. Clojure view changes via `#'var` references hot-reload automatically. Browser cache is the usual culprit — use Cmd+Shift+R instead.

**How to apply:** For CSS changes, just hard-reload the browser. For Clojure view/logic changes, the server auto-reloads via var references. Only add `make stop && make server-dev` when adding new routes to the router.
