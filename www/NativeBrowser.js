var exec = require('cordova/exec');

var NativeBrowser = {

    /**
     * Open the native browser overlay.
     * @param {string}   url
     * @param {number}   x
     * @param {number}   y
     * @param {number}   width
     * @param {number}   height
     * @param {function} onEvent  - called for every browser event {type, url, title, favicon, tabId}
     * @param {function} onError
     */
    open: function (url, x, y, width, height, onEvent, onError) {
        exec(onEvent, onError, 'NativeBrowser', 'open', [url, x, y, width, height]);
    },

    /**
     * Navigate the active tab to a URL.
     */
    navigate: function (url, onSuccess, onError) {
        exec(onSuccess, onError, 'NativeBrowser', 'navigate', [url]);
    },

    /**
     * Go back in history.
     */
    goBack: function (onSuccess, onError) {
        exec(onSuccess, onError, 'NativeBrowser', 'goBack', []);
    },

    /**
     * Go forward in history.
     */
    goForward: function (onSuccess, onError) {
        exec(onSuccess, onError, 'NativeBrowser', 'goForward', []);
    },

    /**
     * Reload current page.
     */
    reload: function (onSuccess, onError) {
        exec(onSuccess, onError, 'NativeBrowser', 'reload', []);
    },

    /**
     * Move / resize the overlay (call this during window drag & resize).
     */
    setRect: function (x, y, width, height, onSuccess, onError) {
        exec(onSuccess, onError, 'NativeBrowser', 'setRect', [x, y, width, height]);
    },

    /**
     * Show the overlay (e.g. after minimize restore).
     */
    show: function (onSuccess, onError) {
        exec(onSuccess, onError, 'NativeBrowser', 'show', []);
    },

    /**
     * Hide the overlay (e.g. window minimized or behind another window).
     */
    hide: function (onSuccess, onError) {
        exec(onSuccess, onError, 'NativeBrowser', 'hide', []);
    },

    /**
     * Close and destroy the overlay entirely.
     */
    close: function (onSuccess, onError) {
        exec(onSuccess, onError, 'NativeBrowser', 'close', []);
    },

    /**
     * Open a new tab.
     */
    newTab: function (url, onSuccess, onError) {
        exec(onSuccess, onError, 'NativeBrowser', 'newTab', [url || 'https://www.google.com']);
    },

    /**
     * Switch to a tab by id.
     */
    switchTab: function (tabId, onSuccess, onError) {
        exec(onSuccess, onError, 'NativeBrowser', 'switchTab', [tabId]);
    },

    /**
     * Close a tab by id.
     */
    closeTab: function (tabId, onSuccess, onError) {
        exec(onSuccess, onError, 'NativeBrowser', 'closeTab', [tabId]);
    }
};

module.exports = NativeBrowser;