package com.relyon.economizaai.model.enums;

/**
 * The periods the spending report can cover. Not exclusive: a user receives the weekly report,
 * the monthly one or both. Declaration order is the order the API lists them in.
 */
public enum SpendingReportFrequency {
    /** The last seven days, sent every week. */
    WEEKLY,
    /** The previous full calendar month, sent at the start of each month. */
    MONTHLY
}
