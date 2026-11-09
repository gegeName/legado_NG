// Provider: the legacy Volcengine translation webpage protocol only.
function synthesize(text, voice, params, options, ctx) {
    if (!voice || !voice.extra || !voice.extra.voiceCode) throw new Error('火山音色编号为空');
    return {
        url: __ENDPOINT__, method: 'POST', headers: {Accept: 'application/json'},
        requestContentType: 'application/json',
        body: {language: 'zh', speaker: String(voice.extra.voiceCode), text: text},
        responseType: 'json', audioExtract: 'audio.data', audioEncoding: 'base64',
        audioContentType: 'audio/wav', timeout: 30,
        responseValidation: {statusPath: 'base_resp.status_code', successValue: '0', messagePath: 'base_resp.status_message'}
    };
}
