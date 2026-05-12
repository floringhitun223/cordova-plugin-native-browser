# cordova-plugin-native-browser

A Cordova plugin for Android that renders a native WebView overlay with full tab management, file downloads, navigation controls, and video fullscreen support.

## Supported Platforms

- Android (API 21+)

## Installation

```bash
cordova plugin add cordova-plugin-native-browser
```

Or from GitHub:

```bash
cordova plugin add https://github.com/floringhitun223/cordova-plugin-native-browser
```

---

## Usage

### 1. Open the browser

```javascript
cordova.plugins.NativeBrowser.open(
    'https://www.google.com',
    0,    // x (dp)
    100,  // y (dp)
    400,  // width (dp)
    600,  // height (dp)
    function(event) {
        console.log('Event:', event.type, event);
    },
    function(error) {
        console.error('Error:', error);
    }
);
```

---

### 2. Navigate to a URL

```javascript
cordova.plugins.NativeBrowser.navigate('https://github.com', null, null);
```

---

### 3. Navigation controls

```javascript
cordova.plugins.NativeBrowser.goBack(null, null);
cordova.plugins.NativeBrowser.goForward(null, null);
cordova.plugins.NativeBrowser.reload(null, null);
```

---

### 4. Reposition / resize

```javascript
cordova.plugins.NativeBrowser.setRect(0, 50, 400, 700, null, null);
```

---

### 5. Show / Hide

```javascript
cordova.plugins.NativeBrowser.show(null, null);
cordova.plugins.NativeBrowser.hide(null, null);
```

---

### 6. Close

Destroys the overlay and all tabs completely.

```javascript
cordova.plugins.NativeBrowser.close(null, null);
```

---

### 7. Tab management

```javascript
// Open a new tab
cordova.plugins.NativeBrowser.newTab('https://youtube.com', null, null);

// Switch to a tab by ID
cordova.plugins.NativeBrowser.switchTab('tab-2', null, null);

// Close a tab by ID
cordova.plugins.NativeBrowser.closeTab('tab-1', null, null);
```

---

## Events

All events are delivered to the `onEvent` callback passed to `open()`.

| `event.type` | Description |
|---|---|
| `tabCreated` | A new tab was opened |
| `tabActivated` | A tab was switched to |
| `tabClosed` | A tab was closed |
| `pageStarted` | Page started loading |
| `pageFinished` | Page finished loading |
| `pageError` | Page failed to load |
| `titleChanged` | Page title changed |
| `navState` | Back/forward state changed |
| `newTabRequested` | A link requested a new tab (long-press or target=_blank) |
| `downloadStarted` | A file download started |
| `downloadProgress` | Download progress update |
| `downloadFinished` | Download completed |
| `downloadError` | Download failed |

### Event object fields

```javascript
{
    type: "pageFinished",
    tabId: "tab-1",
    url: "https://example.com",
    title: "Example",         // titleChanged only
    favicon: null,

    // navState only
    canGoBack: true,
    canGoForward: false,

    // pageError only
    errorCode: -2,
    description: "net::ERR_NAME_NOT_RESOLVED",

    // download events only
    mimetype: "application/pdf",
    contentLength: 204800,
    progress: 75,             // 0-100, -1 if unknown
    path: "/storage/..."      // downloadFinished only
}
```

---

## Full Example

```javascript
document.addEventListener('deviceready', function() {

    cordova.plugins.NativeBrowser.open(
        'https://www.google.com',
        0, 100, window.screen.width, window.screen.height - 100,
        function(event) {

            switch (event.type) {
                case 'pageFinished':
                    console.log('Loaded:', event.url);
                    break;
                case 'pageError':
                    console.warn('Error', event.errorCode, event.description);
                    break;
                case 'titleChanged':
                    document.title = event.title;
                    break;
                case 'navState':
                    backBtn.disabled    = !event.canGoBack;
                    forwardBtn.disabled = !event.canGoForward;
                    break;
                case 'newTabRequested':
                    cordova.plugins.NativeBrowser.newTab(event.url, null, null);
                    break;
                case 'downloadFinished':
                    alert('Downloaded: ' + event.path);
                    break;
            }
        },
        function(err) {
            console.error('Browser error:', err);
        }
    );

}, false);
```

---

## Features

- Native Android WebView overlay (renders above Cordova)
- Full tab management (open, switch, close)
- File downloads via Android DownloadManager with progress events
- Long-press context menu for links and images (open in new tab, save, copy URL)
- Video fullscreen support
- target=_blank and window.open() interception
- SSL error handling
- Back/forward navigation state
- Desktop user agent by default
- Zoom support

---

## Notes

- Android only.
- The overlay renders **above** the Cordova WebView using a native `FrameLayout`.
- Coordinates and sizes are in **dp** (density-independent pixels).

---

## License

MIT
