# Code Editor for Android

A VS Code-like code editor for Android with SSH remote server connectivity and full extension support.

## Features

- **Full VS Code Experience**: Uses code-server to provide the complete VS Code interface on Android
- **SSH Remote Connection**: Connect to any remote server via SSH (password or SSH key authentication)
- **VS Code Extensions**: Install and use all VS Code extensions via Open VSX registry on the remote server
- **Integrated Terminal**: Built-in SSH terminal with extra keys bar (Ctrl, Tab, Esc, arrows)
- **Connection Manager**: Save, edit, and manage multiple server connections
- **SSH Key Management**: Generate RSA/Ed25519 keys directly in the app
- **Auto code-server Setup**: Automatically installs and starts code-server on your remote server
- **Secure**: Passwords encrypted with Android Keystore, SSH tunnel for all traffic
- **Foreground Service**: Persistent SSH connections that survive screen rotation and backgrounding

## Architecture

```
Android App → SSH Tunnel → Remote Server (code-server)
                           └→ Full VS Code + Extensions
```

The app connects to your remote server via SSH, automatically sets up code-server if not installed, creates an SSH tunnel, and displays the VS Code UI in a WebView. All extensions and processing run on the remote server.

## Requirements

- Android 8.0 (API 26) or higher
- A remote Linux server with SSH access
- Internet connection

## Build

```bash
./gradlew assembleDebug
```

The APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

## Tech Stack

- **Kotlin** with Coroutines
- **SSHJ** for SSH connections and port forwarding
- **Room** for connection persistence
- **Material Design 3** for UI
- **WebView** for code-server rendering
- **BouncyCastle** for SSH key generation
- **Android Keystore** for secure credential storage

## Usage

1. Open the app and tap **+** to add a new server connection
2. Enter your server details (host, port, username, password/key)
3. Tap the connection to open the VS Code editor
4. Or tap the terminal icon for a standalone SSH terminal
5. code-server will be automatically installed on first connection

## Project Structure

```
app/src/main/java/com/codeeditor/app/
├── CodeEditorApp.kt          # Application class
├── MainActivity.kt           # Connection list screen
├── ssh/                      # SSH management
│   ├── SSHManager.kt         # Core SSH client
│   ├── SSHKeyManager.kt      # Key generation/storage
│   ├── PortForwarder.kt      # SSH tunnel
│   ├── RemoteCommandRunner.kt# Remote command execution
│   └── SSHConnectionService.kt # Foreground service
├── editor/                   # VS Code editor
│   ├── EditorActivity.kt     # WebView + code-server
│   ├── CodeServerSetup.kt    # Auto-install code-server
│   └── EditorWebViewClient.kt
├── terminal/                 # SSH terminal
│   ├── TerminalActivity.kt
│   └── TerminalSession.kt
├── connections/              # Connection management
│   ├── ConnectionEntity.kt   # Room entity
│   ├── ConnectionDao.kt      # Database operations
│   ├── ConnectionDatabase.kt
│   ├── ConnectionAdapter.kt
│   └── ConnectionEditActivity.kt
├── settings/
│   └── SettingsActivity.kt
└── utils/
    ├── SecurityUtils.kt      # Encryption
    └── Constants.kt
```
