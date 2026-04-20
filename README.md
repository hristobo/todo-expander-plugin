# TODO Expander

An IntelliJ IDEA plugin that turns `// TODO` comments into real code using an AI model via the [OpenRouter](https://openrouter.ai) API.

<!-- Plugin description -->
Place your cursor on any `TODO` comment, invoke **Expand TODO with AI** from the editor context menu or the Tools menu, and the plugin will insert a concrete code implementation directly below the comment — preserving your indentation and using surrounding code as context.
<!-- Plugin description end -->

## What it does

1. Detects the `TODO` comment on the current editor line.
2. Captures ±5 lines of surrounding code as context.
3. Sends both to the OpenRouter chat completions API (model: `nvidia/nemotron-3-super-120b-a12b:free`).
4. Inserts the generated code immediately below the TODO line, indented to match.

The action is accessible from two places:

- **Right-click → Expand TODO with AI** (editor context menu)
- **Tools → Expand TODO with AI** (menu bar)

## Why it's useful

Filling in TODO stubs is mechanical work. This plugin offloads the first draft: describe intent in a comment, trigger the action, and get an implementation in place without leaving the editor or copy-pasting from a chat window.

## Requirements

- IntelliJ IDEA (any edition, 2025.2+)
- JDK 17+
- An [OpenRouter](https://openrouter.ai) account and API key

## Setting the API key

The plugin reads `OPENROUTER_API_KEY` from the environment. Set it before launching the IDE:

**macOS / Linux**
```bash
export OPENROUTER_API_KEY=sk-or-...
open -a "IntelliJ IDEA"   # launch from the same shell so the IDE inherits it
```

**Windows (PowerShell)**
```powershell
$env:OPENROUTER_API_KEY = "sk-or-..."
# then launch IntelliJ from the same shell
```

For a permanent setup, add the export to your shell profile (`~/.zshrc`, `~/.bashrc`, etc.) or add it via **Help → Edit Custom VM Options** using `-Denv.OPENROUTER_API_KEY=...`, or through the system environment variables dialog.

## Build and run

```bash
# Launch a sandbox IDE instance with the plugin loaded
./gradlew runIde

# Build the distributable .zip
./gradlew buildPlugin

# Run tests
./gradlew test
```

The built plugin archive is placed in `build/distributions/`. To install it manually: **Settings → Plugins → ⚙ → Install Plugin from Disk…**.

## Code structure

```
src/main/kotlin/org/jetbrains/plugins/template/
├── actions/
│   └── TodoExpanderAction.kt   # AnAction: reads the editor, validates input,
│                               # dispatches background task, writes result
└── api/
    └── OpenRouterClient.kt     # HTTP call to OpenRouter, JSON building/parsing
```

### Key decisions

**No third-party HTTP or JSON libraries.** The plugin uses only `java.net.HttpURLConnection` and hand-rolled JSON serialisation/deserialisation. This avoids classloader conflicts with the IntelliJ platform and keeps the plugin footprint minimal.

**Background task via `ProgressManager`.** The network call runs in a `Task.Backgroundable` so it never blocks the Event Dispatch Thread. The document write is marshalled back to the EDT with `invokeLater` + `WriteCommandAction`, which is the correct IntelliJ threading model and makes the action undoable.

**Indentation preservation.** Leading whitespace is captured from the TODO line before the API call and re-applied to every line of the response, so generated code aligns naturally regardless of nesting depth.

**Narrow context window.** Five lines above and below the TODO are sent with the prompt — enough to give the model language and type context without exceeding token limits on the free-tier model.

**Free model on OpenRouter.** The default model (`nvidia/nemotron-3-super-120b-a12b:free`) has no per-request cost. To use a different model, change the `MODEL` constant in `OpenRouterClient.kt`.
