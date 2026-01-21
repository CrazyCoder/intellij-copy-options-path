# Copy Option Path

An IntelliJ Platform plugin that adds a **Copy Option Path** action to JetBrains IDEs. This action copies the full navigation path to any UI option in IDE dialogs (such as Settings, Project Structure, etc.) to the clipboard.

## Features

- Copy full path to any option in Settings dialogs (e.g., `Settings | Editor | Code Style | Java`)
- Works with Project Structure dialog in IntelliJ IDEA
- Supports copying via **Ctrl+Click** (or **Cmd+Click** on macOS)
- Handles tree structures, tabs, titled panels, buttons, and labels

## Installation

### From JetBrains Marketplace

1. Open your JetBrains IDE
2. Go to **Settings | Plugins | Marketplace**
3. Search for "Copy Option Path"
4. Click **Install**

### Manual Installation

1. Download the plugin ZIP from [Releases](https://github.com/CrazyCoder/intellij-copy-options-path/releases)
2. Go to **Settings | Plugins | ⚙️ | Install Plugin from Disk...**
3. Select the downloaded ZIP file

## Usage

### Quick Copy (Recommended)

**Ctrl+Click** (or **Cmd+Click** on macOS) on any option, label, button, or setting in a dialog to instantly copy its full path to the clipboard.

### Context Menu

1. Open any IDE dialog (Settings, Project Structure, etc.)
2. Navigate to the desired option
3. Right-click on the option
4. Select **Copy Option Path** from the context menu

### Example Output

When you Ctrl+Click on the "Insert imports on paste" dropdown in the Java section of Auto Import settings:

```
Settings | Editor | General | Auto Import | Java | Insert imports on paste:
```

## Compatibility

- **Minimum IDE Version:** 2025.1+
- **Supported IDEs:** All JetBrains IDEs (IntelliJ IDEA, PyCharm, WebStorm, GoLand, etc.)

## Recent Fixes

This fork includes important fixes for compatibility with modern IDE versions:

- **Added settings group detection** — The plugin now captures titled separator groups (like "Java", "Kotlin", "Groovy" sections) that appear in Settings panels. For example, clicking on "Insert imports on paste:" in the Java section of Auto Import settings now produces `Settings | Editor | General | Auto Import | Java | Insert imports on paste:` instead of omitting the "Java" group name.

- **Fixed Settings dialog breadcrumb extraction** — The plugin now correctly copies the full path in Settings dialogs (e.g., `Settings | Editor | General | Auto Import`) instead of just the final element. This was broken due to internal API changes in recent IDE versions.

- **Fixed Project Structure dialog paths** — Added support for extracting section names (like "Project Settings", "Platform Settings") in the Project Structure dialog, providing complete paths such as `Project Structure | Project Settings | Project | Language level:`.

- **Updated for 2025.1+ compatibility** — Refactored internal reflection-based code to work with the latest IntelliJ Platform architecture changes.

## Use Cases

- **Documentation:** Quickly reference exact settings paths in documentation or tutorials
- **Support:** Share precise setting locations when helping colleagues or reporting issues
- **Configuration:** Document your IDE setup for team onboarding or backup purposes

## License

This project is open source. See the repository for license details.

## Contributors

- **Serge Baranov** — Current maintainer
- **Andrey Dernov** — Original author
