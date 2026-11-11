package com.relyon.economizaai.service.sefaz;

/**
 * What an ingest attempt is allowed to PAY for. Passed explicitly down the fetch/parse chain
 * so a caller that only wants the free paths (e.g. the contingency resync, which re-tries the
 * same nota every few hours) cannot buy a captcha token, an Infosimples query or an LLM rescue
 * by accident. Free work — captcha-free scrapes, legacy reconsults, the parsers — always runs.
 *
 * @param captchaSolves whether a captcha solver may buy a token
 * @param infosimples   whether the paid by-chave Infosimples lookup may be called
 * @param llmRescue     whether the LLM may be asked to rescue an unparseable page
 */
public record IngestSpendPolicy(boolean captchaSolves, boolean infosimples, boolean llmRescue) {

    /** The regular chain: every paid layer is available (still subject to caps, budget and breaker). */
    public static final IngestSpendPolicy FULL = new IngestSpendPolicy(true, true, true);

    /** Free paths only — no captcha purchase, no Infosimples, no LLM rescue. */
    public static final IngestSpendPolicy FREE_ONLY = new IngestSpendPolicy(false, false, false);

    /** Same policy with the Infosimples layer switched off (it was already tried for this nota). */
    public IngestSpendPolicy withoutInfosimples() {
        return new IngestSpendPolicy(captchaSolves, false, llmRescue);
    }
}
