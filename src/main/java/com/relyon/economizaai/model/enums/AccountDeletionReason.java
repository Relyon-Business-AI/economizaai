package com.relyon.economizaai.model.enums;

/**
 * Why a user deleted their account — collected (optionally) at deletion and stored,
 * ANONYMIZED, in a table that survives the user's hard delete. Pure churn analytics;
 * never carries user identity. The user-facing labels live in the app (i18n); these
 * are the stable machine codes. {@link #OTHER} pairs with a free-text detail.
 */
public enum AccountDeletionReason {
    NO_LONGER_USING,     // stopped needing it
    SCANNING_PROBLEMS,   // trouble scanning / reading notas
    MISSING_FEATURES,    // wanted features we don't have
    TOO_COMPLICATED,     // found it hard to use
    PRIVACY_CONCERN,     // worried about their data
    USING_ANOTHER_APP,   // switched to a competitor
    TEMPORARY_ACCOUNT,   // throwaway / will make another
    OTHER                // anything else — see the free-text detail
}
