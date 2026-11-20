package com.relyon.economizaai.repository;

/**
 * The e-mail domains of accounts that are not customers: the team's own and test accounts
 * ({@code @economizaai.app}) and the devices Google Play uses to test each release
 * ({@code @cloudtestlabaccounts.com}). Every metric that leaves internal accounts out matches
 * the lower-cased e-mail against these two patterns, together with the ADMIN role.
 */
public final class InternalAccounts {

    public static final String OWN_DOMAIN_LIKE = "%@economizaai.app";
    public static final String PLAY_TEST_LAB_DOMAIN_LIKE = "%@cloudtestlabaccounts.com";

    private InternalAccounts() {}
}
