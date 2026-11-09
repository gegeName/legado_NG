function synthesize(text, voice, params, options, ctx) {
    if (!voice || !voice.extra) throw new Error('音色不存在');
    var extra = voice.extra;
    function clamp(value) { return Math.max(1, Math.min(100, Math.round(value))); }
    var speed = clamp(30 * params.speed / 50 * extra.speed);
    var pitch = clamp(params.pitch * extra.pitch);
    var volume = clamp(params.volume * extra.volume);
    var values = {
        voiceCode: encodeURIComponent(String(extra.sourceCode)),
        speakText: encodeURIComponent(text),
        'java.encodeURI(speakText)': encodeURIComponent(text),
        'java.encodeURIComponent(speakText)': encodeURIComponent(text),
        speakSpeed: String(speed), speakPitch: String(pitch), speakVolume: String(volume),
        '2-speakSpeed*0.015': String(2 - speed * 0.015), 'speakVolume*4': String(volume * 4)
    };
    var url = __TEMPLATE__.replace(/\{\{([\s\S]*?)}}/g, function(all, expr) {
        var key = expr.replace(/\s+/g, '');
        if (!Object.prototype.hasOwnProperty.call(values, key)) throw new Error('不支持的占位符');
        return values[key];
    });
    if (url.indexOf('{{') >= 0 || url.indexOf('}}') >= 0) throw new Error('HTTP 模板占位符不完整');
    return {url: url, method: 'GET', responseType: 'audio', timeout: 30,
        audioContentType: 'audio/.*|application/octet-stream'};
}
