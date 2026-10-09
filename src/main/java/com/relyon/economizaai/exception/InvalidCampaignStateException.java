package com.relyon.economizaai.exception;

public class InvalidCampaignStateException extends DomainException {

    public InvalidCampaignStateException(String currentStatus, String attemptedAction) {
        super("campaign.state.invalid", currentStatus, attemptedAction);
    }
}
