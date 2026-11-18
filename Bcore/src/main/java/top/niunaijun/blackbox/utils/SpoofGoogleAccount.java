package top.niunaijun.blackbox.utils;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.os.Bundle;

/**
 * Fake Google account handed to sandboxed apps so that "sign in with Google"
 * gates can be satisfied without a real Google login.
 *
 * <p>This is deliberately a local-only spoof: apps see an account and receive a
 * syntactically valid token, but no Google server will ever accept it. It
 * exists so offline features behind a sign-in wall work inside the sandbox, not
 * to impersonate a real user.
 */
public final class SpoofGoogleAccount {

    public static final String TYPE = "com.google";
    public static final String PRIMARY_NAME = "mock.user@gmail.com";
    public static final String SECONDARY_NAME = "virtual.user@gmail.com";

    private static final Account PRIMARY = new Account(PRIMARY_NAME, TYPE);
    private static final Account SECONDARY = new Account(SECONDARY_NAME, TYPE);

    private SpoofGoogleAccount() {
    }

    public static Account primary() {
        return new Account(PRIMARY_NAME, TYPE);
    }

    public static Account[] accounts() {
        return new Account[]{new Account(PRIMARY_NAME, TYPE), new Account(SECONDARY_NAME, TYPE)};
    }

    public static boolean isGoogleType(String type) {
        return TYPE.equals(type);
    }

    public static boolean isSpoofed(Account account) {
        if (account == null || !TYPE.equals(account.type)) {
            return false;
        }
        return PRIMARY_NAME.equals(account.name) || SECONDARY_NAME.equals(account.name);
    }

    /**
     * Shaped like a Google OAuth token so apps that only test for a non-empty
     * value behave as if a login succeeded.
     */
    public static String token(Account account) {
        String name = account == null ? PRIMARY_NAME : account.name;
        return "ya29.spoofed." + Integer.toHexString(name.hashCode()) + ".blackbox";
    }

    public static Bundle tokenBundle(Account account, String authTokenType) {
        Bundle bundle = new Bundle();
        bundle.putString(AccountManager.KEY_ACCOUNT_NAME, account.name);
        bundle.putString(AccountManager.KEY_ACCOUNT_TYPE, account.type);
        bundle.putString(AccountManager.KEY_AUTHTOKEN, token(account));
        bundle.putString("authTokenType", authTokenType);
        return bundle;
    }

    public static Bundle accountsBundle() {
        Bundle result = new Bundle();
        result.putParcelableArray(AccountManager.KEY_ACCOUNTS, accounts());
        return result;
    }
}
