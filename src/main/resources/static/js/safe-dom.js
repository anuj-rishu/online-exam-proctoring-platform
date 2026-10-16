(function (global) {
    'use strict';

    const HTML_ENTITIES = {
        '&': '&amp;',
        '<': '&lt;',
        '>': '&gt;',
        '"': '&quot;',
        "'": '&#039;'
    };

    function escapeHtml(value) {
        if (value === null || value === undefined) return '';
        return String(value).replace(/[&<>"']/g, (ch) => HTML_ENTITIES[ch]);
    }

    function isSafeImageData(value) {
        return typeof value === 'string'
            && value.length <= 400000
            && /^data:image\/(jpeg|png);base64,[A-Za-z0-9+/]+={0,2}$/.test(value);
    }

    global.escapeHtml = escapeHtml;
    global.isSafeImageData = isSafeImageData;
})(window);
