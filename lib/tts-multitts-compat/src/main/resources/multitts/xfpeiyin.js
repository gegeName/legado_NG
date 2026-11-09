// Public webpage protocol constant, not a user credential.
var XF_KEY = 'G%.g7"Y&Nf^40Ee<';
var XF_HOST = java;
var XF_UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36';
function xfCrypto() {
    var Cipher = Packages.javax.crypto.Cipher;
    var SecretKeySpec = Packages.javax.crypto.spec.SecretKeySpec;
    var JString = Packages.java.lang.String;
    var Base64 = Packages.cn.hutool.core.codec.Base64;
    var secret = new SecretKeySpec(new JString(XF_KEY).getBytes('UTF-8'), 'AES');
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
    if (!voice || !voice.extra) throw new Error('音色不存在');
    var extra = voice.extra;
    var vid = String(voice.style_value || extra.voiceCode || '');
    if (!/^\d+$/.test(vid)) throw new Error('讯飞音色编号未解析');
    var crypto = xfCrypto();
    var payload = JSON.stringify({synth_text_hash_code: String(XF_HOST.md5Encode(text))});
    var raw = String(XF_HOST.ajax('https://peiyin.xunfei.cn/web-server/1.0/works_synth_sign,' + JSON.stringify({
        method: 'POST', headers: {'Content-Type': 'application/json', 'User-Agent': XF_UA},
        body: JSON.stringify({req: crypto.encryptBase64(payload)})
    }), 15000));
    var signed = JSON.parse(raw);
    if (!signed.body) throw new Error('讯飞签名响应缺少 body');
    var info = JSON.parse(crypto.decryptStr(String(signed.body)));
    if (!info.time_stamp || !info.sign_text) throw new Error('讯飞签名响应缺少字段');
    var speed = Math.max(0, Math.min(100, Math.round(params.speed * extra.speed)));
    var volume = Math.max(0, Math.min(100, Math.round(params.volume * extra.volume)));
    var query = {ts: String(info.time_stamp), sign: String(info.sign_text), sid: '', vid: vid,
        volume: String(Math.round(volume * 0.4 - 20)), speed: String(Math.round(speed * 4 - 200)), content: text, listen: '0'};
    return {url: 'https://peiyin.xunfei.cn/synth?' + Object.keys(query).map(function(key) {
        return encodeURIComponent(key) + '=' + encodeURIComponent(query[key]);
    }).join('&'), method: 'GET', headers: {'User-Agent': XF_UA}, responseType: 'audio',
        audioContentType: 'audio/(mpeg|wav|x-wav)', timeout: 30};
}
