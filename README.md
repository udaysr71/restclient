<div align="center">

# REST Client

**A lightweight desktop REST client in pure Java. One JAR, zero dependencies, no accounts, no cloud.**

![Java 17+](https://img.shields.io/badge/Java-17%2B-4059D6?style=flat-square&logo=openjdk&logoColor=white)
![Dependencies: none](https://img.shields.io/badge/dependencies-none-16722F?style=flat-square)
![Platforms](https://img.shields.io/badge/platforms-Windows%20%C2%B7%20macOS%20%C2%B7%20Linux-5A5E73?style=flat-square)
![License: MIT](https://img.shields.io/badge/license-MIT-1F6FA8?style=flat-square)
![Built with Claude](https://img.shields.io/badge/built%20with-Claude-D97757?style=flat-square)

<img src="docs/screenshots/hero.png" alt="REST Client in light and dark themes, with the command palette open" width="100%">

</div>

Everything is stored as plain JSON in `~/.restclient`.

> **A hobby project, built with Claude.** This app was designed and written in conversation with
> [Claude](https://claude.ai) (Anthropic's AI assistant): wireframes, code, tests and this README.
> It is **not meant for serious or production work**. It's a personal tool and an experiment in what a
> dependency-free Swing app can look like. That said, **it works**: it sends real requests, keeps your
> collections and environments, and has unit tests and an automated UI check behind it. Use it, poke at
> it, and keep a backup of anything you care about in `~/.restclient`.
>
> **Not taking requests.** Issues, feature requests and pull requests aren't being accepted. You're
> free to **fork it** and make it your own under the [MIT License](LICENSE).

**Zero third-party dependencies.** Everything — the look and feel, icons, code editor, JSON library and
test kit — is in this repository, and the build needs nothing but a JDK. See [Dependencies](#dependencies).

- Collections with folders, drag-and-drop reordering, duplicate, rename, and delete (with Undo)
- Environments, collection variables and globals with `{{variable}}` syntax
  - hover a variable to see its value and where it comes from; ⌘/Ctrl-click to jump to it
  - undefined variables are underlined in red, with a one-click "Define…"
  - autocomplete after typing `{{`
- Recent history, grouped by day, with read-only snapshots (secrets masked)
- cURL import (paste into any URL field, or the import dialog) and export
- TLS: extra trusted certificates and client certificates from `~/.restclient/certs`,
  a "Trust this certificate" flow, and per-request or default "skip verification" (certificate **and** hostname)
- Command palette (⌘K / Ctrl+K), Light, Dark, Solarized Sepia and Solarized Dark themes (or follow the OS), choosable interface and code fonts, tabs and drafts restored on relaunch

## Screenshots

<div align="center">

<img src="docs/screenshots/themes.png" alt="The four themes: Light, Dark, Solarized Sepia and Solarized Dark" width="100%">

<sub>Four themes (Light, Dark, Solarized Sepia, Solarized Dark) or follow the OS, all checked for WCAG AA contrast.</sub>

</div>

<table>
  <tr>
    <td width="50%"><img src="docs/screenshots/command-palette.png" alt="Command palette"><br>
      <b>Command palette</b><br><sub>⌘K / Ctrl+K to jump to any request, environment or action.</sub></td>
    <td width="50%"><img src="docs/screenshots/environments.png" alt="Environments dialog"><br>
      <b>Environments and variables</b><br><sub>Secrets, usage counts and a clear lookup order.</sub></td>
  </tr>
  <tr>
    <td><img src="docs/screenshots/autocomplete.png" alt="Variable autocomplete"><br>
      <b>Variable autocomplete</b><br><sub>Type <code>{{</code> anywhere for a list of defined variables.</sub></td>
    <td><img src="docs/screenshots/curl-import.png" alt="cURL import"><br>
      <b>cURL import</b><br><sub>Paste a command and see it parsed as you type.</sub></td>
  </tr>
  <tr>
    <td><img src="docs/screenshots/settings-fonts.png" alt="Settings with theme and font pickers"><br>
      <b>Themes and fonts</b><br><sub>Pick interface and code fonts; readable fonts listed first.</sub></td>
    <td><img src="docs/screenshots/tab-menu.png" alt="Tab context menu"><br>
      <b>Tabs that behave</b><br><sub>Duplicate, close others / left / right, reopen closed tabs.</sub></td>
  </tr>
</table>

## Build and run

Requirements: **JDK 17 or newer**. Nothing else — no Maven, no Gradle, no downloads.

```bash
java Build.java          # compile, run the tests, build build/restclient.jar
java -jar build/restclient.jar
```

Other tasks: `java Build.java jar` (skip tests), `java Build.java test`, `java Build.java run`,
`java Build.java clean`. `Build.java` is a single-file Java program that drives `javac` and `jar` from
the JDK. The JAR (~470 KB) runs on macOS, Windows and Linux.

A `pom.xml` with no dependencies is included only so IDEs can import the project; note that Maven itself
downloads its plugins from Maven Central.

To use a different data folder (handy for trying things out):

```bash
java -Drestclient.home=/tmp/rc-sandbox -jar build/restclient.jar
```

## Where data lives

```
~/.restclient/
  collections/      one JSON file per collection (e.g. orders-api-1a2b3c4d.json)
  environments/     one JSON file per environment, plus globals.json
  certs/            extra trusted certificates and keystores (see below)
  history.json      recent requests (default: last 200)
  settings.json     preferences, keystore passwords, active environment
  session.json      open tabs (including unsaved drafts) and window layout
```

All files are human-readable JSON and are written atomically (temp file + rename), so a crash
can't leave a half-written file. If a file can't be read, it's renamed to `*.corrupt-<timestamp>`
and the app starts with defaults, telling you in the status bar.

## Environments and variables

Use `{{name}}` anywhere: URL, params, headers, body, auth fields.

Lookup order (first match wins):

1. the **active environment** (picker in the toolbar, or ⌘E / Ctrl+E)
2. the **collection** the request belongs to
3. **Globals**

Variables can be disabled or marked **secret**. Secret values are masked in the UI, in hover popovers
(until you click *Reveal*), and in history snapshots. Values may themselves contain variables.

Manage them in **Request › Manage Environments…** — edits save automatically, ⌘Z / Ctrl+Z undoes,
and the *Used in* column shows how many saved requests reference each variable.

## Certificates and TLS

### Trusting extra certificates

Drop files into `~/.restclient/certs`, then click **Reload** in Settings › Certificates
(or restart). Supported:

| File | Used as |
|---|---|
| `.pem` `.crt` `.cer` `.der` | trusted certificates (PEM bundles with several certificates are fine) |
| `.p12` `.pfx` `.jks` `.keystore` with only certificates | trusted certificates (a truststore) |
| `.p12` `.pfx` `.jks` `.keystore` with a private key | **client certificate** for mTLS |

These are **added to** the JDK's default trust store, so public sites keep working.
Keystore passwords are set per file in Settings › Certificates (`changeit` and an empty password are
tried automatically). A file that fails to load is listed with the reason; the others still load.

When a server's certificate isn't trusted, the response pane offers **View & Trust…**, which shows
the chain (subject, issuer, validity, SHA-256 fingerprint), saves the chosen certificate as PEM into
the certs folder, reloads, and can re-send the request.

### Skipping verification

Per request (Settings tab, the ⋯ menu, or *Retry Insecurely* on a TLS error), or as the default
(Settings › Certificates). While it's on, an orange **TLS verification off** badge sits next to Send.

Skipping turns off **both** certificate-chain validation **and** hostname verification.
How: `java.net.http.HttpClient` performs hostname checks inside the trust manager when that trust manager
is an `X509ExtendedTrustManager`. The skip context uses a no-op `X509ExtendedTrustManager`, so neither
check runs — for that request only. The alternative, the JVM-wide system property
`jdk.internal.httpclient.disableHostnameVerification`, is read once at startup and would also weaken
verified requests, so it is deliberately **not** used. Verified and skipped requests use two separate
`HttpClient` instances. (`TlsManagerTest` checks this end to end against a local HTTPS server.)

## cURL

- **Import:** paste a `curl …` command into any URL field and it's imported into that tab, or use
  **File › Import cURL…** (⌘⇧I / Ctrl+Shift+I) for a live preview.
  Supported: `-X/--request`, `-H/--header`, `-d/--data/--data-raw/--data-binary/--data-ascii`,
  `--data-urlencode`, `--json`, `-u/--user`, `-k/--insecure`, `--url`, `-G/--get`, `-I/--head`,
  `-A/--user-agent`, `-e/--referer`, `-b/--cookie`, `-m/--max-time`, `--oauth2-bearer`;
  single/double/`$'…'` quoting, `\` line continuations, combined flags like `-sSLk` and `-XPOST`.
  Options that don't apply are listed as *ignored*, never dropped silently; parse errors show the
  line and column.
- **Export:** ⋯ › *Copy as cURL* (⌥⌘C / Ctrl+Alt+C) resolves variables with the active environment;
  *Copy as cURL (keep {{variables}})* leaves them as placeholders. `-k` is added when verification is skipped.

## Keyboard shortcuts

| Action | macOS | Windows / Linux |
|---|---|---|
| Send | ⌘↩ | Ctrl+Enter |
| New tab | ⌘T | Ctrl+T |
| Close tab | ⌘W | Ctrl+W |
| Save / Save As | ⌘S / ⇧⌘S | Ctrl+S / Ctrl+Shift+S |
| Focus URL | ⌘L | Ctrl+L |
| Switch environment | ⌘E | Ctrl+E |
| Command palette | ⌘K | Ctrl+K |
| Import cURL | ⇧⌘I | Ctrl+Shift+I |
| Copy as cURL | ⌥⌘C | Ctrl+Alt+C |
| Find in response | ⌘F | Ctrl+F |
| Toggle sidebar | ⌃⌘S | Ctrl+B |
| Next / previous tab | ⇧⌘] / ⇧⌘[ | Ctrl+PgDn / Ctrl+PgUp |
| Settings | ⌘, | Ctrl+, |
| Undo / Redo | ⌘Z / ⇧⌘Z | Ctrl+Z / Ctrl+Y |
| Zoom in / out / actual size | ⌘+ / ⌘- / ⌘0 | Ctrl+= / Ctrl+- / Ctrl+0 |
| Toggle light / dark (Light↔Dark, Sepia↔Solarized Dark) | ⇧⌘L | Ctrl+Shift+L |
| Reopen closed tab | ⇧⌘T | Ctrl+Shift+T |
| Context menu (sidebar, history) | ⇧F10 | Shift+F10 / Menu key |

Zoom (75 % to 200 %) scales all text and rows and is remembered between launches; Ctrl+Shift+= and the
numeric-keypad + / - / 0 work too.

All labels in menus, tooltips and the palette come from one keymap (`ui/Keys.java`).
Palette prefixes: `>` actions, `@` environments, `#` history.

## Dependencies

There are none at runtime or build time. What would normally come from libraries is written here:

| Replaces | Lives in | Notes |
|---|---|---|
| FlatLaf (look and feel) | `ui/laf/` | Flat light/dark Swing look and feel on top of Swing's Basic L&F: rounded buttons, fields, combo boxes, spinners, check boxes, slim scroll bars, menus, source-list trees. Visual design inspired by FlatLaf and macOS; no FlatLaf code is included (see `NOTICE`). |
| FlatLaf Extras (SVG icons) | `ui/SvgIcon.java` | Renders the app's own small SVGs (path/rect/circle/ellipse, path commands M L H V C S Q T A Z), re-coloured for the theme at paint time. |
| RSyntaxTextArea (code editor) | `ui/CodeArea.java`, `ui/Lexer.java` | `JTextArea` with per-line JSON/XML/HTML colouring, line numbers and find. |
| Jackson (JSON) | `json/` | RFC 8259 parser and writer, a token-preserving pretty-printer, and a reflection mapper for the public-field model classes. |
| JUnit | `src/test/java/dev/restclient/testkit/` | `@Test`, `@BeforeEach`, `@AfterEach`, assertions and a runner. |

The only code that runs is the JDK and this repository, so the supply chain is the JDK you install.
To check: `unzip -l build/restclient.jar` lists only `dev/restclient/**`, `icons/**` and the manifest.

## Project layout

```
src/main/java/dev/restclient/
  App.java              entry point
  model/                RequestDef, ApiCollection, CollectionNode, Environment, Variable, HistoryEntry, Settings, Session
  store/                AppPaths, JsonStore (atomic JSON), Repository (state + background writes)
  vars/                 VariableResolver (scopes, precedence, masking)
  http/                 TlsManager, PreparedRequest, HttpService, ResponseData
  curl/                 ShellTokenizer, CurlParser, CurlExporter
  json/                 JSON parser, writer, reformatter, model mapper
  ui/                   Swing UI (MainFrame, RequestTab, VarSupport, CodeArea, TabStrip, dialogs…)
  ui/laf/               the look and feel
src/main/resources/icons/   SVG icons (palette colors are swapped for theme colors at paint time)
src/test/java/…         tests, plus the tiny test kit in testkit/
Build.java              JDK-only build script
```

## Tests

```bash
java Build.java test
java Build.java uicheck      # needs a display; on Linux CI: xvfb-run java Build.java uicheck
```

`uicheck` launches the real app against a local server and drives it with real key presses and mouse
clicks (76 checks). Beyond the list below, it also covers the Save state rules, empty-URL handling,
keyboard reachability, Reopen Closed Tab, keyboard context menus, delete with Undo, and an accessible-name
audit. It covers: typing, Backspace, arrows, Home/End, select-all and undo in the URL field, `{{` autocomplete,
query → Params sync, send, find, method combo with the keyboard, editing and toggling header rows, typing
JSON and Beautify, new/close tab, palette navigation with arrows and Enter, pasting cURL, the environment
menu, settings check boxes and spinners, adding a variable, zoom and theme switching. It fails on any
uncaught exception. `dev.restclient.ui.Gallery` opens every control on one page (pass `dark`, `sepia` or
`solarized_dark` for another theme), for checking components one at a time.

Covers the JSON library, the cURL parser and exporter (including export → import round trips), variable resolution and
precedence, request preparation (auth, content types, masking), persistence (atomic writes, renames,
corrupt files), and TLS: untrusted → trusted after saving the certificate, hostname mismatch still caught
when verifying, both skipped when not, bad files reported while others load, keystore passwords and
client keys; plus the SVG path parser (including arcs) and the syntax lexers.

## Known limitations

- Multipart (`-F`) bodies and file uploads aren't supported; such cURL options are listed as ignored.
- Client certificates must be in a `.p12/.pfx/.jks` store (PEM private keys aren't read).
- Proxies come from the standard Java properties (`https.proxyHost`, `http.nonProxyHosts`, …) or the
  OS defaults Java picks up; there's no proxy UI.
- Response timing is total time; there's no DNS/connect/TLS breakdown (HttpClient doesn't expose it).
- The code editor has no code folding or bracket matching, and very long single-line responses
  (multi-megabyte minified JSON) scroll more slowly than in a dedicated editor; use *Pretty*.
- Title bars: on Windows the app draws its own (themed, with the menu bar inside it, drag, double-click
  to maximise, edge resizing). Windows 11 Snap Layouts on the maximise button and Aero Snap gestures
  aren't available for a self-drawn title bar. macOS and Linux keep the system title bar (macOS follows
  the app's light/dark choice). Force either with `-Drestclient.titlebar=custom` or `=native`.
- Menus, dropdowns, tooltips and popovers draw their shadow inside the app window, so it works the same
  everywhere. One that doesn't fit inside the window opens without a shadow. The command palette's shadow
  uses a see-through window, only on Windows and macOS. `-Drestclient.noShadows=true` turns shadows off.

## Tabs

Right-click a tab for **New Tab, Duplicate Tab, Save, Rename…, Copy as cURL, Close, Close Other Tabs, Close
Tabs to the Left, Close Tabs to the Right, Close Unmodified Tabs** and **Close All Tabs**. When several tabs
with unsaved changes would close, you're asked once (Save All / Discard All / Cancel). Drag a tab to reorder
it, middle-click to close it. Duplicate, Close Other Tabs and Close All are also in the Window menu and the
command palette.

## Design notes

The layout follows the *Practical UI* guidelines:

- **Minimise interaction cost.** Method and URL are one field, right next to Send. The first save offers a
  "My Requests" collection, so it takes a name and Enter. Icon buttons have at least a 30 px target. The app
  opens with the cursor in the URL.
- **Group related elements.** Tables (params, headers, form fields, variables) have no outer box. A header
  rule, row dividers and alignment hold them together. Remove buttons appear only on the row you point at.
- **Similar things look similar.** Status and environment badges are rounded, tinted and never blue, so they
  don't read as buttons. Buttons are the only things that look pressable.
- **Colour.** One cool slate family (hue 230) for text, strokes, fills and backgrounds, plus an indigo brand
  colour. Body text keeps at least 4.5:1 contrast.
- **Commands are honest.** Save (menu, More menu, tab menu, palette, Ctrl+S) is only available when there's
  something to save: an edited request, or one that was never saved. Commands that need an open request are
  off when there isn't one, and the palette hides what can't run.
- **Menus.** Rounded menus with an inset, rounded highlight. Labels and shortcuts are separate columns with a
  guaranteed gap. A check column appears only when an item in that menu uses it. Menus opened from buttons
  near the right edge line up with the button's right edge so they're never cut off. Dropdown and
  autocomplete rows use the same rounded highlight and keep their own colours (a red DELETE stays red).
- **Overflowing tabs.** When tabs don't fit, a list button at the end of the tab bar shows all of them.
- **Line length.** Help text and descriptions wrap well short of the full width.

## Usability and accessibility

Checked against Nielsen's ten heuristics and WCAG 2.1 AA:

- **System status.** Sending shows a progress bar, elapsed time and Cancel. Saves, copies, imports and
  deletes confirm in the status bar.
- **User control.** Deleting a collection, folder, request or history entry, or clearing history, can be
  undone from the status bar. Closed tabs come back with Reopen Closed Tab. Closing unsaved work always asks
  first.
- **Error prevention and recovery.** An empty or invalid URL outlines the URL field in red, puts the cursor
  there and says what to fix. Connection, TLS and timeout errors are explained in plain language with a
  next step (Retry, Trust certificate, Retry without verification).
- **Help.** Help › Quick Start covers the main tasks in one screen. Help › Keyboard Shortcuts lists every
  shortcut. Every icon button has a tooltip.
- **Contrast.** `ContrastTest` checks both palettes. Text is at least 4.5:1 on every surface (including white
  on the accent colour and the status badges). Input outlines and the focus ring are at least 3:1.
- **Not colour alone.** Statuses carry text (200 OK, ERR, TLS). Undefined variables are underlined as well
  as red. Environments show their name next to their colour.
- **Keyboard.** Everything is reachable with Tab, including the icon buttons, which still don't take focus
  when clicked. Focus rings follow "focus visible": buttons, toggles and check boxes show the ring while you use the
  keyboard and hide it after a click; text fields always show it. Rings stay visible on filled buttons too.
  In the Settings tabs focus follows the selected tab, and Left/Right switch tabs. Context menus open with Shift+F10 or
  the Menu key. Focus returns to the URL field after closing a tab.
- **Screen readers.** Icon-only buttons and fields have accessible names. The UI check fails if one doesn't.

## Look and feel notes

**Appearance.** Five choices in View › Appearance, Settings › General, or by right-clicking the sun/moon
button: *Match System*, *Light*, *Dark*, *Solarized Sepia*, *Solarized Dark*, and any theme files of your own (below). The two Solarized themes use
Ethan Schoonover's Solarized colours, nudged where needed so every text colour still meets WCAG AA (checked
for all four palettes by `ContrastTest`, including syntax and method colours). The sun/moon button and ⇧⌘L /
Ctrl+Shift+L flip within a family: Light ↔ Dark, Solarized Sepia ↔ Solarized Dark. In Match System mode the app checks the OS setting every
few seconds in the background and follows it. Switching cross-fades the whole window. Dialogs and message
boxes use the app's own title bar on Windows, so none of them stay white in dark mode.

**Motion.** Short ease-out animations (120–260 ms): the editor-tab underline and segmented-control thumb
slide, button hovers fade, the sidebar slides in and out, dialogs and the command palette fade up into
place, and theme changes cross-fade. Settings › General › *Reduce motion* (or
`-Drestclient.reduceMotion=true`) turns them all off.

**Splash.** `java Build.java` draws `splash.png` (and a 2x version) with the app's own code and fonts and
names it in the jar manifest, so `java -jar restclient.jar` shows it immediately, before any app code
runs; a bar fills as the app loads and it closes when the window opens.

**Custom themes.** Themes are plain `.css` files in `~/.restclient/themes/`. They appear in View › Appearance
and Settings › General next to the built-in ones, and the app updates the moment you save a file. The quickest
start is View › Appearance › *Export Current Theme…*, which writes every colour, grouped and commented, for you to
edit. A theme only needs the colours it changes:

```css
/* ~/.restclient/themes/nord.css */
@theme {
  name: "Nord";
  base: dark;               /* light, dark, or another theme's file name: unset colours come from it */
  toggle: "nord-light";     /* optional: where the sun/moon button goes from here */
}

:root {
  --polar: #2E3440;         /* your own variables are fine */
  --panelBg: var(--polar);
  --sidebarBg: #3B4252;
  --text: #ECEFF4;
  --accent: #5E81AC;
  --link: #88C0D0;
  --method-GET: #A3BE8C;
  --hoverSubtle: rgba(236, 239, 244, 0.08);
}
```

- **Colours:** `#RGB`, `#RGBA`, `#RRGGBB`, `#RRGGBBAA`, `rgb()`, `rgba()` and `var(--name)`. Comments are `/* … */`.
- **Names:** the 59 colours are listed in an exported file and in
  [`ThemeFile.java`](src/main/java/dev/restclient/ui/ThemeFile.java). The two Solarized themes ship as files
  ([`src/main/resources/themes/`](src/main/resources/themes/)) and work as examples.
- **Problems** never stop a theme from loading. Settings › General lists anything it couldn't read, likely
  typos ("`--acent` … did you mean `--accent`?"), `var()` loops, and any text that falls below WCAG AA contrast.
- **Scope:** colours only. It's CSS-*like*, not a CSS engine: there are no selectors, sizes or layout, and a
  theme file can't run code or load anything.
- **Treated as untrusted input:** everything is allow-listed, so anything outside these shapes is dropped with a warning.
  - **Colour names:** only the app's own names are applied.
  - **Values:** only the formats above. `url()`, `expression()`, `@import` and similar are never read.
  - **Theme names:** letters, digits, spaces and `- _ . , ' ( ) & + ! #` only, up to 48 characters. Names are
    shown as plain text, never as HTML.
  - **`base`, `toggle` and file names:** lowercase letters, digits, `-` and `_` only, so they can't be paths.
  - **Files:** only regular files are read, never symlinks. Each is limited to 64 KB, 400 declarations and
    16 levels of `var()`.

**Text smoothing** follows the OS. On Windows, text uses ClearType's sub-pixel rendering when ClearType is
on (Settings › Personalization › Fonts, or "Adjust ClearType text"), at the contrast you tuned it to. On macOS
and Linux it uses greyscale smoothing. Java falls back to greyscale in two places, regardless of setting: inside
see-through windows (only used for a popup too big to fit inside the app window), and for the moment a theme
switch fades. To force a mode, start the app with `-Dawt.useSystemAAFontSettings=lcd` (or `gasp`, `on`, `off`).

**Fonts.** Settings › General has *Interface font* and *Code font* pickers, each listing your installed
fonts previewed in themselves; the code list only offers fonts whose letters are all the same width, and
symbol fonts are left out. Fonts made to be easier to read (Atkinson Hyperlegible, Lexend, OpenDyslexic,
Andika, Verdana, Tahoma; for code Atkinson Hyperlegible Mono, OpenDyslexicMono, Intel One Mono, JetBrains Mono…)
are listed first when installed. Changes apply instantly and keep the zoom level. *Default* uses:

The UI font is San Francisco on macOS and Segoe UI on Windows; on Linux it's Segoe UI if installed, else
Inter, Cantarell, Ubuntu, Noto Sans, Open Sans, DejaVu Sans or Liberation Sans. Text is antialiased with the
desktop's own hints. Code uses SF Mono, Menlo, JetBrains Mono, Cascadia Mono, Consolas or DejaVu Sans Mono.

## Credits

**Made by** [udaysr71](https://github.com/udaysr71), with [Claude](https://claude.ai) by
[Anthropic](https://www.anthropic.com) as a pair programmer. Claude wrote most of the code, tests and
docs; the ideas, direction, testing on real machines and every "this still looks off" came from a human.

**Inspiration (no code copied, nothing bundled):**

- [FlatLaf](https://www.formdev.com/flatlaf/) by FormDev Software (Apache License 2.0). The look and
  feel's visual design is modelled on it; the implementation is written from scratch on Swing's Basic
  look and feel. See `NOTICE`.
- [Apple Human Interface Guidelines](https://developer.apple.com/design/human-interface-guidelines/)
  for spacing, menus, focus behaviour and motion.
- [Practical UI](https://www.practical-ui.com/) by Adham Dannaway, and a UI/UX design guide, used for
  the review passes on layout, contrast, wording and actions.
- [Solarized](https://ethanschoonover.com/solarized/) by Ethan Schoonover (MIT) for the Solarized Sepia
  and Solarized Dark colour schemes, adjusted where needed to meet WCAG AA contrast.
- [Postman](https://www.postman.com/) and [Insomnia](https://insomnia.rest/) for what a REST client is
  expected to do.

**Readable fonts.** The font pickers list these first if you've installed them; none are shipped with
the app: [Atkinson Hyperlegible](https://www.brailleinstitute.org/freefont/) (Braille Institute),
[Lexend](https://www.lexend.com/), [OpenDyslexic](https://opendyslexic.org/) (Abbie Gonzalez) and
[JetBrains Mono](https://www.jetbrains.com/lp/mono/).

**Platform.** Built entirely on the JDK (Swing, Java2D, `java.net.http`). Thanks to the OpenJDK
project for a standard library big enough to make zero dependencies possible.

## License

[MIT](LICENSE). Fork it, change it, ship it; no support or warranty is offered.
