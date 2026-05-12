# cordova-plugin-native-browser

A Cordova plugin for Android that renders a **native WebView overlay** directly above your Cordova app — with full tab management, file downloads, navigation history, SSL handling, video fullscreen, and long-press context menus.

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

## How it works

The plugin creates a native Android `FrameLayout` that sits **above** your Cordova WebView. This means the browser renders at the OS level — not inside an iframe — giving you full native performance, real downloads, and proper video/media support.

Your JS layer controls the overlay via commands and receives all browser activity back through a single persistent event callback.

---

## API

### `open(url, x, y, width, height, onEvent, onError)`

Opens the browser overlay. All subsequent events are delivered to `onEvent`.

```javascript
cordova.plugins.NativeBrowser.open(
    'https://www.google.com',
    0,                          // x in dp
    100,                        // y in dp
    window.screen.width,        // width in dp
    window.screen.height - 100, // height in dp
    function(event) {
        handleBrowserEvent(event);
    },
    function(error) {
        console.error('Fatal error:', error);
    }
);
```

> **Tip:** Call `open()` only once. If the overlay is already open, it repositions and navigates without creating a second instance.

---

### `navigate(url, success, error)`

Navigate the active tab to a new URL.

```javascript
cordova.plugins.NativeBrowser.navigate('https://github.com', null, null);
```

---

### `goBack(success, error)` / `goForward(success, error)`

```javascript
cordova.plugins.NativeBrowser.goBack(null, null);
cordova.plugins.NativeBrowser.goForward(null, null);
```

> Track `canGoBack` / `canGoForward` from `navState` events to enable/disable your buttons.

---

### `reload(success, error)`

```javascript
cordova.plugins.NativeBrowser.reload(null, null);
```

---

### `setRect(x, y, width, height, success, error)`

Reposition or resize the overlay at runtime.

```javascript
cordova.plugins.NativeBrowser.setRect(0, 56, 400, 700, null, null);
```

---

### `show(success, error)` / `hide(success, error)`

Show or hide the overlay without destroying it. Use `hide()` when displaying HTML UI on top (suggestions, error screens).

```javascript
cordova.plugins.NativeBrowser.hide(null, null); // show JS UI on top
cordova.plugins.NativeBrowser.show(null, null); // restore native view
```

---

### `close(success, error)`

Destroys all tabs and removes the overlay completely.

```javascript
cordova.plugins.NativeBrowser.close(null, null);
```

---

### `newTab(url, success, error)`

Opens a new tab.

```javascript
cordova.plugins.NativeBrowser.newTab('https://youtube.com', null, null);
```

---

### `switchTab(nativeTabId, success, error)`

Switch the active native tab. Use the `tabId` from `tabCreated` events.

```javascript
cordova.plugins.NativeBrowser.switchTab('tab-2', null, null);
```

---

### `closeTab(nativeTabId, success, error)`

Close a tab by its native tab ID.

```javascript
cordova.plugins.NativeBrowser.closeTab('tab-1', null, null);
```

---

## Events

All events arrive in the `onEvent` callback passed to `open()`.

```javascript
function handleBrowserEvent(event) {
    switch (event.type) {
        case 'tabCreated':       break;
        case 'tabActivated':     break;
        case 'tabClosed':        break;
        case 'pageStarted':      break;
        case 'pageFinished':     break;
        case 'pageError':        break;
        case 'titleChanged':     break;
        case 'navState':         break;
        case 'newTabRequested':  break;
        case 'downloadStarted':  break;
        case 'downloadProgress': break;
        case 'downloadFinished': break;
        case 'downloadError':    break;
    }
}
```

### Event reference

| `event.type` | Key fields | Description |
|---|---|---|
| `tabCreated` | `tabId` | A new native tab was created. Map this to your JS tab ID. |
| `tabActivated` | `tabId`, `url` | A tab was brought to the front. |
| `tabClosed` | `tabId` | A tab was destroyed. |
| `pageStarted` | `tabId`, `url` | Page started loading. |
| `pageFinished` | `tabId`, `url` | Page fully loaded. |
| `pageError` | `tabId`, `url`, `errorCode`, `description` | Page failed to load. |
| `titleChanged` | `tabId`, `title` | Page `<title>` changed. |
| `navState` | `tabId`, `canGoBack`, `canGoForward` | Back/forward availability changed. |
| `newTabRequested` | `tabId`, `url` | `target="_blank"` or `window.open()` was triggered. |
| `downloadStarted` | `url`, `mimetype`, `contentLength`, `path` | Download began. |
| `downloadProgress` | `url`, `progress` | Download progress (0–100, -1 if unknown). Fires ~every 1s. |
| `downloadFinished` | `url`, `mimetype`, `path` | Download completed. `path` is the local file URI. |
| `downloadError` | `url`, `mimetype`, `path` | Download failed. `path` contains the error reason. |

---

## Use cases

### Back / Forward buttons

```javascript
const btnBack    = document.getElementById('btn-back');
const btnForward = document.getElementById('btn-forward');

// In your event handler:
if (event.type === 'navState') {
    btnBack.disabled    = !event.canGoBack;
    btnForward.disabled = !event.canGoForward;
}

btnBack.addEventListener('click',    () => cordova.plugins.NativeBrowser.goBack(null, null));
btnForward.addEventListener('click', () => cordova.plugins.NativeBrowser.goForward(null, null));
```

---

### Progress bar

```javascript
function showProgress() {
    track.style.width = '0%';
    track.style.transition = 'width 2s ease';
    setTimeout(() => { track.style.width = '80%'; }, 50);
}

function hideProgress() {
    track.style.width = '100%';
    setTimeout(() => { track.style.width = '0%'; }, 300);
}

if (event.type === 'pageStarted')  showProgress();
if (event.type === 'pageFinished') hideProgress();
if (event.type === 'pageError')    hideProgress();
```

---

### Tab management

`newTab()` fires a `tabCreated` event asynchronously. Store `pendingNative: true` on your JS tab and link it when the event arrives:

```javascript
const tabsState = {};
let activeTabId = null;

function addTab(url) {
    const tabId = 'tab-' + Date.now();
    tabsState[tabId] = { url, nativeTabId: null, pendingNative: true };
    cordova.plugins.NativeBrowser.newTab(url, null, null);
}

function removeTab(tabId) {
    const state = tabsState[tabId];
    if (state?.nativeTabId) {
        cordova.plugins.NativeBrowser.closeTab(state.nativeTabId, null, null);
    }
    delete tabsState[tabId];
}

// In your event handler:
if (event.type === 'tabCreated') {
    const pending = Object.keys(tabsState).find(id => tabsState[id].pendingNative);
    if (pending) {
        tabsState[pending].nativeTabId  = event.tabId;
        tabsState[pending].pendingNative = false;
    }
}

if (event.type === 'titleChanged') {
    const jsTabId = Object.keys(tabsState)
        .find(id => tabsState[id].nativeTabId === event.tabId);
    if (jsTabId) {
        tabsState[jsTabId].name = event.title;
        updateTabChipLabel(jsTabId, event.title);
    }
}
```

---

### Handle target=_blank / window.open()

```javascript
if (event.type === 'newTabRequested') {
    addTab(event.url);
}
```

---

### Error overlay with retry

Map Android error codes to user-friendly messages:

```javascript
const ERROR_TYPES = {
    noInternet: { icon: '📡', title: 'No Internet Connection',   subtitle: 'Check your Wi-Fi or data and try again.' },
    refused:    { icon: '🚫', title: 'Connection Refused',       subtitle: 'The server may be down or blocking access.' },
    timeout:    { icon: '⏱',  title: 'Connection Timed Out',     subtitle: 'The server took too long to respond.' },
    ssl:        { icon: '🔒', title: 'Secure Connection Failed', subtitle: 'This page has a security issue.' },
    notFound:   { icon: '🔍', title: 'Page Not Found',           subtitle: 'This address does not exist or has moved.' },
    generic:    { icon: '⚠',  title: 'Page Unavailable',         subtitle: 'This page cannot be loaded right now.' },
};

function errorCodeToType(code) {
    switch (code) {
        case -2: case -6: case -8:  return 'noInternet';
        case -5:                    return 'refused';
        case -7:                    return 'timeout';
        case -9: case -12:          return 'ssl';
        case -10: case -11:         return 'notFound';
        default:                    return 'generic';
    }
}

if (event.type === 'pageError') {
    const type = errorCodeToType(event.errorCode);
    const cfg  = ERROR_TYPES[type];

    errorOverlay.style.display = 'flex';
    errorIcon.textContent      = cfg.icon;
    errorTitle.textContent     = cfg.title;
    errorSubtitle.textContent  = cfg.subtitle;

    // Hide native view so error overlay is visible
    cordova.plugins.NativeBrowser.hide(null, null);
}

retryBtn.addEventListener('click', () => {
    errorOverlay.style.display = 'none';
    cordova.plugins.NativeBrowser.show(null, null);
    cordova.plugins.NativeBrowser.reload(null, null);
});
```

---

### File downloads with progress spinner

```javascript
let activeDownloads = 0;

if (event.type === 'downloadStarted') {
    activeDownloads++;
    showDownloadSpinner();
    showToast('Downloading...');
}

if (event.type === 'downloadProgress') {
    updateDownloadArc(event.progress); // update SVG arc or progress bar
}

if (event.type === 'downloadFinished') {
    activeDownloads = Math.max(0, activeDownloads - 1);
    if (activeDownloads === 0) hideDownloadSpinner();
    showToast('✓ Saved to Downloads: ' + event.path);
}

if (event.type === 'downloadError') {
    activeDownloads = Math.max(0, activeDownloads - 1);
    if (activeDownloads === 0) hideDownloadSpinner();
    showToast('✗ Download failed: ' + event.path);
}
```

---

### Suggestion panel (hide native while showing JS UI)

```javascript
let suggPanelPointerDown = false;
suggPanel.addEventListener('pointerdown',   () => { suggPanelPointerDown = true;  });
suggPanel.addEventListener('pointerup',     () => { suggPanelPointerDown = false; });
suggPanel.addEventListener('pointercancel', () => { suggPanelPointerDown = false; });

function showSuggestions() {
    cordova.plugins.NativeBrowser.hide(null, null);
    suggPanel.style.display = 'block';
}

function hideSuggestions() {
    suggPanel.style.display = 'none';
    cordova.plugins.NativeBrowser.show(null, null);
}

urlInput.addEventListener('focus', showSuggestions);
urlInput.addEventListener('blur', () => {
    setTimeout(() => {
        if (!suggPanelPointerDown) hideSuggestions();
    }, 150); // wait to see if user tapped a suggestion
});
```

---

### Kiosk mode (locked to one URL, no browser chrome)

```javascript
cordova.plugins.NativeBrowser.open(
    'https://your-internal-app.com',
    0, 0, screen.width, screen.height,
    handleBrowserEvent,
    null
);

// Hide all browser UI
tabsBar.style.display      = 'none';
urlBar.style.display       = 'none';
bookmarkBar.style.display  = 'none';
```

---

### Resize on layout change

When a toolbar appears/disappears or the keyboard opens:

```javascript
function onToolbarToggle(toolbarVisible) {
    const topOffset = toolbarVisible ? 56 : 0;
    cordova.plugins.NativeBrowser.setRect(
        0, topOffset,
        screen.width, screen.height - topOffset,
        null, null
    );
}
```

---

### Android hardware back button

```javascript
document.addEventListener('backbutton', function(e) {
    const state = tabsState[activeTabId];
    if (state?.canGoBack) {
        e.preventDefault();
        cordova.plugins.NativeBrowser.goBack(null, null);
    }
}, false);
```

---

### Record browsing history

```javascript
if (event.type === 'pageFinished') {
    HistoryDB.record({
        url:       event.url,
        name:      currentPageTitle,
        favicon:   currentFavicon,
        visitedAt: Date.now()
    });
}
```

---

### URL normalization

The plugin normalizes URLs internally (adds `https://`, converts bare words to Google searches). Your JS layer can mirror this:

```javascript
function normalizeUrl(val) {
    val = (val || '').trim();
    if (!val) return 'https://www.google.com';
    if (/^https?:\/\//i.test(val)) return val;
    if (/^[a-z0-9\-]+(\.[a-z0-9\-]+)+/i.test(val)) return 'https://' + val;
    return 'https://www.google.com/search?q=' + encodeURIComponent(val);
}
```

---

## Event object shape

```javascript
{
    // Always present
    type:  "pageFinished",
    tabId: "tab-1",
    url:   "https://example.com",

    // titleChanged only
    title: "Page Title",

    // navState only
    canGoBack:    true,
    canGoForward: false,

    // pageError only
    errorCode:   -2,
    description: "net::ERR_INTERNET_DISCONNECTED",

    // download events
    mimetype:      "application/pdf",
    disposition:   "attachment; filename=report.pdf",
    contentLength: 204800,
    progress:      75,       // 0–100, or -1 if total size unknown
    path:          "file:///storage/emulated/0/Download/report.pdf"
}
```

---

## Features

- Native Android WebView overlay (above Cordova, not an iframe)
- Full tab management with async native tab ID mapping
- File downloads via Android `DownloadManager` with 1-second progress polling
- Long-press context menu — open image/link in new tab, save image, copy URL
- Video fullscreen (`onShowCustomView` / `onHideCustomView`)
- `target="_blank"` and `window.open()` interception
- SSL error handling with event reporting
- Per-tab back/forward state via `navState` events
- Desktop user agent by default (Chrome 124 on Windows)
- Pinch-to-zoom (controls hidden)
- Mixed content allowed

---

## Notes

- Android only.
- The overlay renders **above** the Cordova WebView — it is not an iframe or InAppBrowser.
- All coordinates and sizes are in **dp** (density-independent pixels).
- Always call `hide()` before showing any HTML overlay so the native view doesn't cover it.
- `newTab()` is async — always wait for `tabCreated` to get the native tab ID before calling `switchTab()` or `closeTab()`.

---

## License

MIT
