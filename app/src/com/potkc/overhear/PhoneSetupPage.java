package com.potkc.overhear;

/**
 * The pages 「スマホで認証」 shows on the phone: the form (the Soloist API key,
 * the Client ID, whether to log in to Spotify) and one-line messages. Small,
 * UTF-8, dark like the app; everything echoed is escaped, and the stored key
 * never appears (only whether there is one). Pure Java (host tests).
 */
final class PhoneSetupPage {
    static final String SAVED = "保存しました。このページは閉じてかまいません。";
    static final String USED = "この QR コードは使用済みです。もう一度行うには、端末で「スマホで認証」を開き直してください。";
    static final String SAVE_FAILED = "保存できませんでした。端末の画面を確認してください。";

    private static final String STYLE = "body{margin:0;padding:16px;background:#101010;color:#fff;"
            + "font:16px/1.5 sans-serif}h1{font-size:20px;margin:0 0 16px}"
            + "label{display:block;font-weight:bold;margin:16px 0 4px}"
            + "input[type=text],input[type=password]{box-sizing:border-box;width:100%;padding:10px;"
            + "font-size:16px;color:#fff;background:#222;border:1px solid #555;border-radius:4px}"
            + "label.check{font-weight:normal;margin-top:20px}.hint{color:#aaa;font-size:13px;margin:4px 0 0}"
            + ".err{color:#ff8a80;font-weight:bold}button{width:100%;margin-top:24px;padding:12px;"
            + "font-size:16px;color:#101010;background:#fff;border:0;border-radius:4px}";

    private PhoneSetupPage() {
    }

    /**
     * The form, posting back to /setup?n=nonce. hasKey: a key is stored (the
     * field may stay blank); storedClientId: "" for none; typedClientId: what
     * to put in its field (after a problem); error: null, or a line shown
     * above the form.
     */
    static String form(String nonce, boolean hasKey, String storedClientId, String typedClientId, boolean login,
            String error) {
        String cidHint = storedClientId.isEmpty()
                ? "Spotify Developer で作ったアプリの Client ID（32 文字の16進数）。ライブラリに使います。"
                : "保存済み: " + escape(storedClientId) + "。空欄のままなら変わりません。";
        String submit = login ? "保存してログイン" : "保存";
        StringBuilder sb = new StringBuilder(2048);
        sb.append(head("Overhear の設定"));
        sb.append("<h1>Overhear の設定</h1>");
        if (error != null) sb.append("<p class=\"err\">").append(escape(error)).append("</p>");
        sb.append("<form method=\"post\" action=\"").append(PhoneSetup.PATH).append("?n=").append(escape(nonce))
                .append("\" accept-charset=\"utf-8\" autocomplete=\"off\">");
        sb.append("<label for=\"key\">Soloist API キー</label>");
        sb.append("<input id=\"key\" name=\"").append(PhoneSetup.KEY).append("\" type=\"password\"")
                .append(" autocomplete=\"off\" autocapitalize=\"off\" autocorrect=\"off\" spellcheck=\"false\"")
                .append(" placeholder=\"").append(hasKey ? "保存済み（変更する場合のみ）" : "API キーを貼り付け")
                .append("\"").append(hasKey ? "" : " required").append(">");
        sb.append("<label for=\"cid\">Client ID</label>");
        sb.append("<input id=\"cid\" name=\"").append(PhoneSetup.CLIENT_ID).append("\" type=\"text\" maxlength=\"64\"")
                .append(" autocomplete=\"off\" autocapitalize=\"off\" autocorrect=\"off\" spellcheck=\"false\"")
                .append(" value=\"").append(escape(typedClientId)).append("\">");
        sb.append("<p class=\"hint\">").append(cidHint).append("</p>");
        sb.append("<label class=\"check\"><input type=\"checkbox\" name=\"").append(PhoneSetup.LOGIN)
                .append("\" value=\"1\"").append(login ? " checked" : "")
                .append(" onchange=\"document.getElementById('go').textContent=")
                .append("this.checked?'保存してログイン':'保存'\"> Spotify にログインする</label>");
        sb.append("<p class=\"hint\">Client ID が必要です。ログインし直すときは、両方空欄のままでかまいません。</p>");
        sb.append("<button id=\"go\" type=\"submit\">").append(submit).append("</button>");
        sb.append("</form></body></html>");
        return sb.toString();
    }

    /** The line shown above the form for a problem; null for NONE. */
    static String problem(PhoneSetup.Submission.Problem p) {
        switch (p) {
            case BAD_FORM: return "入力を読み取れませんでした。もう一度送信してください。";
            case NEED_KEY: return "Soloist API キーを入力してください。";
            case BAD_CLIENT_ID: return "Client ID は 32 文字の16進数（0–9, a–f）です。";
            case NEED_CLIENT_ID: return "Spotify にログインするには Client ID が必要です。";
            default: return null;
        }
    }

    /** A page with one line of text. */
    static String message(String text) {
        return head("Overhear") + "<p>" + escape(text) + "</p></body></html>";
    }

    /** text escaped for HTML content and double- or single-quoted attributes. */
    static String escape(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                case '\'': sb.append("&#39;"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String head(String title) {
        return "<!DOCTYPE html><html lang=\"ja\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<title>" + escape(title) + "</title><style>" + STYLE + "</style></head><body>";
    }
}
