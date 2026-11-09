// Feasibility probe only. Not installed as an application engine.
// AUDIT_KEY comes from the local legacy protocol reference at runtime.
var SIGN_URL = 'https://peiyin.xunfei.cn/web-server/1.0/works_synth_sign';
var SYNTH_URL = 'https://peiyin.xunfei.cn/synth';
var UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36';
var XF_HOST = java;

function xfCrypto(key) {
    // Use JCA with an explicit AES key algorithm; no extra crypto provider is needed.
    var Cipher = Packages.javax.crypto.Cipher;
    var SecretKeySpec = Packages.javax.crypto.spec.SecretKeySpec;
    var JString = Packages.java.lang.String;
    var Base64 = Packages.cn.hutool.core.codec.Base64;
    var secret = new SecretKeySpec(new JString(key).getBytes('UTF-8'), 'AES');
    return {
        encryptBase64: function(text) {
            var cipher = Cipher.getInstance('AES/ECB/PKCS5Padding');
            cipher.init(Cipher.ENCRYPT_MODE, secret);
            return String(Base64.encode(cipher.doFinal(new JString(text).getBytes('UTF-8'))));
        },
        decryptStr: function(text) {
            var cipher = Cipher.getInstance('AES/ECB/PKCS5Padding');
            cipher.init(Cipher.DECRYPT_MODE, secret);
            return String(new JString(cipher.doFinal(Base64.decode(text)), 'UTF-8'));
        }
    };
}

function synthesize(text, voice, params, options, ctx) {
    var extra = voice.extra;
    var vid = String(voice.style_value || extra.voiceCode || '');
    if (!/^\d+$/.test(vid)) throw new Error('Unresolved provider voice ID');
    var crypto = xfCrypto(AUDIT_KEY);
    var payload = JSON.stringify({synth_text_hash_code: String(XF_HOST.md5Encode(text))});
    var req = String(crypto.encryptBase64(payload));
    var raw = String(XF_HOST.ajax(SIGN_URL + ',' + JSON.stringify({
        method: 'POST', headers: {'Content-Type': 'application/json', 'User-Agent': UA},
        body: JSON.stringify({req: req})
    }), 15000));
    var signed = JSON.parse(raw);
    if (!signed.body) throw new Error('Sign response has no encrypted body');
    var info = JSON.parse(String(crypto.decryptStr(String(signed.body))));
    if (!info.time_stamp || !info.sign_text) throw new Error('Sign response lacks fields');
    // NG params use 50 as neutral; preserve the backup's per-voice factors.
    var speed = Math.max(0, Math.min(100, Math.round(params.speed * extra.speed)));
    var volume = Math.max(0, Math.min(100, Math.round(params.volume * extra.volume)));
    var query = {ts: String(info.time_stamp), sign: String(info.sign_text), sid: '',
        vid: vid, volume: String(Math.round(volume * 0.4 - 20)),
        speed: String(Math.round(speed * 4 - 200)), content: text, listen: '0'};
    return {url: SYNTH_URL + '?' + Object.keys(query).map(function(k) {
        return encodeURIComponent(k) + '=' + encodeURIComponent(query[k]);
    }).join('&'), method: 'GET', headers: {'User-Agent': UA}, responseType: 'audio',
        audioContentType: 'audio/(mpeg|wav|x-wav)'};
}
