package io.github.yuu518.hyperosime;

final class CompatibilityPolicy {
    static final String PHRASE_PACKAGE = "com.miui.phrase";
    static final String PROVIDER_AUTHORITY = "com.miui.phrase.input.provider";
    static final String REGISTER_METHOD = "hyperos_ime_register_reader_v1";

    static boolean isStockIme(String name) {
        return "com.xiaomi.type".equals(name)
                || "com.sohu.inputmethod.sogou.xiaomi".equals(name)
                || "com.iflytek.inputmethod.miui".equals(name)
                || "com.baidu.input_mi".equals(name)
                || "com.miui.securityinputmethod".equals(name);
    }

    static String buttonFunction(String configured, boolean left) {
        if ("clipboard_phrase".equals(configured)
                || "switch_input_method".equals(configured)
                || "no_function".equals(configured)) {
            return configured;
        }
        return left ? "switch_input_method" : "clipboard_phrase";
    }

    static boolean isReader(int callerUid, int imeUid, String callerPackage,
                            String imePackage, boolean registered, boolean inputMethodService) {
        return callerUid >= 10000 && callerUid == imeUid && registered && inputMethodService
                && callerPackage != null && callerPackage.equals(imePackage);
    }
}
